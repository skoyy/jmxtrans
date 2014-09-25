package com.googlecode.jmxtrans.example;

import com.googlecode.jmxtrans.model.JmxProcess;
import com.googlecode.jmxtrans.model.Query;
import com.googlecode.jmxtrans.model.Server;
import com.googlecode.jmxtrans.model.output.GraphiteWriter;
import com.googlecode.jmxtrans.util.JsonPrinter;

/**
 * This class hits a Graphite server running on port 2003 and sends the memory
 * usage data to it for graphing.
 * 
 * @author jon
 */
public class Graphite {

	private static JsonPrinter printer = new JsonPrinter(System.out);

	public static void main(String[] args) throws Exception {

		GraphiteWriter gw = new GraphiteWriter();
		gw.addSetting(GraphiteWriter.HOST, "192.168.192.133");
		gw.addSetting(GraphiteWriter.PORT, 2003);
		gw.addSetting(GraphiteWriter.DEBUG, true);
		gw.addSetting(GraphiteWriter.ROOT_PREFIX, "jon.foo.bar");

		Query q = Query.builder()
				.setObj("java.lang:type=GarbageCollector,name=ConcurrentMarkSweep")
				.addOutputWriter(gw)
				.build();

		Server server = Server.builder()
				.setHost("w2")
				.setPort("1099")
				.addQuery(q)
				.build();

		JmxProcess process = new JmxProcess(server);
		printer.prettyPrint(process);
		// JmxTransformer transformer = new JmxTransformer();
		// transformer.executeStandalone(process);

		// for (int i = 0; i < 160; i++) {
		// JmxUtils.execute(jmx);
		// Thread.sleep(1000);
		// }
	}

}