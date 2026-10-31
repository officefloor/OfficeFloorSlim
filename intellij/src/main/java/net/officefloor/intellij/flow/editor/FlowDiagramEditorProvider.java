package net.officefloor.intellij.flow.editor;

import org.jetbrains.annotations.NotNull;

import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorPolicy;
import com.intellij.openapi.fileEditor.FileEditorProvider;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

import net.officefloor.intellij.flow.parser.ComposeContentHeuristic;

/**
 * Adds a "Flow" editor tab, after the normal text editor, for YAML files that
 * look like OfficeFloor compose/activity configuration.
 */
public class FlowDiagramEditorProvider implements FileEditorProvider, DumbAware {

	private static final String TYPE_ID = "officefloor-flow-view";

	@Override
	public boolean accept(@NotNull Project project, @NotNull VirtualFile file) {
		return ComposeContentHeuristic.accepts(file);
	}

	@Override
	public @NotNull FileEditor createEditor(@NotNull Project project, @NotNull VirtualFile file) {
		return new FlowDiagramEditor(project, file);
	}

	@Override
	public @NotNull String getEditorTypeId() {
		return TYPE_ID;
	}

	@Override
	public @NotNull FileEditorPolicy getPolicy() {
		// Keep the text editor as the default tab; show "Flow" alongside it.
		return FileEditorPolicy.PLACE_AFTER_DEFAULT_EDITOR;
	}
}
