package net.officefloor.intellij.flow.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An OfficeFloor flow graph: the functions (nodes) and the flow links between
 * them, parsed from a single configuration document.
 *
 * <p>
 * Nodes keep insertion order so that, all else being equal, the layout is
 * stable and mirrors the declaration order in the file.
 */
public final class FlowGraph {

	private final Map<String, FlowNode> nodesById = new LinkedHashMap<>();
	private final List<FlowLink> links = new ArrayList<>();

	public FlowNode addNode(FlowNode node) {
		this.nodesById.put(node.getId(), node);
		return node;
	}

	public void addLink(FlowLink link) {
		this.links.add(link);
	}

	public boolean hasNode(String id) {
		return this.nodesById.containsKey(id);
	}

	public FlowNode getNode(String id) {
		return this.nodesById.get(id);
	}

	public List<FlowNode> getNodes() {
		return Collections.unmodifiableList(new ArrayList<>(this.nodesById.values()));
	}

	/** Only the links whose endpoints both resolve to known nodes. */
	public List<FlowLink> getLinks() {
		List<FlowLink> resolved = new ArrayList<>(this.links.size());
		for (FlowLink link : this.links) {
			if (this.nodesById.containsKey(link.getFromId()) && this.nodesById.containsKey(link.getToId())) {
				resolved.add(link);
			}
		}
		return Collections.unmodifiableList(resolved);
	}

	/** All links as declared, including those referencing unknown targets. */
	public List<FlowLink> getAllLinks() {
		return Collections.unmodifiableList(new ArrayList<>(this.links));
	}

	/**
	 * Derives the data-flow links: for every variable that is written by one
	 * function and read by another, a link from each writer to each reader.
	 */
	public List<DataFlowLink> getDataFlowLinks() {
		Map<String, List<FlowNode>> writers = new LinkedHashMap<>();
		Map<String, List<FlowNode>> readers = new LinkedHashMap<>();
		Map<String, String> typeLabels = new LinkedHashMap<>();
		for (FlowNode node : this.nodesById.values()) {
			for (VariableAccess access : node.getVariables()) {
				String name = access.getVariableName();
				typeLabels.putIfAbsent(name, access.displayLabel());
				if (access.getDirection().writes()) {
					writers.computeIfAbsent(name, k -> new ArrayList<>()).add(node);
				}
				if (access.getDirection().reads()) {
					readers.computeIfAbsent(name, k -> new ArrayList<>()).add(node);
				}
			}
		}
		List<DataFlowLink> links = new ArrayList<>();
		for (Map.Entry<String, List<FlowNode>> entry : writers.entrySet()) {
			String name = entry.getKey();
			List<FlowNode> rs = readers.get(name);
			if (rs == null) {
				continue;
			}
			for (FlowNode w : entry.getValue()) {
				for (FlowNode r : rs) {
					if (!w.getId().equals(r.getId())) {
						links.add(new DataFlowLink(w.getId(), r.getId(), name, typeLabels.get(name)));
					}
				}
			}
		}
		return Collections.unmodifiableList(links);
	}

	public boolean hasAnyVariables() {
		for (FlowNode node : this.nodesById.values()) {
			if (node.hasVariables()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Derives the parameter flows: for each control-flow link into a function that
	 * declares an {@code @Parameter}, the value passed is the source function's
	 * return. Only links from a function with a non-void return are included (the
	 * argument must come "from the previous function").
	 */
	public List<DataFlowLink> getParameterFlowLinks() {
		List<DataFlowLink> links = new ArrayList<>();
		for (FlowLink link : getLinks()) {
			if (link.getKind() != LinkKind.NEXT && link.getKind() != LinkKind.FLOW) {
				continue;
			}
			FlowNode source = this.nodesById.get(link.getFromId());
			FlowNode target = this.nodesById.get(link.getToId());
			if (source == null || target == null || !target.hasParameterType() || !source.hasReturnType()) {
				continue;
			}
			links.add(new DataFlowLink(source.getId(), target.getId(), PARAMETER_VARIABLE,
					VariableAccess.simpleType(target.getParameterType())));
		}
		return links;
	}

	/** Sentinel variable-name used to key the per-node parameter/return chip. */
	public static final String PARAMETER_VARIABLE = "\u0001parameter";

	public boolean hasAnyDataFlow() {
		for (FlowNode node : this.nodesById.values()) {
			if (node.hasVariables() || node.hasReturnType() || node.hasParameterType()) {
				return true;
			}
		}
		return false;
	}

	public boolean isEmpty() {
		return this.nodesById.isEmpty();
	}

	public int nodeCount() {
		return this.nodesById.size();
	}
}
