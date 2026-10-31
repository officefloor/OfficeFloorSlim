package net.officefloor.intellij.flow.ui;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JPanel;

import com.intellij.ui.ColorUtil;
import com.intellij.ui.JBColor;
import com.intellij.ui.scale.JBUIScale;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.NamedColorUtil;
import com.intellij.util.ui.UIUtil;

/**
 * Floating, collapsible control panel for the flow diagram. It sits over the
 * top-right of the diagram rather than consuming a toolbar row, lists its
 * controls downwards, and can be collapsed to a compact header.
 *
 * <p>
 * The two display modes are check boxes (clearly on or off); the zoom and fit
 * actions are rounded, accent-coloured icon buttons.
 */
public final class FlowControlPanel extends JPanel {

	/** Callbacks for the editor to react to the controls. */
	public interface Listener {
		void onControlFlow(boolean selected);

		void onDataFlow(boolean selected);

		void onHttpInputs(boolean selected);

		void onZoomIn();

		void onZoomOut();

		void onFit();
	}

	private static final int PANEL_ARC = 16;
	private static final JBColor ACCENT = new JBColor(new Color(0x3574F0), new Color(0x3C74E8));

	private final JBCheckBox controlFlow = new JBCheckBox("Control flow");
	private final JBCheckBox dataFlow = new JBCheckBox("Data flow");
	private final JBCheckBox httpInputs = new JBCheckBox("HTTP inputs");
	private final JPanel body = new JPanel();
	private final CaretButton caret = new CaretButton();
	private final Runnable onLayoutChanged;

	private boolean collapsed;

	public FlowControlPanel(boolean controlSelected, boolean dataSelected, boolean httpSelected, Listener listener,
			Runnable onLayoutChanged) {
		super(new BorderLayout());
		this.onLayoutChanged = onLayoutChanged;
		setOpaque(false);
		setBorder(JBUI.Borders.empty(8, 12, 12, 12));

		// Header: title on the left, collapse caret on the right.
		JPanel header = new JPanel(new BorderLayout());
		header.setOpaque(false);
		JBLabel title = new JBLabel("Flow");
		title.setFont(title.getFont().deriveFont(Font.BOLD));
		header.add(title, BorderLayout.WEST);
		this.caret.addActionListener(e -> toggleCollapsed());
		JPanel caretHolder = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
		caretHolder.setOpaque(false);
		caretHolder.add(this.caret);
		header.add(caretHolder, BorderLayout.EAST);
		add(header, BorderLayout.NORTH);

		// Body: the display check boxes, then the action buttons, listed downwards.
		this.body.setOpaque(false);
		this.body.setLayout(new BoxLayout(this.body, BoxLayout.Y_AXIS));
		this.body.setBorder(JBUI.Borders.emptyTop(8));

		this.controlFlow.setOpaque(false);
		this.dataFlow.setOpaque(false);
		this.httpInputs.setOpaque(false);
		this.controlFlow.setSelected(controlSelected);
		this.dataFlow.setSelected(dataSelected);
		this.httpInputs.setSelected(httpSelected);
		this.controlFlow.setToolTipText("Show the control flow: next / flow / escalation connections");
		this.dataFlow.setToolTipText(
				"Show the data flow: @Val/In/Out variables and @Parameter values passed between functions");
		this.httpInputs.setToolTipText(
				"Show the HTTP request inputs each function reads (path, query, header, cookie, body)");
		this.controlFlow.setAlignmentX(Component.LEFT_ALIGNMENT);
		this.dataFlow.setAlignmentX(Component.LEFT_ALIGNMENT);
		this.httpInputs.setAlignmentX(Component.LEFT_ALIGNMENT);
		this.controlFlow.addActionListener(e -> listener.onControlFlow(this.controlFlow.isSelected()));
		this.dataFlow.addActionListener(e -> listener.onDataFlow(this.dataFlow.isSelected()));
		this.httpInputs.addActionListener(e -> listener.onHttpInputs(this.httpInputs.isSelected()));
		this.body.add(this.controlFlow);
		this.body.add(this.dataFlow);
		this.body.add(this.httpInputs);

		this.body.add(Box.createVerticalStrut(JBUI.scale(10)));

		JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0));
		actions.setOpaque(false);
		actions.setAlignmentX(Component.LEFT_ALIGNMENT);
		actions.add(new RoundedButton(RoundedButton.Glyph.ZOOM_OUT, "Zoom out", listener::onZoomOut));
		actions.add(new RoundedButton(RoundedButton.Glyph.ZOOM_IN, "Zoom in", listener::onZoomIn));
		actions.add(new RoundedButton(RoundedButton.Glyph.FIT, "Zoom to fit", listener::onFit));
		this.body.add(actions);

		add(this.body, BorderLayout.CENTER);
	}

	/** Enables or disables the data-flow and HTTP-input check boxes (disabled when nothing to show). */
	public void setMetadataEnabled(boolean enabled) {
		this.dataFlow.setEnabled(enabled);
		this.httpInputs.setEnabled(enabled);
	}

	private void toggleCollapsed() {
		this.collapsed = !this.collapsed;
		this.body.setVisible(!this.collapsed);
		this.caret.setCollapsed(this.collapsed);
		revalidate();
		if (this.onLayoutChanged != null) {
			this.onLayoutChanged.run();
		}
	}

	@Override
	protected void paintComponent(Graphics g) {
		Graphics2D g2 = (Graphics2D) g.create();
		try {
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			int w = getWidth();
			int h = getHeight();
			g2.setColor(ColorUtil.withAlpha(UIUtil.getPanelBackground(), 0.94));
			g2.fillRoundRect(0, 0, w - 1, h - 1, PANEL_ARC, PANEL_ARC);
			g2.setColor(JBColor.border());
			g2.drawRoundRect(0, 0, w - 1, h - 1, PANEL_ARC, PANEL_ARC);
		} finally {
			g2.dispose();
		}
		super.paintComponent(g);
	}

	/** A rounded, accent-coloured icon button with a drawn (not font-dependent) glyph. */
	private static final class RoundedButton extends JButton {

		enum Glyph {
			ZOOM_IN, ZOOM_OUT, FIT
		}

		private final Glyph glyph;

		RoundedButton(Glyph glyph, String tooltip, Runnable action) {
			this.glyph = glyph;
			setToolTipText(tooltip);
			setFocusable(false);
			setContentAreaFilled(false);
			setBorderPainted(false);
			setOpaque(false);
			setRolloverEnabled(true);
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			setPreferredSize(new Dimension(JBUI.scale(32), JBUI.scale(28)));
			addActionListener(e -> action.run());
		}

		@Override
		protected void paintComponent(Graphics g) {
			Graphics2D g2 = (Graphics2D) g.create();
			try {
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				int w = getWidth();
				int h = getHeight();
				Color fill = ACCENT;
				if (getModel().isPressed()) {
					fill = ColorUtil.darker(ACCENT, 2);
				} else if (getModel().isRollover()) {
					fill = ColorUtil.brighter(ACCENT, 1);
				}
				int arc = JBUI.scale(10);
				g2.setColor(fill);
				g2.fillRoundRect(0, 0, w - 1, h - 1, arc, arc);

				g2.setColor(Color.WHITE);
				g2.setStroke(new BasicStroke(JBUIScale.scale(1.6f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				drawGlyph(g2, w, h);
			} finally {
				g2.dispose();
			}
		}

		private void drawGlyph(Graphics2D g2, int w, int h) {
			int cx = w / 2;
			int cy = h / 2;
			int r = JBUI.scale(5);
			switch (this.glyph) {
			case ZOOM_OUT:
				g2.drawLine(cx - r, cy, cx + r, cy);
				break;
			case ZOOM_IN:
				g2.drawLine(cx - r, cy, cx + r, cy);
				g2.drawLine(cx, cy - r, cx, cy + r);
				break;
			case FIT:
				int b = JBUI.scale(3);
				int x0 = cx - r;
				int y0 = cy - r;
				int x1 = cx + r;
				int y1 = cy + r;
				g2.drawLine(x0, y0, x0 + b, y0);
				g2.drawLine(x0, y0, x0, y0 + b);
				g2.drawLine(x1, y0, x1 - b, y0);
				g2.drawLine(x1, y0, x1, y0 + b);
				g2.drawLine(x0, y1, x0 + b, y1);
				g2.drawLine(x0, y1, x0, y1 - b);
				g2.drawLine(x1, y1, x1 - b, y1);
				g2.drawLine(x1, y1, x1, y1 - b);
				break;
			}
		}
	}

	/** A small flat button drawing a chevron that flips with the collapsed state. */
	private static final class CaretButton extends JButton {

		private boolean pointingToCollapsed;

		CaretButton() {
			setFocusable(false);
			setContentAreaFilled(false);
			setBorderPainted(false);
			setOpaque(false);
			setBorder(BorderFactory.createEmptyBorder());
			setToolTipText("Collapse");
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			setPreferredSize(new Dimension(JBUI.scale(18), JBUI.scale(18)));
		}

		void setCollapsed(boolean collapsed) {
			this.pointingToCollapsed = collapsed;
			setToolTipText(collapsed ? "Expand" : "Collapse");
			repaint();
		}

		@Override
		protected void paintComponent(Graphics g) {
			Graphics2D g2 = (Graphics2D) g.create();
			try {
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g2.setColor(getModel().isRollover() ? UIUtil.getLabelForeground() : NamedColorUtil.getInactiveTextColor());
				g2.setStroke(new BasicStroke(JBUIScale.scale(1.6f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				int cx = getWidth() / 2;
				int cy = getHeight() / 2;
				int s = JBUI.scale(4);
				if (this.pointingToCollapsed) {
					// Right-pointing chevron: expand.
					g2.drawLine(cx - s / 2, cy - s, cx + s / 2, cy);
					g2.drawLine(cx + s / 2, cy, cx - s / 2, cy + s);
				} else {
					// Down-pointing chevron: collapse.
					g2.drawLine(cx - s, cy - s / 2, cx, cy + s / 2);
					g2.drawLine(cx, cy + s / 2, cx + s, cy - s / 2);
				}
			} finally {
				g2.dispose();
			}
		}
	}
}
