package net.officefloor.intellij.flow.layout;

/** Immutable rectangle for a laid-out node (device-independent units). */
public final class NodeBounds {

	public final double x;
	public final double y;
	public final double width;
	public final double height;
	public final int layer;

	public NodeBounds(double x, double y, double width, double height, int layer) {
		this.x = x;
		this.y = y;
		this.width = width;
		this.height = height;
		this.layer = layer;
	}

	public double centerX() {
		return this.x + (this.width / 2);
	}

	public double centerY() {
		return this.y + (this.height / 2);
	}

	public double bottom() {
		return this.y + this.height;
	}

	public double right() {
		return this.x + this.width;
	}

	@Override
	public String toString() {
		return "NodeBounds[x=" + this.x + ", y=" + this.y + ", layer=" + this.layer + "]";
	}
}
