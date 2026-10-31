package net.officefloor.officefloorslim.graph;

import java.util.ArrayList;
import java.util.List;

import net.officefloor.officefloorslim.graph.YamlTree.Entry;
import net.officefloor.officefloorslim.graph.YamlTree.Mapping;
import net.officefloor.officefloorslim.graph.YamlTree.Scalar;
import net.officefloor.officefloorslim.graph.YamlTree.Sequence;

/**
 * Builds the {@link FlowGraph} of an OfficeFloor "compose" / Activity YAML document (as used for
 * REST endpoints and activity flows): which functions there are, the class and method each is
 * wired to, and the flows between them. The one set of rules for every reader of the YAML: the
 * plugin's (IntelliJ's PSI, {@code ComposeYamlParser}) and the scan's ({@link ComposeYamlReader}).
 */
public final class ComposeGraph {

	/** Reserved top-level key; everything else is a function. */
	private static final String COMPOSITION = "composition";

	private static final String EXTERNAL_PREFIX = "#";

	private ComposeGraph() {
	}

	/**
	 * @param root the document's top-level mapping, or null when it has none
	 * @param path the file's path with {@code /} separators, for the REST endpoint it maps to; null
	 *             for none
	 * @return a parsed graph, or {@code null} if the document does not look like an OfficeFloor
	 *         compose/activity configuration.
	 */
	public static FlowGraph build(Mapping root, String path) {
		if (root == null) {
			return null;
		}

		// Collect function key-values (everything except "composition").
		List<Entry> functionKvs = new ArrayList<>();
		Entry compositionKv = null;
		for (Entry kv : root.entries()) {
			String key = kv.key();
			if (COMPOSITION.equals(key)) {
				compositionKv = kv;
			} else if (key != null && !key.isEmpty() && kv.value() instanceof Mapping) {
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
		// is resolved lazily (in the plugin, by MetaDataEnricher when the data-flow view is shown).
		String startFunction = functionKvs.get(0).key();
		for (Entry kv : functionKvs) {
			graph.addNode(buildFunctionNode(kv));
		}

		// Optional HTTP endpoint derived from the file name / location.
		RestFileName rest = RestFileName.parse(path);
		if (rest != null && rest.isEndpoint()) {
			String endpointId = "endpoint::" + rest.endpointLabel();
			graph.addNode(new FlowNode(endpointId, NodeKind.HTTP_ENDPOINT, rest.endpointLabel(), "REST endpoint", -1,
					null, null));
			graph.addLink(new FlowLink(endpointId, startFunction, LinkKind.ROUTE, "", -1));
		}

		// Pass 2: links between functions.
		for (Entry kv : functionKvs) {
			String from = kv.key();
			Mapping body = (Mapping) kv.value();

			Entry nextKv = body.get("next");
			if (nextKv != null) {
				String target = scalarText(nextKv.value());
				if (target != null && !target.isEmpty()) {
					addLink(graph, from, target, LinkKind.NEXT, "next", nextKv.offset());
				}
			}

			addMapLinks(graph, from, body.get("outputs"), LinkKind.FLOW, false);
			addMapLinks(graph, from, body.get("escalations"), LinkKind.ESCALATION, true);
		}

		// Section-wide escalations default from composition: linked off the entry function.
		if (compositionKv != null && compositionKv.value() instanceof Mapping composition) {
			Entry escKv = composition.get("escalations");
			if (escKv != null && escKv.value() instanceof Mapping escalations) {
				for (Entry entry : escalations.entries()) {
					String target = scalarText(entry.value());
					if (target != null && !target.isEmpty()) {
						addLink(graph, startFunction, target, LinkKind.ESCALATION,
								shortException(entry.key()) + " (section)", entry.offset());
					}
				}
			}
		}

		return graph;
	}

	private static void addMapLinks(FlowGraph graph, String from, Entry mapKv, LinkKind kind, boolean exception) {
		if (mapKv == null || !(mapKv.value() instanceof Mapping map)) {
			return;
		}
		for (Entry entry : map.entries()) {
			String target = scalarText(entry.value());
			if (target == null || target.isEmpty()) {
				continue;
			}
			String label = exception ? shortException(entry.key()) : entry.key();
			addLink(graph, from, target, kind, label, entry.offset());
		}
	}

	/** Adds a link, materialising an external ({@code #name}) or unresolved target node as needed. */
	private static void addLink(FlowGraph graph, String from, String rawTarget, LinkKind kind, String label,
			int offset) {
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

	private static FlowNode buildFunctionNode(Entry kv) {
		String name = kv.key();
		Mapping body = (Mapping) kv.value();

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

		return new FlowNode(name, kind, name, subtitle, kv.offset(), javaClass, javaMethod);
	}

	// --- Heuristics ---------------------------------------------------------

	/** Confirms at least one function carries a compose-specific key. */
	private static boolean looksLikeCompose(List<Entry> functionKvs, Entry compositionKv) {
		if (compositionKv != null) {
			return true;
		}
		for (Entry kv : functionKvs) {
			Mapping body = (Mapping) kv.value();
			if (hasAnyKey(body, "class", "source", "procedure", "next", "outputs", "escalations", "method", "govern",
					"resource", "location", "input")) {
				return true;
			}
		}
		return false;
	}

	private static boolean hasAnyKey(Mapping mapping, String... keys) {
		for (String key : keys) {
			if (mapping.get(key) != null) {
				return true;
			}
		}
		return false;
	}

	// --- Tree helpers -------------------------------------------------------

	private static String text(Mapping mapping, String key) {
		Entry kv = mapping.get(key);
		return kv == null ? null : scalarText(kv.value());
	}

	private static String scalarText(YamlTree value) {
		if (value instanceof Scalar scalar) {
			String text = scalar.text();
			return text == null ? null : text.trim();
		}
		if (value instanceof Sequence sequence) {
			// e.g. govern: [a, b] -- join for display.
			List<String> items = new ArrayList<>();
			for (YamlTree item : sequence.items()) {
				if (item instanceof Scalar scalar) {
					items.add(scalar.text());
				}
			}
			return String.join(", ", items);
		}
		return null;
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
