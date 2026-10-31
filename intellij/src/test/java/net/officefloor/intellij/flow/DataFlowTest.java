package net.officefloor.intellij.flow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import net.officefloor.intellij.flow.model.DataFlowLink;
import net.officefloor.intellij.flow.model.FlowGraph;
import net.officefloor.intellij.flow.model.FlowLink;
import net.officefloor.intellij.flow.model.FlowNode;
import net.officefloor.intellij.flow.model.LinkKind;
import net.officefloor.intellij.flow.model.NodeKind;
import net.officefloor.intellij.flow.model.VariableAccess;
import net.officefloor.intellij.flow.model.VariableDirection;

public class DataFlowTest {

	private static FlowNode fn(String id) {
		return new FlowNode(id, NodeKind.PROCEDURE, id, "", -1, "Logic", id);
	}

	@Test
	public void writerFeedsReader() {
		FlowGraph graph = new FlowGraph();
		FlowNode producer = graph.addNode(fn("producer"));
		FlowNode consumer = graph.addNode(fn("consumer"));
		FlowNode unrelated = graph.addNode(fn("unrelated"));

		producer.addVariable(new VariableAccess("app.Person", "app.Person", null, VariableDirection.OUT));
		consumer.addVariable(new VariableAccess("app.Person", "app.Person", null, VariableDirection.IN));
		// A read with no writer produces no link.
		unrelated.addVariable(new VariableAccess("java.lang.String", "java.lang.String", null, VariableDirection.IN));

		List<DataFlowLink> links = graph.getDataFlowLinks();
		assertEquals(1, links.size());
		assertEquals("producer", links.get(0).getWriterId());
		assertEquals("consumer", links.get(0).getReaderId());
		assertEquals("app.Person", links.get(0).getVariableName());
		assertTrue(graph.hasAnyVariables());
	}

	@Test
	public void qualifierDistinguishesVariablesOfSameType() {
		FlowGraph graph = new FlowGraph();
		FlowNode producer = graph.addNode(fn("producer"));
		FlowNode consumer = graph.addNode(fn("consumer"));

		// Same type String, but different qualifiers => different variables => no match.
		producer.addVariable(new VariableAccess("app.Description-java.lang.String", "java.lang.String", "app.Description",
				VariableDirection.OUT));
		consumer.addVariable(new VariableAccess("java.lang.String", "java.lang.String", null, VariableDirection.IN));

		assertEquals(0, graph.getDataFlowLinks().size());

		// Now add a matching qualified reader.
		consumer.addVariable(new VariableAccess("app.Description-java.lang.String", "java.lang.String", "app.Description",
				VariableDirection.IN));
		assertEquals(1, graph.getDataFlowLinks().size());
	}

	@Test
	public void returnValueFlowsToNextFunctionParameter() {
		FlowGraph graph = new FlowGraph();
		FlowNode a = graph.addNode(fn("a"));
		FlowNode b = graph.addNode(fn("b"));
		FlowNode c = graph.addNode(fn("c")); // no return, so no parameter flow from it
		a.setReturnType("app.Order");
		b.setParameterType("app.Order");
		c.setParameterType("app.Order");
		graph.addLink(new FlowLink("a", "b", LinkKind.NEXT, "", -1));
		graph.addLink(new FlowLink("c", "b", LinkKind.FLOW, "retry", -1));

		List<DataFlowLink> params = graph.getParameterFlowLinks();
		assertEquals(1, params.size());
		assertEquals("a", params.get(0).getWriterId());
		assertEquals("b", params.get(0).getReaderId());
		assertEquals(FlowGraph.PARAMETER_VARIABLE, params.get(0).getVariableName());
		assertTrue(graph.hasAnyDataFlow());
	}

	@Test
	public void varCountsAsBothReaderAndWriter() {
		FlowGraph graph = new FlowGraph();
		FlowNode a = graph.addNode(fn("a"));
		FlowNode b = graph.addNode(fn("b"));
		a.addVariable(new VariableAccess("app.State", "app.State", null, VariableDirection.IN_OUT));
		b.addVariable(new VariableAccess("app.State", "app.State", null, VariableDirection.IN));

		// 'a' writes (via IN_OUT) -> 'b' reads.
		List<DataFlowLink> links = graph.getDataFlowLinks();
		assertEquals(1, links.size());
		assertEquals("a", links.get(0).getWriterId());
		assertEquals("b", links.get(0).getReaderId());
	}
}
