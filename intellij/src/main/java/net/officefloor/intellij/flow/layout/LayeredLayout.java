package net.officefloor.intellij.flow.layout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.officefloor.intellij.flow.model.FlowGraph;
import net.officefloor.intellij.flow.model.FlowLink;
import net.officefloor.intellij.flow.model.FlowNode;

/**
 * Auto-layout for a {@link FlowGraph}, flowing top-to-bottom down the page like
 * a flow diagram.
 *
 * <p>
 * This is a simplified Sugiyama-style layered layout:
 * <ol>
 * <li>cycles are broken by classifying "back" edges via DFS;</li>
 * <li>nodes are assigned to layers by longest path from a source (so HTTP
 * endpoints sit at the top and downstream functions below them);</li>
 * <li>within each layer, nodes are ordered by the barycenter of their parents
 * to reduce edge crossings;</li>
 * <li>coordinates are assigned, centring each layer horizontally.</li>
 * </ol>
 */
public final class LayeredLayout {

	public double nodeWidth = 230;
	public double nodeHeight = 60;
	// Gaps leave room for the variable chips drawn on a node's sides in data-flow mode.
	public double horizontalGap = 150;
	public double verticalGap = 84;
	public double margin = 56;
	// Wider left/right gutter so data-flow chips drawn outside the edge nodes stay on-screen.
	public double sideMargin = 180;

	public LayoutResult layout(FlowGraph graph) {
		List<FlowNode> nodes = graph.getNodes();
		List<FlowLink> links = graph.getLinks();

		if (nodes.isEmpty()) {
			return new LayoutResult(new LinkedHashMap<>(), this.sideMargin * 2, this.margin * 2, 0, new HashSet<>());
		}

		// Adjacency (forward) and back-edge detection.
		Map<String, List<String>> outgoing = new HashMap<>();
		Map<String, List<String>> incoming = new HashMap<>();
		for (FlowNode node : nodes) {
			outgoing.put(node.getId(), new ArrayList<>());
			incoming.put(node.getId(), new ArrayList<>());
		}
		Set<FlowLink> backLinks = detectBackLinks(nodes, links);
		for (FlowLink link : links) {
			if (backLinks.contains(link)) {
				continue; // ignore for layering; still rendered
			}
			outgoing.get(link.getFromId()).add(link.getToId());
			incoming.get(link.getToId()).add(link.getFromId());
		}

		// Longest-path layering over the forward DAG.
		Map<String, Integer> layer = assignLayers(nodes, outgoing, incoming);

		// Group by layer, preserving declaration order within a layer.
		int maxLayer = 0;
		for (int l : layer.values()) {
			maxLayer = Math.max(maxLayer, l);
		}
		List<List<String>> layers = new ArrayList<>();
		for (int i = 0; i <= maxLayer; i++) {
			layers.add(new ArrayList<>());
		}
		for (FlowNode node : nodes) {
			layers.get(layer.get(node.getId())).add(node.getId());
		}

		// Reduce crossings: order each layer by the barycenter of its parents.
		orderByBarycenter(layers, incoming);

		// Assign coordinates.
		double maxLayerWidth = 0;
		for (List<String> row : layers) {
			maxLayerWidth = Math.max(maxLayerWidth, layerWidth(row.size()));
		}

		Map<String, NodeBounds> bounds = new LinkedHashMap<>();
		double y = this.margin;
		for (int l = 0; l < layers.size(); l++) {
			List<String> row = layers.get(l);
			double rowWidth = layerWidth(row.size());
			double x = this.sideMargin + ((maxLayerWidth - rowWidth) / 2);
			for (String id : row) {
				bounds.put(id, new NodeBounds(x, y, this.nodeWidth, this.nodeHeight, l));
				x += this.nodeWidth + this.horizontalGap;
			}
			y += this.nodeHeight + this.verticalGap;
		}

		double canvasWidth = (this.sideMargin * 2) + maxLayerWidth;
		double canvasHeight = this.margin + (layers.size() * (this.nodeHeight + this.verticalGap));
		return new LayoutResult(bounds, canvasWidth, canvasHeight, layers.size(), backLinks);
	}

	private double layerWidth(int count) {
		if (count <= 0) {
			return 0;
		}
		return (count * this.nodeWidth) + ((count - 1) * this.horizontalGap);
	}

	/**
	 * Classifies edges whose target is currently on the DFS recursion stack as
	 * back edges, so the layering can treat the graph as a DAG.
	 */
	private Set<FlowLink> detectBackLinks(List<FlowNode> nodes, List<FlowLink> links) {
		Map<String, List<FlowLink>> out = new HashMap<>();
		for (FlowNode node : nodes) {
			out.put(node.getId(), new ArrayList<>());
		}
		for (FlowLink link : links) {
			out.get(link.getFromId()).add(link);
		}

		Set<FlowLink> back = new HashSet<>();
		Set<String> visited = new HashSet<>();
		Set<String> onStack = new HashSet<>();
		for (FlowNode node : nodes) {
			if (!visited.contains(node.getId())) {
				dfs(node.getId(), out, visited, onStack, back);
			}
		}
		return back;
	}

	private void dfs(String id, Map<String, List<FlowLink>> out, Set<String> visited, Set<String> onStack,
			Set<FlowLink> back) {
		visited.add(id);
		onStack.add(id);
		for (FlowLink link : out.get(id)) {
			String target = link.getToId();
			if (target.equals(id)) {
				back.add(link); // self loop
			} else if (onStack.contains(target)) {
				back.add(link); // edge back up the current path
			} else if (!visited.contains(target)) {
				dfs(target, out, visited, onStack, back);
			}
		}
		onStack.remove(id);
	}

	private Map<String, Integer> assignLayers(List<FlowNode> nodes, Map<String, List<String>> outgoing,
			Map<String, List<String>> incoming) {
		Map<String, Integer> layer = new HashMap<>();
		for (FlowNode node : nodes) {
			layer.put(node.getId(), 0);
		}

		// Kahn topological order over the forward DAG.
		Map<String, Integer> indegree = new HashMap<>();
		for (FlowNode node : nodes) {
			indegree.put(node.getId(), incoming.get(node.getId()).size());
		}
		List<String> queue = new ArrayList<>();
		for (FlowNode node : nodes) {
			if (indegree.get(node.getId()) == 0) {
				queue.add(node.getId());
			}
		}
		List<String> topo = new ArrayList<>();
		while (!queue.isEmpty()) {
			String id = queue.remove(0);
			topo.add(id);
			for (String target : outgoing.get(id)) {
				int d = indegree.get(target) - 1;
				indegree.put(target, d);
				if (d == 0) {
					queue.add(target);
				}
			}
		}
		// Any nodes not reached (shouldn't happen once back edges are removed) keep layer 0.
		for (String id : topo) {
			for (String target : outgoing.get(id)) {
				layer.put(target, Math.max(layer.get(target), layer.get(id) + 1));
			}
		}
		return layer;
	}

	private void orderByBarycenter(List<List<String>> layers, Map<String, List<String>> incoming) {
		for (int l = 1; l < layers.size(); l++) {
			List<String> parentRow = layers.get(l - 1);
			Map<String, Integer> parentIndex = new HashMap<>();
			for (int i = 0; i < parentRow.size(); i++) {
				parentIndex.put(parentRow.get(i), i);
			}
			List<String> row = layers.get(l);
			Map<String, Double> bary = new HashMap<>();
			for (int i = 0; i < row.size(); i++) {
				String id = row.get(i);
				List<String> parents = incoming.get(id);
				double sum = 0;
				int count = 0;
				for (String p : parents) {
					Integer idx = parentIndex.get(p);
					if (idx != null) {
						sum += idx;
						count++;
					}
				}
				// Nodes with no positioned parent keep their current order (stable).
				bary.put(id, count == 0 ? i : sum / count);
			}
			row.sort((a, b) -> Double.compare(bary.get(a), bary.get(b)));
		}
	}

	/** Exposed for testing: layer index assigned to each node. */
	public Map<String, Integer> computeLayers(FlowGraph graph) {
		List<FlowNode> nodes = graph.getNodes();
		List<FlowLink> links = graph.getLinks();
		Map<String, List<String>> outgoing = new HashMap<>();
		Map<String, List<String>> incoming = new HashMap<>();
		for (FlowNode node : nodes) {
			outgoing.put(node.getId(), new ArrayList<>());
			incoming.put(node.getId(), new ArrayList<>());
		}
		Set<FlowLink> backLinks = detectBackLinks(nodes, links);
		for (FlowLink link : links) {
			if (backLinks.contains(link)) {
				continue;
			}
			outgoing.get(link.getFromId()).add(link.getToId());
			incoming.get(link.getToId()).add(link.getFromId());
		}
		return Collections.unmodifiableMap(assignLayers(nodes, outgoing, incoming));
	}
}
