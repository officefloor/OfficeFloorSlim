package net.officefloor.intellij.flow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.junit.Test;

import net.officefloor.intellij.flow.layout.LayeredLayout;
import net.officefloor.intellij.flow.layout.LayoutResult;
import net.officefloor.intellij.flow.model.FlowGraph;
import net.officefloor.intellij.flow.model.FlowLink;
import net.officefloor.intellij.flow.model.FlowNode;
import net.officefloor.intellij.flow.model.LinkKind;
import net.officefloor.intellij.flow.model.NodeKind;

public class LayeredLayoutTest {

	private static FlowNode node(String id, NodeKind kind) {
		return new FlowNode(id, kind, id, "", -1, null, null);
	}

	@Test
	public void layersFlowDownFromEndpoint() {
		FlowGraph graph = new FlowGraph();
		graph.addNode(node("GET /thing", NodeKind.HTTP_ENDPOINT));
		graph.addNode(node("a", NodeKind.PROCEDURE));
		graph.addNode(node("b", NodeKind.PROCEDURE));
		graph.addNode(node("c", NodeKind.PROCEDURE));
		graph.addLink(new FlowLink("GET /thing", "a", LinkKind.ROUTE, "", -1));
		graph.addLink(new FlowLink("a", "b", LinkKind.NEXT, "", -1));
		graph.addLink(new FlowLink("a", "c", LinkKind.FLOW, "branch", -1));

		Map<String, Integer> layers = new LayeredLayout().computeLayers(graph);
		assertEquals(0, (int) layers.get("GET /thing"));
		assertEquals(1, (int) layers.get("a"));
		assertEquals(2, (int) layers.get("b"));
		assertEquals(2, (int) layers.get("c"));
	}

	@Test
	public void longestPathWins() {
		// a -> b -> c and a -> c : c must sit below b (longest path), not beside it.
		FlowGraph graph = new FlowGraph();
		graph.addNode(node("a", NodeKind.PROCEDURE));
		graph.addNode(node("b", NodeKind.PROCEDURE));
		graph.addNode(node("c", NodeKind.PROCEDURE));
		graph.addLink(new FlowLink("a", "b", LinkKind.NEXT, "", -1));
		graph.addLink(new FlowLink("b", "c", LinkKind.NEXT, "", -1));
		graph.addLink(new FlowLink("a", "c", LinkKind.FLOW, "", -1));

		Map<String, Integer> layers = new LayeredLayout().computeLayers(graph);
		assertEquals(0, (int) layers.get("a"));
		assertEquals(1, (int) layers.get("b"));
		assertEquals(2, (int) layers.get("c"));
	}

	@Test
	public void cyclesDoNotHang() {
		FlowGraph graph = new FlowGraph();
		graph.addNode(node("a", NodeKind.PROCEDURE));
		graph.addNode(node("b", NodeKind.PROCEDURE));
		graph.addLink(new FlowLink("a", "b", LinkKind.NEXT, "", -1));
		graph.addLink(new FlowLink("b", "a", LinkKind.FLOW, "loop", -1)); // back edge

		LayeredLayout engine = new LayeredLayout();
		Map<String, Integer> layers = engine.computeLayers(graph);
		assertEquals(0, (int) layers.get("a"));
		assertEquals(1, (int) layers.get("b"));

		LayoutResult result = engine.layout(graph);
		assertTrue("canvas has positive size", result.getWidth() > 0 && result.getHeight() > 0);
		assertEquals(2, result.getLayerCount());
	}

	@Test
	public void emptyGraphLaysOut() {
		LayoutResult result = new LayeredLayout().layout(new FlowGraph());
		assertEquals(0, result.getLayerCount());
		assertTrue(result.getWidth() > 0);
	}
}
