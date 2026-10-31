package net.officefloor.officefloorslim.graph;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;

import net.officefloor.officefloorslim.graph.YamlTree.Entry;
import net.officefloor.officefloorslim.graph.YamlTree.Mapping;
import net.officefloor.officefloorslim.graph.YamlTree.Scalar;
import net.officefloor.officefloorslim.graph.YamlTree.Sequence;

/**
 * Reads a compose / Activity YAML file outside the IDE, by SnakeYAML, into the same
 * {@link FlowGraph} the plugin draws: the graph model the classification reads (which classes
 * and methods are wired as functions). The plugin reads the same files by IntelliJ's PSI; both
 * build the graph through {@link ComposeGraph}.
 */
public final class ComposeYamlReader {

	private ComposeYamlReader() {
	}

	/**
	 * @param path   the file's path with {@code /} separators (for the REST endpoint it maps to)
	 * @param source the file's text
	 * @return its graph, or {@code null} if it does not look like an OfficeFloor compose/activity
	 *         configuration
	 * @throws org.yaml.snakeyaml.error.YAMLException when the text is not YAML
	 */
	public static FlowGraph read(String path, String source) {
		return ComposeGraph.build(topMapping(source), path);
	}

	/** The first document whose top-level value is a mapping, as the plugin takes it; or null. */
	static Mapping topMapping(String source) {
		Yaml yaml = new Yaml(new LoaderOptions());
		for (Node document : yaml.composeAll(new StringReader(source))) {
			if (document instanceof MappingNode m) {
				return mapping(m);
			}
		}
		return null;
	}

	static YamlTree tree(Node node) {
		if (node instanceof MappingNode m) {
			return mapping(m);
		}
		if (node instanceof SequenceNode s) {
			List<YamlTree> items = new ArrayList<>(s.getValue().size());
			for (Node item : s.getValue()) {
				items.add(tree(item));
			}
			return new Sequence(items);
		}
		if (node instanceof ScalarNode s) {
			// a null scalar ("key:" alone, "~", "null") is no value, as the PSI reads it
			return Tag.NULL.equals(s.getTag()) ? null : new Scalar(s.getValue());
		}
		return null;
	}

	static Mapping mapping(MappingNode node) {
		List<Entry> entries = new ArrayList<>(node.getValue().size());
		for (NodeTuple t : node.getValue()) {
			Node key = t.getKeyNode();
			String text = key instanceof ScalarNode s ? s.getValue() : null;
			int offset = key.getStartMark() == null ? -1 : key.getStartMark().getIndex();
			entries.add(new Entry(text, tree(t.getValueNode()), offset));
		}
		return new Mapping(entries);
	}
}
