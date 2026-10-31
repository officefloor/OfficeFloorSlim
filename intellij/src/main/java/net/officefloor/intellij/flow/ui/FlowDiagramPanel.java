package net.officefloor.intellij.flow.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.CubicCurve2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.JPanel;

import com.intellij.ui.JBColor;

import net.officefloor.intellij.flow.layout.LayoutResult;
import net.officefloor.intellij.flow.layout.NodeBounds;
import net.officefloor.intellij.flow.model.DataFlowLink;
import net.officefloor.intellij.flow.model.FlowGraph;
import net.officefloor.intellij.flow.model.FlowLink;
// FlowGraph.PARAMETER_VARIABLE referenced below.
import net.officefloor.intellij.flow.model.FlowNode;
import net.officefloor.intellij.flow.model.HttpInput;
import net.officefloor.intellij.flow.model.LinkKind;
import net.officefloor.intellij.flow.model.NodeKind;
import net.officefloor.intellij.flow.model.VariableAccess;

/**
 * Swing component that renders a {@link FlowGraph} using a pre-computed
 * {@link LayoutResult}. Supports zoom (Ctrl + mouse wheel), hover highlighting
 * and click-to-navigate.
 */
public class FlowDiagramPanel extends JPanel {

	/** Notified when the user activates (clicks) a node or a link. */
	public interface NavigationListener {
		/**
		 * @param preferYaml true to jump to the YAML declaration rather than the Java
		 *                   method (used for a double-click; a single click prefers the
		 *                   implementing method).
		 */
		void onNode(FlowNode node, boolean preferYaml);

		void onLink(FlowLink link);
	}

	private final FlowGraph graph;
	private final LayoutResult layout;
	private NavigationListener navigationListener;

	/** Paths of the most recently painted links, for click hit-testing (model coords). */
	private final Map<FlowLink, Path2D> linkPaths = new LinkedHashMap<>();

	/** Chips painted in the current frame, for hover/click hit-testing (model coords). */
	private final List<Chip> chips = new ArrayList<>();

	private double scale = 1.0;
	private boolean showControlFlow = true;
	private boolean showDataFlow;
	private boolean showHttpInputs;
	private FlowNode hovered;

	/** Visual style of a chip, by what the function does with the value. */
	private enum ChipStyle {
		READ, WRITE, PARAM, RETURN, UNKNOWN, HTTP
	}

	/** A variable / parameter chip drawn beside a node. */
	private static final class Chip {
		final Rectangle2D bounds;
		final String label;
		final String tooltip;
		final ChipStyle style;
		final String nodeId;

		Chip(Rectangle2D bounds, String label, String tooltip, ChipStyle style, String nodeId) {
			this.bounds = bounds;
			this.label = label;
			this.tooltip = tooltip;
			this.style = style;
			this.nodeId = nodeId;
		}
	}

	/** A pending chip (spec) before its rectangle is computed. */
	private static final class ChipSpec {
		final String key;
		final String text;
		final String tooltip;
		final ChipStyle style;

		ChipSpec(String key, String text, String tooltip, ChipStyle style) {
			this.key = key;
			this.text = text;
			this.tooltip = tooltip;
			this.style = style;
		}
	}

	public FlowDiagramPanel(FlowGraph graph, LayoutResult layout) {
		this.graph = graph;
		this.layout = layout;
		this.setBackground(JBColor.background());
		this.setOpaque(true);
		this.updatePreferredSize();

		MouseAdapter mouse = new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				if (FlowDiagramPanel.this.navigationListener == null) {
					return;
				}
				boolean preferYaml = e.getClickCount() >= 2;
				if (chipsVisible()) {
					Chip chip = chipAt(e.getPoint());
					if (chip != null) {
						FlowNode owner = FlowDiagramPanel.this.graph.getNode(chip.nodeId);
						if (owner != null) {
							FlowDiagramPanel.this.navigationListener.onNode(owner, preferYaml);
						}
						return;
					}
				}
				FlowNode node = nodeAt(e.getPoint());
				if (node != null) {
					FlowDiagramPanel.this.navigationListener.onNode(node, preferYaml);
					return;
				}
				if (FlowDiagramPanel.this.showControlFlow) {
					FlowLink link = linkAt(e.getPoint());
					if (link != null) {
						FlowDiagramPanel.this.navigationListener.onLink(link);
					}
				}
			}

			@Override
			public void mouseMoved(MouseEvent e) {
				Chip chip = chipsVisible() ? chipAt(e.getPoint()) : null;
				FlowNode node = chip == null ? nodeAt(e.getPoint()) : FlowDiagramPanel.this.graph.getNode(chip.nodeId);
				if (node != FlowDiagramPanel.this.hovered) {
					FlowDiagramPanel.this.hovered = node;
					repaint();
				}
				boolean overClickable = node != null || chip != null;
				setCursor(Cursor.getPredefinedCursor(overClickable ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
				if (chip != null) {
					setToolTipText(chip.tooltip);
				} else {
					setToolTipText(node != null ? tooltip(node) : null);
				}
			}
		};
		this.addMouseListener(mouse);
		this.addMouseMotionListener(mouse);

		this.addMouseWheelListener(e -> {
			if (e.isControlDown() || e.isMetaDown()) {
				double factor = e.getPreciseWheelRotation() < 0 ? 1.1 : 1 / 1.1;
				setScale(this.scale * factor);
			} else {
				this.getParent().dispatchEvent(e);
			}
		});
	}

	public void setNavigationListener(NavigationListener listener) {
		this.navigationListener = listener;
	}

	public void setScale(double scale) {
		this.scale = Math.max(0.3, Math.min(3.0, scale));
		this.updatePreferredSize();
		this.revalidate();
		this.repaint();
	}

	public double getScale() {
		return this.scale;
	}

	public void setShowControlFlow(boolean showControlFlow) {
		this.showControlFlow = showControlFlow;
		this.repaint();
	}

	public boolean isShowControlFlow() {
		return this.showControlFlow;
	}

	public void setShowDataFlow(boolean showDataFlow) {
		this.showDataFlow = showDataFlow;
		this.repaint();
	}

	public boolean isShowDataFlow() {
		return this.showDataFlow;
	}

	public void setShowHttpInputs(boolean showHttpInputs) {
		this.showHttpInputs = showHttpInputs;
		this.repaint();
	}

	public boolean isShowHttpInputs() {
		return this.showHttpInputs;
	}

	/** Chips (variables, parameter, HTTP inputs) are drawn when either mode is on. */
	private boolean chipsVisible() {
		return this.showDataFlow || this.showHttpInputs;
	}

	public void zoomToFit(Dimension viewport) {
		if (this.layout.getWidth() <= 0 || this.layout.getHeight() <= 0) {
			return;
		}
		double sx = viewport.getWidth() / this.layout.getWidth();
		double sy = viewport.getHeight() / this.layout.getHeight();
		this.setScale(Math.min(1.0, Math.min(sx, sy)));
	}

	private void updatePreferredSize() {
		int w = (int) Math.ceil(this.layout.getWidth() * this.scale);
		int h = (int) Math.ceil(this.layout.getHeight() * this.scale);
		this.setPreferredSize(new Dimension(Math.max(w, 1), Math.max(h, 1)));
	}

	private FlowNode nodeAt(Point devicePoint) {
		double mx = devicePoint.x / this.scale;
		double my = devicePoint.y / this.scale;
		for (FlowNode node : this.graph.getNodes()) {
			NodeBounds b = this.layout.boundsFor(node.getId());
			if (b != null && mx >= b.x && mx <= b.right() && my >= b.y && my <= b.bottom()) {
				return node;
			}
		}
		return null;
	}

	private FlowLink linkAt(Point devicePoint) {
		double mx = devicePoint.x / this.scale;
		double my = devicePoint.y / this.scale;
		double tolerance = 5.0 / this.scale;
		FlowLink best = null;
		double bestDist = tolerance;
		for (Map.Entry<FlowLink, Path2D> entry : this.linkPaths.entrySet()) {
			double d = distanceToPath(entry.getValue(), mx, my);
			if (d <= bestDist) {
				bestDist = d;
				best = entry.getKey();
			}
		}
		return best;
	}

	private static double distanceToPath(Path2D path, double px, double py) {
		PathIterator it = path.getPathIterator(null, 1.0); // flatten to line segments
		double[] coords = new double[6];
		double lastX = 0;
		double lastY = 0;
		double min = Double.MAX_VALUE;
		boolean have = false;
		while (!it.isDone()) {
			int type = it.currentSegment(coords);
			if (type == PathIterator.SEG_MOVETO) {
				lastX = coords[0];
				lastY = coords[1];
				have = true;
			} else if (type == PathIterator.SEG_LINETO && have) {
				min = Math.min(min, segmentDistance(px, py, lastX, lastY, coords[0], coords[1]));
				lastX = coords[0];
				lastY = coords[1];
			}
			it.next();
		}
		return min;
	}

	private Chip chipAt(Point devicePoint) {
		double mx = devicePoint.x / this.scale;
		double my = devicePoint.y / this.scale;
		for (Chip chip : this.chips) {
			if (chip.bounds.contains(mx, my)) {
				return chip;
			}
		}
		return null;
	}

	private static double segmentDistance(double px, double py, double x1, double y1, double x2, double y2) {
		double dx = x2 - x1;
		double dy = y2 - y1;
		double lenSq = dx * dx + dy * dy;
		double t = lenSq == 0 ? 0 : ((px - x1) * dx + (py - y1) * dy) / lenSq;
		t = Math.max(0, Math.min(1, t));
		double cx = x1 + t * dx;
		double cy = y1 + t * dy;
		return Math.hypot(px - cx, py - cy);
	}

	private String tooltip(FlowNode node) {
		StringBuilder sb = new StringBuilder("<html><b>").append(escape(node.getTitle())).append("</b>");
		if (!node.getSubtitle().isEmpty()) {
			sb.append("<br/>").append(escape(node.getSubtitle()));
		}
		if (node.hasJavaTarget()) {
			sb.append("<br/><i>").append(escape(node.getJavaClassName()));
			if (node.getJavaMethodName() != null) {
				sb.append('#').append(escape(node.getJavaMethodName()));
			}
			sb.append("</i>");
		}
		return sb.append("</html>").toString();
	}

	private static String escape(String s) {
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	@Override
	protected void paintComponent(Graphics g) {
		super.paintComponent(g);
		Graphics2D g2 = (Graphics2D) g.create();
		try {
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g2.scale(this.scale, this.scale);

			this.linkPaths.clear();
			this.chips.clear();

			// Chip overlay (behind nodes): compute chip geometry first so data links connect chip to chip.
			if (chipsVisible()) {
				List<DataFlowLink> parameterLinks = this.showDataFlow ? this.graph.getParameterFlowLinks()
						: java.util.Collections.emptyList();
				Map<String, Rectangle2D> inChips = new HashMap<>();
				Map<String, Rectangle2D> outChips = new HashMap<>();
				computeChips(g2, inChips, outChips, parameterLinks);
				if (this.showDataFlow) {
					for (DataFlowLink link : this.graph.getDataFlowLinks()) {
						paintDataLink(g2, link, inChips, outChips, DATA_LINK);
					}
					for (DataFlowLink link : parameterLinks) {
						paintDataLink(g2, link, inChips, outChips, PARAM_LINK);
					}
				}
			}

			// Control-flow links (behind nodes).
			if (this.showControlFlow) {
				for (FlowLink link : this.graph.getLinks()) {
					paintLink(g2, link);
				}
			}

			for (FlowNode node : this.graph.getNodes()) {
				paintNode(g2, node, node == this.hovered);
			}

			// Chips drawn on top of the nodes.
			if (chipsVisible()) {
				paintChips(g2);
			}
		} finally {
			g2.dispose();
		}
	}

	private void paintLink(Graphics2D g2, FlowLink link) {
		NodeBounds from = this.layout.boundsFor(link.getFromId());
		NodeBounds to = this.layout.boundsFor(link.getToId());
		if (from == null || to == null) {
			return;
		}

		boolean back = this.layout.isBackLink(link);
		Color color = linkColor(link.getKind());
		g2.setColor(color);

		Point2D start;
		Point2D end;
		Path2D path = new Path2D.Double();
		if (back) {
			// Route cycle edges down the right-hand side so they read as a loop-back.
			start = new Point2D.Double(from.right(), from.centerY());
			end = new Point2D.Double(to.right(), to.centerY());
			double bulge = Math.max(from.right(), to.right()) + 48;
			path.moveTo(start.getX(), start.getY());
			path.curveTo(bulge, start.getY(), bulge, end.getY(), end.getX(), end.getY());
			g2.setStroke(dashed(1.6f));
		} else {
			start = new Point2D.Double(from.centerX(), from.bottom());
			end = new Point2D.Double(to.centerX(), to.y);
			double midY = (start.getY() + end.getY()) / 2;
			CubicCurve2D curve = new CubicCurve2D.Double(start.getX(), start.getY(), start.getX(), midY, end.getX(),
					midY, end.getX(), end.getY());
			path.append(curve, false);
			g2.setStroke(link.getKind() == LinkKind.ESCALATION ? dashed(1.8f) : new BasicStroke(1.8f));
		}
		g2.draw(path);
		this.linkPaths.put(link, path);
		drawArrowHead(g2, end, back ? new Point2D.Double(end.getX() + 1, end.getY()) : new Point2D.Double(end.getX(),
				end.getY() - 1), color, back);

		if (!link.getLabel().isEmpty()) {
			double lx = back ? Math.max(from.right(), to.right()) + 52 : (start.getX() + end.getX()) / 2;
			double ly = (start.getY() + end.getY()) / 2;
			drawLabel(g2, link.getLabel(), lx, ly, color);
		}
	}

	private void drawArrowHead(Graphics2D g2, Point2D tip, Point2D from, Color color, boolean horizontal) {
		double angle = Math.atan2(tip.getY() - from.getY(), tip.getX() - from.getX());
		double len = 9;
		double spread = Math.toRadians(22);
		Path2D head = new Path2D.Double();
		head.moveTo(tip.getX(), tip.getY());
		head.lineTo(tip.getX() - len * Math.cos(angle - spread), tip.getY() - len * Math.sin(angle - spread));
		head.lineTo(tip.getX() - len * Math.cos(angle + spread), tip.getY() - len * Math.sin(angle + spread));
		head.closePath();
		g2.setColor(color);
		g2.setStroke(new BasicStroke(1f));
		g2.fill(head);
	}

	private void drawLabel(Graphics2D g2, String text, double cx, double cy, Color color) {
		Font font = this.getFont().deriveFont(Font.PLAIN, 10.5f);
		g2.setFont(font);
		int tw = g2.getFontMetrics().stringWidth(text);
		int th = g2.getFontMetrics().getHeight();
		int pad = 4;
		double x = cx - (tw / 2.0) - pad;
		double y = cy - (th / 2.0);
		RoundRectangle2D bg = new RoundRectangle2D.Double(x, y, tw + (pad * 2), th, 6, 6);
		g2.setColor(JBColor.background());
		g2.fill(bg);
		g2.setColor(color);
		g2.setStroke(new BasicStroke(1f));
		g2.draw(bg);
		g2.setColor(JBColor.foreground());
		g2.drawString(text, (int) (x + pad), (int) (y + g2.getFontMetrics().getAscent()));
	}

	// --- Data-flow mode -----------------------------------------------------

	private void computeChips(Graphics2D g2, Map<String, Rectangle2D> inChips, Map<String, Rectangle2D> outChips,
			List<DataFlowLink> parameterLinks) {
		g2.setFont(this.getFont().deriveFont(Font.PLAIN, 10f));
		FontMetrics fm = g2.getFontMetrics();
		double ch = 17;
		double gap = 3;

		// Nodes whose return is consumed as a downstream @Parameter get a "return" chip.
		java.util.Set<String> returnProducers = new java.util.HashSet<>();
		for (DataFlowLink link : parameterLinks) {
			returnProducers.add(link.getWriterId());
		}

		for (FlowNode node : this.graph.getNodes()) {
			NodeBounds b = this.layout.boundsFor(node.getId());
			if (b == null) {
				continue;
			}
			List<ChipSpec> left = new ArrayList<>();
			List<ChipSpec> right = new ArrayList<>();

			// Data flow: variables (reads / writes), the @Parameter and the return value.
			if (this.showDataFlow) {
				LinkedHashMap<String, VariableAccess> reads = new LinkedHashMap<>();
				LinkedHashMap<String, VariableAccess> writes = new LinkedHashMap<>();
				LinkedHashMap<String, VariableAccess> unknown = new LinkedHashMap<>();
				for (VariableAccess access : node.getVariables()) {
					if (access.getDirection().reads()) {
						reads.putIfAbsent(access.getVariableName(), access);
					}
					if (access.getDirection().writes()) {
						writes.putIfAbsent(access.getVariableName(), access);
					}
					if (!access.getDirection().reads() && !access.getDirection().writes()) {
						unknown.putIfAbsent(access.getVariableName(), access);
					}
				}

				// Left column: reads, then direction-unknown variables, then the @Parameter.
				for (VariableAccess access : reads.values()) {
					left.add(new ChipSpec(access.getVariableName(), "◂ " + access.displayLabel(), access.tooltip(),
							ChipStyle.READ));
				}
				for (VariableAccess access : unknown.values()) {
					left.add(new ChipSpec(access.getVariableName(), "◇ " + access.displayLabel(),
							access.tooltip() + " (direction unknown)", ChipStyle.UNKNOWN));
				}
				if (node.hasParameterType()) {
					String simple = VariableAccess.simpleType(node.getParameterType());
					left.add(new ChipSpec(FlowGraph.PARAMETER_VARIABLE, "◂ " + simple + " (param)",
							"@Parameter " + node.getParameterType(), ChipStyle.PARAM));
				}

				// Right column: writes, then the return value (when consumed downstream).
				for (VariableAccess access : writes.values()) {
					right.add(new ChipSpec(access.getVariableName(), access.displayLabel() + " ▸", access.tooltip(),
							ChipStyle.WRITE));
				}
				if (node.hasReturnType() && returnProducers.contains(node.getId())) {
					String simple = VariableAccess.simpleType(node.getReturnType());
					right.add(new ChipSpec(FlowGraph.PARAMETER_VARIABLE, "return " + simple + " ▸",
							"returns " + node.getReturnType(), ChipStyle.RETURN));
				}
			}

			layoutChipColumn(fm, b, true, left, ch, gap, inChips, node.getId());
			layoutChipColumn(fm, b, false, right, ch, gap, outChips, node.getId());

			// HTTP request inputs are external data flow, so they are drawn inside the
			// node body rather than in the inter-function input / output columns.
			if (this.showHttpInputs && node.hasHttpInputs()) {
				layoutHttpRow(fm, b, node.getHttpInputs(), node.getId());
			}
		}
	}

	/** Lays out the HTTP input chips as a compact row inside the node body. */
	private void layoutHttpRow(FontMetrics fm, NodeBounds b, List<HttpInput> inputs, String nodeId) {
		double ch = 15;
		double gap = 4;
		double x = b.x + 22; // after the left accent stripe, matching the title's left edge
		double y = b.y + 31; // lower band of the node, where the subtitle would otherwise sit
		double maxX = b.right() - 8;
		int placed = 0;
		for (int i = 0; i < inputs.size(); i++) {
			HttpInput input = inputs.get(i);
			String text = input.chipLabel();
			double w = fm.stringWidth(text) + 12;
			if (placed > 0 && (x + w) > maxX) {
				String more = "+" + (inputs.size() - i);
				double mw = fm.stringWidth(more) + 10;
				Rectangle2D rect = new Rectangle2D.Double(Math.min(x, maxX - mw), y, mw, ch);
				this.chips.add(new Chip(rect, more, overflowTooltip(inputs, i), ChipStyle.HTTP, nodeId));
				return;
			}
			if ((x + w) > maxX) {
				w = Math.max(24, maxX - x); // a single oversized chip is clipped to the node width
			}
			Rectangle2D rect = new Rectangle2D.Double(x, y, w, ch);
			this.chips.add(new Chip(rect, text, input.tooltip(), ChipStyle.HTTP, nodeId));
			x += w + gap;
			placed++;
		}
	}

	private static String overflowTooltip(List<HttpInput> inputs, int from) {
		StringBuilder sb = new StringBuilder("More HTTP inputs: ");
		for (int i = from; i < inputs.size(); i++) {
			if (i > from) {
				sb.append(", ");
			}
			sb.append(inputs.get(i).chipLabel());
		}
		return sb.toString();
	}

	private void layoutChipColumn(FontMetrics fm, NodeBounds b, boolean in, List<ChipSpec> specs, double ch, double gap,
			Map<String, Rectangle2D> target, String nodeId) {
		int n = specs.size();
		if (n == 0) {
			return;
		}
		double total = (n * ch) + ((n - 1) * gap);
		double y = b.centerY() - (total / 2);
		for (ChipSpec spec : specs) {
			double w = fm.stringWidth(spec.text) + 14;
			double x = in ? (b.x - 8 - w) : (b.right() + 8);
			Rectangle2D rect = new Rectangle2D.Double(x, y, w, ch);
			target.put(nodeId + "\u0000" + spec.key, rect);
			this.chips.add(new Chip(rect, spec.text, spec.tooltip, spec.style, nodeId));
			y += ch + gap;
		}
	}

	private void paintChips(Graphics2D g2) {
		g2.setFont(this.getFont().deriveFont(Font.PLAIN, 10f));
		FontMetrics fm = g2.getFontMetrics();
		for (Chip chip : this.chips) {
			Color accent = chipAccent(chip.style);
			RoundRectangle2D box = new RoundRectangle2D.Double(chip.bounds.getX(), chip.bounds.getY(),
					chip.bounds.getWidth(), chip.bounds.getHeight(), 9, 9);
			g2.setColor(chipFill(chip.style));
			g2.fill(box);
			g2.setColor(accent);
			g2.setStroke(new BasicStroke(1f));
			g2.draw(box);
			g2.setColor(JBColor.foreground());
			int ty = (int) (chip.bounds.getY() + ((chip.bounds.getHeight() - fm.getHeight()) / 2) + fm.getAscent());
			g2.drawString(chip.label, (int) (chip.bounds.getX() + 7), ty);
		}
	}

	private void paintDataLink(Graphics2D g2, DataFlowLink link, Map<String, Rectangle2D> inChips,
			Map<String, Rectangle2D> outChips, Color color) {
		Rectangle2D out = outChips.get(link.getWriterId() + "\u0000" + link.getVariableName());
		Rectangle2D in = inChips.get(link.getReaderId() + "\u0000" + link.getVariableName());
		if (out == null || in == null) {
			return;
		}
		Point2D start = new Point2D.Double(out.getMaxX(), out.getCenterY());
		Point2D end = new Point2D.Double(in.getX(), in.getCenterY());
		double bend = Math.max(40, Math.abs(end.getX() - start.getX()) * 0.4);
		double c1x = start.getX() + bend;
		double c2x = end.getX() - bend;
		CubicCurve2D curve = new CubicCurve2D.Double(start.getX(), start.getY(), c1x, start.getY(), c2x, end.getY(),
				end.getX(), end.getY());
		g2.setColor(color);
		g2.setStroke(new BasicStroke(1.6f));
		g2.draw(curve);
		drawArrowHead(g2, end, new Point2D.Double(c2x, end.getY()), color, false);
	}

	private void paintNode(Graphics2D g2, FlowNode node, boolean isHovered) {
		NodeBounds b = this.layout.boundsFor(node.getId());
		if (b == null) {
			return;
		}
		Color accent = nodeAccent(node.getKind());
		RoundRectangle2D box = new RoundRectangle2D.Double(b.x, b.y, b.width, b.height, 12, 12);

		g2.setColor(nodeFill(isHovered));
		g2.fill(box);

		g2.setStroke(new BasicStroke(isHovered ? 2.2f : 1.4f));
		g2.setColor(isHovered ? accent : nodeBorder());
		g2.draw(box);

		// Accent stripe down the left edge.
		Path2D stripe = new Path2D.Double();
		double r = 12;
		stripe.moveTo(b.x + r, b.y);
		stripe.lineTo(b.x + r, b.bottom());
		stripe.lineTo(b.x + r / 2, b.bottom());
		stripe.quadTo(b.x, b.bottom(), b.x, b.bottom() - r / 2);
		stripe.lineTo(b.x, b.y + r / 2);
		stripe.quadTo(b.x, b.y, b.x + r / 2, b.y);
		stripe.closePath();
		g2.setColor(accent);
		g2.fill(stripe);

		double textLeft = b.x + r + 10;
		double textWidth = b.width - (r + 10) - 10;

		g2.setColor(JBColor.foreground());
		g2.setFont(this.getFont().deriveFont(Font.BOLD, 12.5f));
		g2.drawString(ellipsize(g2, node.getTitle(), textWidth),
				(float) textLeft, (float) (b.y + 22));

		// The HTTP input row occupies the subtitle band inside the node, so the
		// subtitle yields to it when those chips are shown.
		boolean showingHttpRow = this.showHttpInputs && node.hasHttpInputs();
		if (!node.getSubtitle().isEmpty() && !showingHttpRow) {
			g2.setColor(JBColor.GRAY);
			g2.setFont(this.getFont().deriveFont(Font.PLAIN, 11f));
			g2.drawString(ellipsize(g2, node.getSubtitle(), textWidth),
					(float) textLeft, (float) (b.y + 40));
		}

		// Kind label, small, top-right.
		g2.setColor(accent);
		g2.setFont(this.getFont().deriveFont(Font.PLAIN, 9f));
		String kindLabel = kindLabel(node.getKind());
		int klw = g2.getFontMetrics().stringWidth(kindLabel);
		g2.drawString(kindLabel, (float) (b.right() - klw - 8), (float) (b.y + 14));
	}

	private String ellipsize(Graphics2D g2, String text, double maxWidth) {
		if (g2.getFontMetrics().stringWidth(text) <= maxWidth) {
			return text;
		}
		String ellipsis = "…";
		int len = text.length();
		while (len > 1 && g2.getFontMetrics().stringWidth(text.substring(0, len) + ellipsis) > maxWidth) {
			len--;
		}
		return text.substring(0, len) + ellipsis;
	}

	private static Stroke dashed(float width) {
		return new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 0, new float[] { 5f, 4f }, 0);
	}

	// --- Theme-aware colours -------------------------------------------------

	private static final Color VAR_IN = new JBColor(new Color(0x1565C0), new Color(0x64B5F6));
	private static final Color VAR_OUT = new JBColor(new Color(0x2E7D32), new Color(0x66BB6A));
	private static final Color VAR_UNKNOWN = new JBColor(new Color(0x616161), new Color(0x9E9E9E));
	private static final Color DATA_LINK = new JBColor(new Color(0xEF6C00), new Color(0xFFB74D));
	private static final Color PARAM_LINK = new JBColor(new Color(0x6A1B9A), new Color(0xBA68C8));
	private static final Color HTTP_INPUT = new JBColor(new Color(0x00838F), new Color(0x4DD0E1));

	private static Color chipAccent(ChipStyle style) {
		switch (style) {
		case READ:
			return VAR_IN;
		case WRITE:
			return VAR_OUT;
		case PARAM:
		case RETURN:
			return PARAM_LINK;
		case HTTP:
			return HTTP_INPUT;
		case UNKNOWN:
		default:
			return VAR_UNKNOWN;
		}
	}

	private static Color chipFill(ChipStyle style) {
		switch (style) {
		case READ:
			return new JBColor(new Color(0xE3F2FD), new Color(0x273540));
		case WRITE:
			return new JBColor(new Color(0xE8F5E9), new Color(0x273A2B));
		case PARAM:
		case RETURN:
			return new JBColor(new Color(0xF3E5F5), new Color(0x33293A));
		case HTTP:
			return new JBColor(new Color(0xE0F7FA), new Color(0x23383C));
		case UNKNOWN:
		default:
			return new JBColor(new Color(0xEEEEEE), new Color(0x2E2E2E));
		}
	}

	private static Color nodeFill(boolean hovered) {
		Color base = JBColor.namedColor("Tree.background", JBColor.background());
		return hovered ? new JBColor(new Color(0xEAF2FD), new Color(0x35414F)) : base;
	}

	private static Color nodeBorder() {
		return JBColor.namedColor("Component.borderColor", JBColor.border());
	}

	private static Color nodeAccent(NodeKind kind) {
		switch (kind) {
		case HTTP_ENDPOINT:
			return new JBColor(new Color(0x2E7D32), new Color(0x66BB6A));
		case PROCEDURE:
			return new JBColor(new Color(0x1565C0), new Color(0x64B5F6));
		case SECTION:
			return new JBColor(new Color(0x6A1B9A), new Color(0xBA68C8));
		case TEMPLATE:
			return new JBColor(new Color(0x00838F), new Color(0x4DD0E1));
		case RESOURCE:
			return new JBColor(new Color(0x546E7A), new Color(0x90A4AE));
		case SECURITY:
			return new JBColor(new Color(0xC62828), new Color(0xEF5350));
		case EXCEPTION_HANDLER:
			return new JBColor(new Color(0xD84315), new Color(0xFF8A65));
		case START:
			return new JBColor(new Color(0x00695C), new Color(0x4DB6AC));
		default:
			return JBColor.GRAY;
		}
	}

	private static Color linkColor(LinkKind kind) {
		switch (kind) {
		case ROUTE:
			return new JBColor(new Color(0x2E7D32), new Color(0x66BB6A));
		case ESCALATION:
			return new JBColor(new Color(0xD84315), new Color(0xFF8A65));
		case FLOW:
			return new JBColor(new Color(0x1565C0), new Color(0x64B5F6));
		case REDIRECT:
			return new JBColor(new Color(0x6A1B9A), new Color(0xBA68C8));
		case NEXT:
		default:
			return JBColor.namedColor("Label.foreground", JBColor.foreground());
		}
	}

	private static String kindLabel(NodeKind kind) {
		switch (kind) {
		case HTTP_ENDPOINT:
			return "HTTP";
		case PROCEDURE:
			return "function";
		case SECTION:
			return "section";
		case TEMPLATE:
			return "template";
		case RESOURCE:
			return "resource";
		case SECURITY:
			return "security";
		case EXCEPTION_HANDLER:
			return "escalation";
		case START:
			return "start";
		default:
			return "";
		}
	}

	/** Exposed for potential programmatic selection/testing. */
	public List<FlowNode> nodesInRenderOrder() {
		return new ArrayList<>(this.graph.getNodes());
	}
}
