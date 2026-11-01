package net.officefloor.intellij.flow.slim;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.officefloor.intellij.flow.slim.SlimEnricher.NodeSlim;
import net.officefloor.officefloorslim.core.OfficeFloorSplit;
import net.officefloor.officefloorslim.graph.FlowGraph;
import net.officefloor.officefloorslim.graph.FlowLink;
import net.officefloor.officefloorslim.graph.FlowNode;
import net.officefloor.officefloorslim.graph.LinkKind;
import net.officefloor.officefloorslim.graph.NodeKind;

/**
 * The flow graph as it would be after splitting some of its long functions: each replaced by the
 * functions of its combined split, with the flows between them, and the links it had joined to
 * them. A preview only: nothing in the configuration or the source changes.
 *
 * <p>
 * What reached the function now reaches its first; what followed it ({@code next}) follows each
 * function whose flow leaves the method, and each early return. A named flow or escalation the
 * function itself raised is drawn from its first function: which proposed function makes that
 * call is not worked out here.
 */
public final class SplitGraph {

	/**
	 * A proposed function, drawn in place of the long function it comes from.
	 *
	 * @param origin   the long function's node id
	 * @param index    its place in the plan, from 0
	 * @param of       how many functions the plan has
	 * @param function the plan's function
	 */
	public record Ghost(String origin, int index, int of, OfficeFloorSplit.Function function) {
	}

	/**
	 * The graph to draw.
	 *
	 * @param graph  the nodes and links, the proposed functions among them
	 * @param ghosts the proposed functions, by node id
	 */
	public record View(FlowGraph graph, Map<String, Ghost> ghosts) {
	}

	private SplitGraph() {
	}

	/** The id of a long function's {@code i}th proposed function. */
	public static String ghostId(String origin, int i) {
		return origin + " #" + (i + 1);
	}

	/**
	 * {@code base} with each function in {@code expand} that has a split using function injection
	 * replaced by its proposed functions; any other is left as it is.
	 */
	public static View expand(FlowGraph base, Map<String, NodeSlim> slims, Set<String> expand) {
		Map<String, OfficeFloorSplit.Plan> plans = new HashMap<>();
		for (String id : expand) {
			NodeSlim s = slims.get(id);
			if (s != null && s.split().longMethod()) {
				OfficeFloorSplit.Plan c = s.split().pricing().combined();
				if (c.feasible() && !c.extractionOnly() && base.hasNode(id)) {
					plans.put(id, c);
				}
			}
		}
		if (plans.isEmpty()) {
			return new View(base, Map.of());
		}

		FlowGraph g = new FlowGraph();
		Map<String, Ghost> ghosts = new HashMap<>();
		for (FlowNode node : base.getNodes()) {
			OfficeFloorSplit.Plan plan = plans.get(node.getId());
			if (plan == null) {
				g.addNode(node);
				continue;
			}
			List<OfficeFloorSplit.Function> fns = plan.functions();
			for (int i = 0; i < fns.size(); i++) {
				OfficeFloorSplit.Function f = fns.get(i);
				String id = ghostId(node.getId(), i);
				g.addNode(new FlowNode(id, NodeKind.PROCEDURE, node.getTitle() + " · " + (i + 1) + "/" + fns.size(),
						subtitle(f), node.getSourceOffset(), node.getJavaClassName(), node.getJavaMethodName()));
				ghosts.put(id, new Ghost(node.getId(), i, fns.size(), f));
			}
		}

		for (FlowLink link : base.getLinks()) {
			for (String from : sources(link, plans)) {
				g.addLink(new FlowLink(from, target(link.getToId(), plans), link.getKind(), link.getLabel(),
						link.getSourceOffset()));
			}
		}
		for (Map.Entry<String, OfficeFloorSplit.Plan> e : plans.entrySet()) {
			String origin = e.getKey();
			List<FlowLink> onward = new ArrayList<>();
			for (FlowLink link : base.getLinks()) {
				if (link.getFromId().equals(origin) && link.getKind() == LinkKind.NEXT) {
					onward.add(link);
				}
			}
			for (OfficeFloorSplit.Link l : e.getValue().links()) {
				String from = ghostId(origin, l.from());
				if (l.to() == OfficeFloorSplit.Link.END) {
					// an early return: on to what follows the method
					for (FlowLink next : onward) {
						g.addLink(new FlowLink(from, target(next.getToId(), plans), LinkKind.FLOW, "return", -1));
					}
					continue;
				}
				String to = ghostId(origin, l.to());
				switch (l.kind()) {
				case NEXT -> g.addLink(new FlowLink(from, to, LinkKind.NEXT, "", -1));
				case FLOW -> g.addLink(new FlowLink(from, to, LinkKind.FLOW, "branch", -1));
				case ESCALATION -> g.addLink(new FlowLink(from, to, LinkKind.ESCALATION, "catch", -1));
				case RETURN -> g.addLink(new FlowLink(from, to, LinkKind.FLOW, "return", -1));
				}
			}
		}
		return new View(g, ghosts);
	}

	/**
	 * Where a link of the base graph starts in the split one: from the same node, or, from a split
	 * function, its {@code next} from each function whose flow leaves the method and anything
	 * else from its first function.
	 */
	private static Set<String> sources(FlowLink link, Map<String, OfficeFloorSplit.Plan> plans) {
		OfficeFloorSplit.Plan plan = plans.get(link.getFromId());
		Set<String> out = new LinkedHashSet<>();
		if (plan == null) {
			out.add(link.getFromId());
		} else if (link.getKind() == LinkKind.NEXT) {
			// the method's end, reached by falling off it or by a return at a function's end
			for (int i : plan.outs()) {
				out.add(ghostId(link.getFromId(), i));
			}
			for (int i : plan.returns()) {
				out.add(ghostId(link.getFromId(), i));
			}
		} else {
			out.add(ghostId(link.getFromId(), 0));
		}
		return out;
	}

	/** What reached a split function now reaches its first. */
	private static String target(String to, Map<String, OfficeFloorSplit.Plan> plans) {
		return plans.containsKey(to) ? ghostId(to, 0) : to;
	}

	private static String subtitle(OfficeFloorSplit.Function f) {
		String kind = switch (f.kind()) {
		case RUN -> "";
		case DECISION -> "decision · ";
		case HANDLER -> "handler · ";
		};
		String declares = f.declares().isEmpty() ? "" : " · declares " + String.join(", ", f.declares());
		String pieces = f.pieces() == 0 ? "" : " · " + f.pieces() + " extracted";
		return kind + "lines " + f.lo() + "–" + f.hi() + declares + pieces;
	}
}
