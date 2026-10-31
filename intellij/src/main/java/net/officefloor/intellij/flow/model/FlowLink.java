package net.officefloor.intellij.flow.model;

import java.util.Objects;

/**
 * A directed connection from one {@link FlowNode} to another.
 */
public final class FlowLink {

	private final String fromId;
	private final String toId;
	private final LinkKind kind;

	/** Label drawn on the connection (output name, exception type, ...); may be empty. */
	private final String label;

	/** Offset into the configuration document where this link is declared (or -1). */
	private final int sourceOffset;

	public FlowLink(String fromId, String toId, LinkKind kind, String label, int sourceOffset) {
		this.fromId = Objects.requireNonNull(fromId, "fromId");
		this.toId = Objects.requireNonNull(toId, "toId");
		this.kind = Objects.requireNonNull(kind, "kind");
		this.label = label == null ? "" : label;
		this.sourceOffset = sourceOffset;
	}

	public String getFromId() {
		return this.fromId;
	}

	public String getToId() {
		return this.toId;
	}

	public LinkKind getKind() {
		return this.kind;
	}

	public String getLabel() {
		return this.label;
	}

	public int getSourceOffset() {
		return this.sourceOffset;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof FlowLink)) {
			return false;
		}
		FlowLink other = (FlowLink) o;
		return this.fromId.equals(other.fromId) && this.toId.equals(other.toId) && this.kind == other.kind
				&& this.label.equals(other.label);
	}

	@Override
	public int hashCode() {
		return Objects.hash(this.fromId, this.toId, this.kind, this.label);
	}

	@Override
	public String toString() {
		return "FlowLink[" + this.fromId + " -" + this.kind + "(" + this.label + ")-> " + this.toId + "]";
	}
}
