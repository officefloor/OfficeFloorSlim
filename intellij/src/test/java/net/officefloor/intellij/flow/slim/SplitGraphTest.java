package net.officefloor.intellij.flow.slim;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.Test;

import net.officefloor.intellij.flow.slim.SlimEnricher.NodeSlim;
import net.officefloor.officefloorslim.core.MethodSplit;
import net.officefloor.officefloorslim.graph.FlowGraph;
import net.officefloor.officefloorslim.graph.FlowLink;
import net.officefloor.officefloorslim.graph.FlowNode;
import net.officefloor.officefloorslim.graph.LinkKind;
import net.officefloor.officefloorslim.graph.NodeKind;

/** The graph as it would be after splitting a long function: its functions in its place. */
public class SplitGraphTest {

	private static String lines(String statement, int n) {
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < n; i++) {
			b.append("\t\t").append(statement.replace("#", String.valueOf(i))).append("\n");
		}
		return b.toString();
	}

	private static MethodSplit method(String body) {
		return MethodSplit.at("A.java", "class A {\n\tvoid f(int a) {\n" + body + "\t}\n}\n", 2);
	}

	/** POST /x -> f -> g, f the long function. */
	private static FlowGraph graph() {
		FlowGraph g = new FlowGraph();
		g.addNode(new FlowNode("POST /x", NodeKind.HTTP_ENDPOINT, "POST /x", "", -1, null, null));
		g.addNode(new FlowNode("f", NodeKind.PROCEDURE, "f", "A#f", 10, "A", "f"));
		g.addNode(new FlowNode("g", NodeKind.PROCEDURE, "g", "B#g", 20, "B", "g"));
		g.addLink(new FlowLink("POST /x", "f", LinkKind.ROUTE, "", -1));
		g.addLink(new FlowLink("f", "g", LinkKind.NEXT, "", -1));
		return g;
	}

	private static Set<String> links(FlowGraph g) {
		Set<String> out = new TreeSet<>();
		for (FlowLink l : g.getLinks()) {
			out.add(l.getFromId() + " > " + l.getToId() + " " + l.getKind() + (l.getLabel().isEmpty() ? "" : " " + l.getLabel()));
		}
		return out;
	}

	@Test
	public void straightLineSplitsIntoAChainInTheFunctionsPlace() {
		// a void method of 40 lines that read the parameter: two functions, the second flowing on to g
		MethodSplit f = method(lines("System.out.println(a + #);", 40));
		SplitGraph.View v = SplitGraph.expand(graph(), Map.of("f", new NodeSlim(null, f)), Set.of("f"));
		assertFalse(v.graph().hasNode("f"));
		assertTrue(v.graph().hasNode("f #1") && v.graph().hasNode("f #2"));
		assertEquals(Set.of("POST /x > f #1 ROUTE", "f #1 > f #2 NEXT", "f #2 > g NEXT"), links(v.graph()));
		assertEquals("f", v.ghosts().get("f #2").origin());
		assertEquals(1, v.ghosts().get("f #2").index());
		assertEquals("f · 2/2", v.graph().getNode("f #2").getTitle());
		assertTrue(v.graph().getNode("f #1").getSubtitle().startsWith("lines 3–"));
	}

	/**
	 * Each branch updates two values that are read after the if, so any method extracted from it
	 * returns two (a record, refused): the combined split cuts at the if instead, a decision whose
	 * branches are flows, meeting at the join.
	 */
	@Test
	public void anIfElseBecomesADecisionWithBranchesAndAJoin() {
		MethodSplit f = method("\t\tint s = a * 2;\n\t\tint t = a * 3;\n\t\tif (a > 0) {\n"
				+ lines("s += t * #; t += s * #;", 20) + "\t\t} else {\n" + lines("s -= t * #; t -= s * #;", 20)
				+ "\t\t}\n\t\tSystem.out.println(s + t);\n");
		assertEquals(0, f.pricing().combined().pieces());
		SplitGraph.View v = SplitGraph.expand(graph(), Map.of("f", new NodeSlim(null, f)), Set.of("f"));
		assertEquals(Set.of("POST /x > f #1 ROUTE", "f #1 > f #2 NEXT", "f #2 > f #3 FLOW branch",
				"f #2 > f #4 FLOW branch", "f #3 > f #5 NEXT", "f #4 > f #5 NEXT", "f #5 > g NEXT"), links(v.graph()));
		assertTrue(v.graph().getNode("f #2").getSubtitle().startsWith("decision · "));
	}

	@Test
	public void guardsReturnEarlyOnToWhatFollows() {
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < 14; i++) {
			b.append("\t\tif (a == ").append(i).append(") {\n\t\t\treturn;\n\t\t}\n");
		}
		MethodSplit f = method(b + "\t\tSystem.out.println(a);\n");
		SplitGraph.View v = SplitGraph.expand(graph(), Map.of("f", new NodeSlim(null, f)), Set.of("f"));
		assertTrue(links(v.graph()).toString(), links(v.graph()).contains("f #1 > g FLOW return"));
		assertTrue(links(v.graph()).contains("f #2 > g NEXT"));
	}

	@Test
	public void nothingToExpandLeavesTheGraphAsItIs() {
		FlowGraph base = graph();
		// not asked for, and a plan of one function (extraction only): the same graph
		MethodSplit loop = method("\t\tint s = 0;\n\t\tfor (int i = 0; i < a; i++) {\n" + lines("s += i * #;", 30)
				+ "\t\t}\n\t\tSystem.out.println(s);\n");
		assertSame(base, SplitGraph.expand(base, Map.of("f", new NodeSlim(null, loop)), Set.of()).graph());
		assertSame(base, SplitGraph.expand(base, Map.of("f", new NodeSlim(null, loop)), Set.of("f")).graph());
	}
}
