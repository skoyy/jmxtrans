package com.googlecode.jmxtrans.model.output.elastic;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.io.Resources;
import com.googlecode.jmxtrans.exceptions.LifecycleException;
import com.googlecode.jmxtrans.model.Query;
import com.googlecode.jmxtrans.model.Result;
import com.googlecode.jmxtrans.model.Server;
import com.googlecode.jmxtrans.model.ValidationException;
import com.googlecode.jmxtrans.model.naming.StringUtils;
import com.googlecode.jmxtrans.model.output.BaseOutputWriter;
import io.searchbox.client.JestClient;
import io.searchbox.client.JestClientFactory;
import io.searchbox.client.JestResult;
import io.searchbox.client.config.HttpClientConfig;
import io.searchbox.core.Index;
import io.searchbox.indices.CreateIndex;
import io.searchbox.indices.IndicesExists;
import io.searchbox.indices.mapping.PutMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.concurrent.NotThreadSafe;
import java.io.IOException;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import static com.googlecode.jmxtrans.model.PropertyResolver.resolveProps;
import static com.googlecode.jmxtrans.model.naming.KeyUtils.getKeyString;
import static com.googlecode.jmxtrans.util.NumberUtils.isNumeric;

/**
 * Feed data directly into elastic.
 *
 * @author Peter Paul Bakker - pp@stokpop.nl
 */

@NotThreadSafe
public class ElasticWriter extends BaseOutputWriter {
	
	private static final Logger log = LoggerFactory.getLogger(ElasticWriter.class);
	
	private static final String DEFAULT_ROOT_PREFIX = "jmxtrans";
	private static final String TYPE_NAME = "jmx-entry";

	private static final Object CREATE_MAPPING_LOCK = new Object();

	private JestClient jestClient;
	private final String rootPrefix;
	private final String connectionUrl;
	private String indexName;

	@JsonCreator
	public ElasticWriter(
			@JsonProperty("typeNames") ImmutableList<String> typeNames,
			@JsonProperty("booleanAsNumber") boolean booleanAsNumber,
			@JsonProperty("rootPrefix") String rootPrefix,
			@JsonProperty("debug") Boolean debugEnabled,
			@JsonProperty("connectionUrl") String connectionUrl,
			@JsonProperty("settings") Map<String, Object> settings) throws IOException {

		super(typeNames, booleanAsNumber, debugEnabled, settings);

		this.rootPrefix = resolveProps(
				firstNonNull(
						rootPrefix,
						(String) getSettings().get("rootPrefix"),
						DEFAULT_ROOT_PREFIX));		

		this.connectionUrl = connectionUrl;
		this.indexName = this.rootPrefix + "_jmx-entries";
		this.jestClient = createJestClient(connectionUrl);
	}

	private JestClient createJestClient(String connectionUrl) {
		log.info("Create a jest elastic search client for connection url [{}]", connectionUrl);
		JestClientFactory factory = new JestClientFactory();
		factory.setHttpClientConfig(
				new HttpClientConfig.Builder(connectionUrl)
						.multiThreaded(true)
						.build());
		return factory.getObject();
	}

	@Override
	protected void internalWrite(Server server, Query query, ImmutableList<Result> results) throws Exception {
		List<String> typeNames = this.getTypeNames();

		for (Result result : results) {
			log.debug("Query result: [{}]", result);
			Map<String, Object> resultValues = result.getValues();
			for (Entry<String, Object> values : resultValues.entrySet()) {
				Object value = values.getValue();
				if (isNumeric(value)) {
					Map<String, Object> map = createMap(server, query, typeNames, result, values, value);
					log.debug("Insert into Elastic: Index: [{}] Type: [{}] Map: [{}]", indexName, TYPE_NAME, map);
					Index index = new Index.Builder(map).index(indexName).type(TYPE_NAME).build();
					jestClient.execute(index);
				} else {
					log.warn("Unable to submit non-numeric value to Elastic: [{}] from result [{}]", value, result);
				}
			}
		}
	}

	private Map<String, Object> createMap(Server server, Query query, List<String> typeNames, Result result, Entry<String, Object> values, Object value) {

		String keyString = getKeyString(server, query, result, values, typeNames, this.rootPrefix);

		Map<String, Object> map = new HashMap<String, Object>();
		map.put("server", createAlias(server));
		map.put("metric", keyString);
		map.put("value", Double.parseDouble(value.toString()));
		map.put("resultAlias", result.getKeyAlias());
		map.put("attributeName", result.getAttributeName());
		map.put("key", values.getKey());
		map.put("timestamp", result.getEpoch());

		return map;
	}

	private String createAlias(Server server) {
		String alias;
		if (server.getAlias() != null) {
			alias = server.getAlias();
		} else {
			alias = server.getHost() + "_" + server.getPort();
			alias = StringUtils.cleanupStr(alias);
		}
		return alias;
	}

	@VisibleForTesting
	void setJestClient(JestClient jestClient) {
		log.info("Note: using injected jestClient instead of default client: [{}]", jestClient);
		this.jestClient = jestClient;
	}

	private static void createMappingIfNeeded(JestClient jestClient, String indexName, String typeName) throws ElasticWriterException, IOException {
		synchronized (CREATE_MAPPING_LOCK) {
			IndicesExists indicesExists = new IndicesExists.Builder(indexName).build();
			boolean indexExists = jestClient.execute(indicesExists).isSucceeded();

			if (!indexExists) {

				CreateIndex createIndex = new CreateIndex.Builder(indexName).build();
				jestClient.execute(createIndex);

				URL url = ElasticWriter.class.getResource("/elastic-mapping.json");
				String mapping = Resources.toString(url, Charsets.UTF_8);

				PutMapping putMapping = new PutMapping.Builder(indexName, typeName,mapping).build();

				JestResult result = jestClient.execute(putMapping);
				if (!result.isSucceeded()) {
					throw new ElasticWriterException(String.format("Failed to create mapping: %s", result.getErrorMessage()));
				}
				else {
					log.info("Created mapping for index {}", indexName);
				}
			}
		}
	}

	@Override
	public void start() throws LifecycleException {
		super.start();
		try {
			createMappingIfNeeded(jestClient, indexName, TYPE_NAME);
		} catch (Exception e) {
			throw new LifecycleException("Failed to create elastic mapping.", e);
		}
	}

	@Override
	public void stop() throws LifecycleException {
		super.stop();
		jestClient.shutdownClient();
	}

	@Override
	public void validateSetup(Server server, Query query) throws ValidationException {

	}

	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder("ElasticWriter{");
		sb.append("rootPrefix='").append(rootPrefix).append('\'');
		sb.append(", connectionUrl='").append(connectionUrl).append('\'');
		sb.append(", indexName='").append(indexName).append('\'');
		sb.append('}');
		return sb.toString();
	}
}
