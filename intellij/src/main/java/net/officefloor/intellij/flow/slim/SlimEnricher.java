package net.officefloor.intellij.flow.slim;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

import net.officefloor.officefloorslim.core.MethodSplit;
import net.officefloor.officefloorslim.graph.FlowGraph;
import net.officefloor.officefloorslim.graph.FlowNode;
import net.officefloor.officefloorslim.graph.NodeKind;

/**
 * Prices each function of a flow graph as {@code officefloorslim scan} would: finds its method's
 * source under a short read action, then parses and prices it outside one. Intended to run off
 * the EDT.
 */
public final class SlimEnricher {

	/**
	 * A function's method, priced.
	 *
	 * @param file  its source file, for navigation
	 * @param split its pricing
	 */
	public record NodeSlim(VirtualFile file, MethodSplit split) {
	}

	private record Target(String nodeId, JavaMethodSource.Located located) {
	}

	private SlimEnricher() {
	}

	/** By node id, for the functions whose method has source in the project. */
	public static Map<String, NodeSlim> enrich(FlowGraph graph, Project project) {
		List<Target> targets = new ArrayList<>();
		ApplicationManager.getApplication().runReadAction((Runnable) () -> {
			for (FlowNode node : graph.getNodes()) {
				if (node.getKind() != NodeKind.PROCEDURE || node.getJavaClassName() == null) {
					continue;
				}
				try {
					JavaMethodSource.Located located = JavaMethodSource.locate(project, node.getJavaClassName(),
							node.getJavaMethodName());
					if (located != null) {
						targets.add(new Target(node.getId(), located));
					}
				} catch (Throwable ignored) {
					// Java module absent: nothing is marked
				}
			}
		});
		Map<String, NodeSlim> out = new HashMap<>();
		Map<String, List<MethodSplit>> byFile = new HashMap<>();
		for (Target t : targets) {
			JavaMethodSource.Located l = t.located();
			try {
				List<MethodSplit> methods = byFile.computeIfAbsent(l.file().getPath(),
						path -> MethodSplit.of(path, l.text()));
				for (MethodSplit m : methods) {
					if (m.nameLine() == l.nameLine()) {
						out.put(t.nodeId(), new NodeSlim(l.file(), m));
					}
				}
			} catch (Throwable ignored) {
				// a file the parser cannot read is left unmarked, as the scan lists it unparsed
			}
		}
		return out;
	}
}
