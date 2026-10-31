package net.officefloor.intellij.flow.layout;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

import net.officefloor.intellij.flow.model.FlowLink;

/** Result of laying out a flow graph: node positions and overall canvas size. */
public final class LayoutResult {

	private final Map<String, NodeBounds> bounds;
	private final double width;
	private final double height;
	private final int layerCount;

	/** Links that were detected as cycle-breaking "back" edges (drawn specially). */
	private final Set<FlowLink> backLinks;

	public LayoutResult(Map<String, NodeBounds> bounds, double width, double height, int layerCount,
			Set<FlowLink> backLinks) {
		this.bounds = Collections.unmodifiableMap(bounds);
		this.width = width;
		this.height = height;
		this.layerCount = layerCount;
		this.backLinks = Collections.unmodifiableSet(backLinks);
	}

	public NodeBounds boundsFor(String nodeId) {
		return this.bounds.get(nodeId);
	}

	public Map<String, NodeBounds> getBounds() {
		return this.bounds;
	}

	public double getWidth() {
		return this.width;
	}

	public double getHeight() {
		return this.height;
	}

	public int getLayerCount() {
		return this.layerCount;
	}

	public boolean isBackLink(FlowLink link) {
		return this.backLinks.contains(link);
	}
}
