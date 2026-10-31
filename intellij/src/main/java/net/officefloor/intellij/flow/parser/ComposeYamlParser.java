package net.officefloor.intellij.flow.parser;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.yaml.psi.YAMLDocument;
import org.jetbrains.yaml.psi.YAMLFile;
import org.jetbrains.yaml.psi.YAMLKeyValue;
import org.jetbrains.yaml.psi.YAMLMapping;
import org.jetbrains.yaml.psi.YAMLScalar;
import org.jetbrains.yaml.psi.YAMLSequence;
import org.jetbrains.yaml.psi.YAMLSequenceItem;
import org.jetbrains.yaml.psi.YAMLValue;

import com.intellij.openapi.vfs.VirtualFile;

import net.officefloor.intellij.flow.model.FlowGraph;
import net.officefloor.intellij.flow.model.FlowLink;
import net.officefloor.intellij.flow.model.FlowNode;
import net.officefloor.intellij.flow.model.LinkKind;
import net.officefloor.intellij.flow.model.NodeKind;

/**
 * Parses an OfficeFloor "compose" / Activity YAML document (as used for REST
 * endpoints and activity flows) into a {@link FlowGraph}.
 *
 * <p>
 * Uses IntelliJ's YAML PSI so that each node and link carries the source offset
 * of its declaration, enabling click-to-navigate back into the editor.
 */
public final class ComposeYamlParser {

	/** Reserved top-level key; everything else is a function. */
	private static final String COMPOSITION = "composition";

	private static final String EXTERNAL_PREFIX = "#";

	/**
	 * @return a parsed graph, or {@code null} if the document does not look like an
	 *         OfficeFloor compose/activity configuration.
	 */
	public FlowGraph parse(YAMLFile file, VirtualFile virtualFile) {
		YAMLMapping root = topMapping(file);
		if (root == null) {
			return null;
		}

		// Collect function key-values (everything except "composition").
		List<YAMLKeyValue> functionKvs = new ArrayList<>();
		YAMLKeyValue compositionKv = null;
		for (YAMLKeyValue kv : root.getKeyValues()) {
			String key = kv.getKeyText();
			if (COMPOSITION.equals(key)) {
				compositionKv = kv;
			} else if (key != null && !key.isEmpty() && kv.getValue() instanceof YAMLMapping) {
				functionKvs.add(kv);
			}
		}

		if (functionKvs.isEmpty()) {
			return null; // not a compose document
		}
		if (!looksLikeCompose(functionKvs, compositionKv)) {
			return null;
		}

		FlowGraph graph = new FlowGraph();

		// Pass 1: declare a node per function. Variable / parameter / return metadata
		// is resolved lazily by MetaDataEnricher when the data-flow view is shown.
		String startFunction = functionKvs.get(0).getKeyText();
		for (YAMLKeyValue kv : functionKvs) {
			graph.addNode(buildFunctionNode(kv));
		}

		// Optional HTTP endpoint derived from the file name / location.
		if (virtualFile != null) {
			RestFileName rest = RestFileName.parse(virtualFile);
			if (rest != null && rest.isEndpoint()) {
				String endpointId = "endpoint::" + rest.endpointLabel();
				graph.addNode(new FlowNode(endpointId, NodeKind.HTTP_ENDPOINT, rest.endpointLabel(),
						"REST endpoint", -1, null, null));
				graph.addLink(new FlowLink(endpointId, startFunction, LinkKind.ROUTE, "", -1));
			}
		}

		// Pass 2: links between functions.
		for (YAMLKeyValue kv : functionKvs) {
			String from = kv.getKeyText();
			YAMLMapping body = (YAMLMapping) kv.getValue();

			YAMLKeyValue nextKv = body.getKeyValueByKey("next");
			if (nextKv != null) {
				String target = scalarText(nextKv.getValue());
				if (target != null && !target.isEmpty()) {
					addLink(graph, from, target, LinkKind.NEXT, "next", offsetOf(nextKv));
				}
			}

			addMapLinks(graph, from, body.getKeyValueByKey("outputs"), LinkKind.FLOW, false);
			addMapLinks(graph, from, body.getKeyValueByKey("escalations"), LinkKind.ESCALATION, true);
		}

		// Section-wide escalations default from composition: linked off the entry function.
		if (compositionKv != null && compositionKv.getValue() instanceof YAMLMapping) {
			YAMLMapping composition = (YAMLMapping) compositionKv.getValue();
			YAMLKeyValue escKv = composition.getKeyValueByKey("escalations");
			if (escKv != null && escKv.getValue() instanceof YAMLMapping) {
				for (YAMLKeyValue entry : ((YAMLMapping) escKv.getValue()).getKeyValues()) {
					String target = scalarText(entry.getValue());
					if (target != null && !target.isEmpty()) {
						addLink(graph, startFunction, target, LinkKind.ESCALATION,
								shortException(entry.getKeyText()) + " (section)", offsetOf(entry));
					}
				}
			}
		}

		return graph;
	}

	private void addMapLinks(FlowGraph graph, String from, YAMLKeyValue mapKv, LinkKind kind, boolean exception) {
		if (mapKv == null || !(mapKv.getValue() instanceof YAMLMapping)) {
			return;
		}
		for (YAMLKeyValue entry : ((YAMLMapping) mapKv.getValue()).getKeyValues()) {
			String target = scalarText(entry.getValue());
			if (target == null || target.isEmpty()) {
				continue;
			}
			String label = exception ? shortException(entry.getKeyText()) : entry.getKeyText();
			addLink(graph, from, target, kind, label, offsetOf(entry));
		}
	}

	/** Adds a link, materialising an external ({@code #name}) or unresolved target node as needed. */
	private void addLink(FlowGraph graph, String from, String rawTarget, LinkKind kind, String label, int offset) {
		String targetId = rawTarget;
		if (rawTarget.startsWith(EXTERNAL_PREFIX)) {
			if (!graph.hasNode(targetId)) {
				graph.addNode(new FlowNode(targetId, NodeKind.SECTION, rawTarget, "external input", -1, null, null));
			}
		} else if (!graph.hasNode(targetId)) {
			// Referenced but not declared in this file.
			graph.addNode(new FlowNode(targetId, NodeKind.UNKNOWN, rawTarget, "unresolved", -1, null, null));
		}
		graph.addLink(new FlowLink(from, targetId, kind, label, offset));
	}

	private FlowNode buildFunctionNode(YAMLKeyValue kv) {
		String name = kv.getKeyText();
		YAMLMapping body = (YAMLMapping) kv.getValue();

		String className = text(body, "class");
		String method = text(body, "method");
		String source = text(body, "source");
		String location = text(body, "location");
		String procedure = text(body, "procedure");
		String resource = text(body, "resource");

		NodeKind kind;
		String subtitle;
		String javaClass = null;
		String javaMethod = null;

		if (source != null) {
			kind = NodeKind.SECTION;
			subtitle = simpleName(source) + (location != null ? " @ " + location : "");
			javaClass = resolvable(source) ? source : null;
		} else if (className != null) {
			kind = NodeKind.PROCEDURE;
			subtitle = simpleName(className) + (method != null ? "#" + method : "");
			javaClass = resolvable(className) ? className : null;
			javaMethod = method;
		} else if (procedure != null) {
			kind = NodeKind.PROCEDURE;
			subtitle = "procedure: " + procedure + (resource != null ? " (" + resource + ")" : "");
		} else {
			kind = NodeKind.PROCEDURE;
			subtitle = "";
		}

		return new FlowNode(name, kind, name, subtitle, offsetOf(kv), javaClass, javaMethod);
	}

	// --- Heuristics ---------------------------------------------------------

	/** Confirms at least one function carries a compose-specific key. */
	private boolean looksLikeCompose(List<YAMLKeyValue> functionKvs, YAMLKeyValue compositionKv) {
		if (compositionKv != null) {
			return true;
		}
		for (YAMLKeyValue kv : functionKvs) {
			YAMLMapping body = (YAMLMapping) kv.getValue();
			if (hasAnyKey(body, "class", "source", "procedure", "next", "outputs", "escalations", "method", "govern",
					"resource", "location", "input")) {
				return true;
			}
		}
		return false;
	}

	private boolean hasAnyKey(YAMLMapping mapping, String... keys) {
		for (String key : keys) {
			if (mapping.getKeyValueByKey(key) != null) {
				return true;
			}
		}
		return false;
	}

	// --- PSI helpers --------------------------------------------------------

	private YAMLMapping topMapping(YAMLFile file) {
		if (file == null) {
			return null;
		}
		for (YAMLDocument document : file.getDocuments()) {
			YAMLValue top = document.getTopLevelValue();
			if (top instanceof YAMLMapping) {
				return (YAMLMapping) top;
			}
		}
		return null;
	}

	private String text(YAMLMapping mapping, String key) {
		YAMLKeyValue kv = mapping.getKeyValueByKey(key);
		return kv == null ? null : scalarText(kv.getValue());
	}

	private String scalarText(YAMLValue value) {
		if (value instanceof YAMLScalar) {
			String text = ((YAMLScalar) value).getTextValue();
			return text == null ? null : text.trim();
		}
		if (value instanceof YAMLSequence) {
			// e.g. govern: [a, b] -- join for display.
			List<String> items = new ArrayList<>();
			for (YAMLSequenceItem item : ((YAMLSequence) value).getItems()) {
				if (item.getValue() instanceof YAMLScalar) {
					items.add(((YAMLScalar) item.getValue()).getTextValue());
				}
			}
			return String.join(", ", items);
		}
		return null;
	}

	private int offsetOf(YAMLKeyValue kv) {
		if (kv == null || kv.getKey() == null) {
			return -1;
		}
		return kv.getKey().getTextRange().getStartOffset();
	}

	private static String simpleName(String fqn) {
		if (fqn == null) {
			return "";
		}
		String name = fqn;
		int dollar = name.lastIndexOf('$');
		if (dollar >= 0) {
			name = name.substring(dollar + 1);
		}
		int dot = name.lastIndexOf('.');
		if (dot >= 0) {
			name = name.substring(dot + 1);
		}
		// Tidy a leading property tag like ${TestClass}.
		return name.replace("}", "").replace("{", "");
	}

	private static String shortException(String exceptionType) {
		if (exceptionType == null) {
			return "";
		}
		int dot = exceptionType.lastIndexOf('.');
		return dot >= 0 ? exceptionType.substring(dot + 1) : exceptionType;
	}

	/** A class reference is resolvable in Java only when it has no property substitution tag. */
	private static boolean resolvable(String className) {
		return className != null && !className.contains("${");
	}
}
