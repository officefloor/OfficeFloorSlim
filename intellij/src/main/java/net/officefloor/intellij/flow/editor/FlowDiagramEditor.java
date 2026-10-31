package net.officefloor.intellij.flow.editor;

import java.awt.Dimension;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.util.Map;

import javax.swing.JComponent;
import javax.swing.JLayeredPane;
import javax.swing.SwingConstants;

import org.jetbrains.yaml.psi.YAMLFile;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorLocation;
import com.intellij.openapi.fileEditor.FileEditorState;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;

import net.officefloor.intellij.flow.layout.LayeredLayout;
import net.officefloor.intellij.flow.layout.LayoutResult;
import net.officefloor.intellij.flow.model.FlowGraph;
import net.officefloor.intellij.flow.model.FlowLink;
import net.officefloor.intellij.flow.model.FlowNode;
import net.officefloor.intellij.flow.model.HttpInput;
import net.officefloor.intellij.flow.model.VariableAccess;
import net.officefloor.intellij.flow.parser.ComposeYamlParser;
import net.officefloor.intellij.flow.parser.FunctionMetaData;
import net.officefloor.intellij.flow.parser.MetaDataEnricher;
import net.officefloor.intellij.flow.ui.FlowControlPanel;
import net.officefloor.intellij.flow.ui.FlowDiagramPanel;

/**
 * The "Flow" editor tab: renders an OfficeFloor compose/activity YAML file as an
 * auto-laid-out flow diagram, with click-to-navigate back to the YAML (single
 * click) or to the Java class/method (double click).
 *
 * <p>
 * The controls live in a collapsible panel floating over the top-right of the
 * diagram, so they do not consume a toolbar row above the graph.
 */
public class FlowDiagramEditor extends UserDataHolderBase implements FileEditor {

	private final Project project;
	private final VirtualFile file;
	private final JLayeredPane root = new JLayeredPane();
	private final JBScrollPane scrollPane = new JBScrollPane();
	private final PropertyChangeSupport changeSupport = new PropertyChangeSupport(this);
	private final FlowControlPanel controlPanel;

	private FlowDiagramPanel diagramPanel;
	private FlowGraph graph;

	/** Remembered across rebuilds so toggling survives a re-parse. */
	private boolean showControlFlow = true;
	private boolean showDataFlow;
	private boolean showHttpInputs;

	/** Data-flow metadata (variables/parameter/return) is loaded lazily, in the background. */
	private boolean enriched;
	private boolean enriching;

	public FlowDiagramEditor(Project project, VirtualFile file) {
		this.project = project;
		this.file = file;

		this.controlPanel = new FlowControlPanel(this.showControlFlow, this.showDataFlow, this.showHttpInputs,
				new FlowControlPanel.Listener() {
					@Override
					public void onControlFlow(boolean selected) {
						FlowDiagramEditor.this.showControlFlow = selected;
						if (FlowDiagramEditor.this.diagramPanel != null) {
							FlowDiagramEditor.this.diagramPanel.setShowControlFlow(selected);
						}
					}

					@Override
					public void onDataFlow(boolean selected) {
						FlowDiagramEditor.this.showDataFlow = selected;
						if (selected && !FlowDiagramEditor.this.enriched) {
							enrichInBackground();
						}
						if (FlowDiagramEditor.this.diagramPanel != null) {
							FlowDiagramEditor.this.diagramPanel.setShowDataFlow(selected);
						}
					}

					@Override
					public void onHttpInputs(boolean selected) {
						FlowDiagramEditor.this.showHttpInputs = selected;
						if (selected && !FlowDiagramEditor.this.enriched) {
							enrichInBackground();
						}
						if (FlowDiagramEditor.this.diagramPanel != null) {
							FlowDiagramEditor.this.diagramPanel.setShowHttpInputs(selected);
						}
					}

					@Override
					public void onZoomIn() {
						zoom(1.15);
					}

					@Override
					public void onZoomOut() {
						zoom(1 / 1.15);
					}

					@Override
					public void onFit() {
						fit();
					}
				}, this::relayout);

		this.root.add(this.scrollPane, JLayeredPane.DEFAULT_LAYER);
		this.root.add(this.controlPanel, JLayeredPane.PALETTE_LAYER);
		this.root.addComponentListener(new ComponentAdapter() {
			@Override
			public void componentResized(ComponentEvent e) {
				relayout();
			}
		});

		rebuild();
	}

	/** Keeps the scroll pane filling the view and the control panel pinned top-right. */
	private void relayout() {
		int w = this.root.getWidth();
		int h = this.root.getHeight();
		this.scrollPane.setBounds(0, 0, w, h);
		if (this.controlPanel.isVisible()) {
			Dimension pref = this.controlPanel.getPreferredSize();
			int margin = JBUI.scale(10);
			int x = Math.max(margin, w - pref.width - margin);
			this.controlPanel.setBounds(x, margin, pref.width, pref.height);
		}
		this.root.revalidate();
		this.root.repaint();
	}

	private void zoom(double factor) {
		if (this.diagramPanel != null) {
			this.diagramPanel.setScale(this.diagramPanel.getScale() * factor);
		}
	}

	private void fit() {
		if (this.diagramPanel != null) {
			this.diagramPanel.zoomToFit(this.scrollPane.getViewport().getExtentSize());
		}
	}

	private void rebuild() {
		FlowGraph parsed = ApplicationManager.getApplication().runReadAction((Computable<FlowGraph>) () -> {
			PsiFile psiFile = PsiManager.getInstance(this.project).findFile(this.file);
			if (psiFile instanceof YAMLFile) {
				return new ComposeYamlParser().parse((YAMLFile) psiFile, this.file);
			}
			return null;
		});
		this.graph = parsed;
		this.enriched = false;
		this.enriching = false;

		if (parsed == null || parsed.isEmpty()) {
			this.diagramPanel = null;
			this.controlPanel.setVisible(false);
			JBLabel empty = new JBLabel(
					"<html><center>This file is not an OfficeFloor flow configuration,<br/>"
							+ "or it declares no functions.</center></html>",
					SwingConstants.CENTER);
			empty.setHorizontalAlignment(SwingConstants.CENTER);
			this.scrollPane.setViewportView(empty);
			relayout();
			return;
		}

		LayoutResult layout = new LayeredLayout().layout(parsed);
		FlowDiagramPanel panel = new FlowDiagramPanel(parsed, layout);
		panel.setNavigationListener(new FlowDiagramPanel.NavigationListener() {
			@Override
			public void onNode(FlowNode node, boolean preferYaml) {
				// A single click goes to the implementing Java method; a double click
				// (preferYaml) goes to the YAML. Each falls back to the other when its
				// preferred target is unavailable (e.g. HTTP endpoints have no method).
				if (!preferYaml && node.hasJavaTarget() && navigateToJava(node)) {
					return;
				}
				if (node.getSourceOffset() >= 0) {
					navigateToYaml(node.getSourceOffset());
				} else if (node.hasJavaTarget()) {
					navigateToJava(node);
				}
			}

			@Override
			public void onLink(FlowLink link) {
				if (link.getSourceOffset() >= 0) {
					navigateToYaml(link.getSourceOffset());
				}
			}
		});
		// Data-flow / HTTP-input metadata is resolved lazily; enable whenever there are functions.
		this.controlPanel.setVisible(true);
		this.controlPanel.setMetadataEnabled(true);
		panel.setShowControlFlow(this.showControlFlow);
		panel.setShowDataFlow(this.showDataFlow);
		panel.setShowHttpInputs(this.showHttpInputs);

		this.diagramPanel = panel;
		this.scrollPane.setViewportView(panel);
		relayout();

		if (this.showDataFlow || this.showHttpInputs) {
			enrichInBackground();
		}
	}

	/**
	 * Resolves the data-flow metadata (ProcedureType first, Java source fallback)
	 * off the EDT, then applies it and repaints. Runs at most once per rebuild.
	 */
	private void enrichInBackground() {
		if (this.enriched || this.enriching || this.graph == null) {
			return;
		}
		this.enriching = true;
		final FlowGraph target = this.graph;
		ApplicationManager.getApplication().executeOnPooledThread(() -> {
			Map<String, FunctionMetaData> metaData;
			try {
				metaData = MetaDataEnricher.enrich(target, this.project, this.file);
			} catch (Throwable ex) {
				metaData = java.util.Collections.emptyMap();
			}
			final Map<String, FunctionMetaData> resolved = metaData;
			ApplicationManager.getApplication().invokeLater(() -> {
				if (this.graph != target) {
					return; // a newer rebuild superseded this run
				}
				for (Map.Entry<String, FunctionMetaData> entry : resolved.entrySet()) {
					FlowNode node = target.getNode(entry.getKey());
					if (node == null) {
						continue;
					}
					FunctionMetaData md = entry.getValue();
					node.clearVariables();
					for (VariableAccess access : md.getVariables()) {
						node.addVariable(access);
					}
					node.clearHttpInputs();
					for (HttpInput httpInput : md.getHttpInputs()) {
						node.addHttpInput(httpInput);
					}
					node.setReturnType(md.getReturnType());
					node.setParameterType(md.getParameterType());
				}
				this.enriched = true;
				this.enriching = false;
				if (this.diagramPanel != null) {
					this.diagramPanel.repaint();
				}
			});
		});
	}

	private void navigateToYaml(int offset) {
		new OpenFileDescriptor(this.project, this.file, offset).navigate(true);
	}

	private boolean navigateToJava(FlowNode node) {
		try {
			return JavaTargetNavigator.navigate(this.project, node.getJavaClassName(), node.getJavaMethodName());
		} catch (Throwable ignored) {
			// Java module not present; caller falls back to YAML navigation.
			return false;
		}
	}

	@Override
	public JComponent getComponent() {
		return this.root;
	}

	@Override
	public JComponent getPreferredFocusedComponent() {
		return this.diagramPanel != null ? this.diagramPanel : this.root;
	}

	@Override
	public String getName() {
		return "Flow";
	}

	@Override
	public void selectNotify() {
		// Re-parse so the diagram reflects edits made in the Text tab.
		rebuild();
	}

	@Override
	public void setState(FileEditorState state) {
		// Stateless view; nothing to restore.
	}

	@Override
	public boolean isModified() {
		return false;
	}

	@Override
	public boolean isValid() {
		return this.file.isValid();
	}

	@Override
	public VirtualFile getFile() {
		return this.file;
	}

	@Override
	public void addPropertyChangeListener(PropertyChangeListener listener) {
		this.changeSupport.addPropertyChangeListener(listener);
	}

	@Override
	public void removePropertyChangeListener(PropertyChangeListener listener) {
		this.changeSupport.removePropertyChangeListener(listener);
	}

	@Override
	public FileEditorLocation getCurrentLocation() {
		return null;
	}

	@Override
	public void dispose() {
		// Nothing retained that needs explicit disposal.
	}
}
