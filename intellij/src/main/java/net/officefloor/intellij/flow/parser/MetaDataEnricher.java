package net.officefloor.intellij.flow.parser;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.OrderEnumerator;
import com.intellij.openapi.vfs.VirtualFile;

import net.officefloor.intellij.flow.model.FlowGraph;
import net.officefloor.intellij.flow.model.FlowNode;
import net.officefloor.intellij.flow.model.NodeKind;

/**
 * Resolves per-function metadata (variables, {@code @Parameter}, return type)
 * for a {@link FlowGraph}, preferring OfficeFloor's compiled {@code ProcedureType}
 * and falling back to the Java source.
 *
 * <p>
 * Loading a ProcedureType runs OfficeFloor's compiler against a classloader over
 * the module's compiled output and dependencies, so this is intended to run off
 * the EDT. It takes short read actions for PSI / project-model access and runs
 * the compiler outside any read action.
 */
public final class MetaDataEnricher {

	/** The OfficeFloor ProcedureSource name for a plain Java class. */
	private static final String CLASS_SOURCE = "Class";

	private MetaDataEnricher() {
	}

	private static final class Target {
		final String nodeId;
		final String className;
		final String methodName;
		FunctionMetaData source = FunctionMetaData.empty();

		Target(String nodeId, String className, String methodName) {
			this.nodeId = nodeId;
			this.className = className;
			this.methodName = methodName;
		}
	}

	/**
	 * @return merged metadata keyed by node id (only for nodes that resolved to a
	 *         Java procedure class).
	 */
	public static Map<String, FunctionMetaData> enrich(FlowGraph graph, Project project, VirtualFile file) {
		// 1. Under a read action: collect targets + their Java-source metadata + the classpath.
		List<Target> targets = new ArrayList<>();
		List<String> classpath = new ArrayList<>();
		ApplicationManager.getApplication().runReadAction((Runnable) () -> {
			for (FlowNode node : graph.getNodes()) {
				if (node.getKind() == NodeKind.PROCEDURE && node.getJavaClassName() != null) {
					Target target = new Target(node.getId(), node.getJavaClassName(), node.getJavaMethodName());
					try {
						target.source = JavaFunctionVariables.extract(project, target.className, target.methodName);
					} catch (Throwable ignored) {
						// Java module absent; source stays empty.
					}
					targets.add(target);
				}
			}
			classpath.addAll(classpathEntries(project, file));
		});

		Map<String, FunctionMetaData> result = new HashMap<>();
		if (targets.isEmpty()) {
			return result;
		}

		// 2. Build a classloader over the project and a single reusable ProcedureLoader.
		URLClassLoader classLoader = buildClassLoader(classpath);
		try {
			ProcedureTypeMetaData.Loader loader = null;
			if (classLoader != null) {
				try {
					loader = ProcedureTypeMetaData.newLoader(classLoader);
				} catch (Throwable ignored) {
					// OfficeFloor not on the project classpath: source-only metadata.
				}
			}
			for (Target target : targets) {
				FunctionMetaData procedureType = null;
				if (loader != null) {
					try {
						procedureType = ProcedureTypeMetaData.load(loader, target.className, CLASS_SOURCE,
								target.methodName);
					} catch (Throwable ignored) {
						// ProcedureType unavailable for this function: fall back to source.
					}
				}
				result.put(target.nodeId, FunctionMetaDataResolver.merge(procedureType, target.source));
			}
		} finally {
			if (classLoader != null) {
				try {
					classLoader.close();
				} catch (Exception ignored) {
					// best effort
				}
			}
		}
		return result;
	}

	private static List<String> classpathEntries(Project project, VirtualFile file) {
		List<String> paths = new ArrayList<>();
		if (file == null) {
			return paths;
		}
		Module module = ModuleUtilCore.findModuleForFile(file, project);
		if (module == null) {
			return paths;
		}
		paths.addAll(OrderEnumerator.orderEntries(module).recursively().withoutSdk().getPathsList().getPathList());
		return paths;
	}

	private static URLClassLoader buildClassLoader(List<String> classpath) {
		if (classpath.isEmpty()) {
			return null;
		}
		List<URL> urls = new ArrayList<>();
		for (String path : classpath) {
			try {
				// Trim the Jar-URL "!/" suffix IntelliJ uses for roots inside archives.
				String fsPath = path;
				int bang = fsPath.indexOf("!/");
				if (bang >= 0) {
					fsPath = fsPath.substring(0, bang);
				}
				urls.add(new File(fsPath).toURI().toURL());
			} catch (Exception ignored) {
				// skip unusable entry
			}
		}
		if (urls.isEmpty()) {
			return null;
		}
		return new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader());
	}
}
