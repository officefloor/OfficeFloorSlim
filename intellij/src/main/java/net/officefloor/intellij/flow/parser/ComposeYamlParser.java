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

import net.officefloor.officefloorslim.graph.ComposeGraph;
import net.officefloor.officefloorslim.graph.FlowGraph;
import net.officefloor.officefloorslim.graph.YamlTree;
import net.officefloor.officefloorslim.graph.YamlTree.Entry;
import net.officefloor.officefloorslim.graph.YamlTree.Mapping;
import net.officefloor.officefloorslim.graph.YamlTree.Scalar;
import net.officefloor.officefloorslim.graph.YamlTree.Sequence;

/**
 * Parses an OfficeFloor "compose" / Activity YAML document (as used for REST
 * endpoints and activity flows) into a {@link FlowGraph}.
 *
 * <p>
 * Uses IntelliJ's YAML PSI so that each node and link carries the source offset
 * of its declaration, enabling click-to-navigate back into the editor. The graph
 * itself is built by {@link ComposeGraph}, which the scan outside the IDE uses too.
 */
public final class ComposeYamlParser {

	/**
	 * @return a parsed graph, or {@code null} if the document does not look like an
	 *         OfficeFloor compose/activity configuration.
	 */
	public FlowGraph parse(YAMLFile file, VirtualFile virtualFile) {
		return ComposeGraph.build(topMapping(file), virtualFile == null ? null : virtualFile.getPath());
	}

	/** The first document whose top-level value is a mapping, as the graph reads it; or null. */
	static Mapping topMapping(YAMLFile file) {
		if (file == null) {
			return null;
		}
		for (YAMLDocument document : file.getDocuments()) {
			YAMLValue top = document.getTopLevelValue();
			if (top instanceof YAMLMapping) {
				return mapping((YAMLMapping) top);
			}
		}
		return null;
	}

	static YamlTree tree(YAMLValue value) {
		if (value instanceof YAMLMapping) {
			return mapping((YAMLMapping) value);
		}
		if (value instanceof YAMLSequence) {
			List<YamlTree> items = new ArrayList<>();
			for (YAMLSequenceItem item : ((YAMLSequence) value).getItems()) {
				items.add(tree(item.getValue()));
			}
			return new Sequence(items);
		}
		if (value instanceof YAMLScalar) {
			return new Scalar(((YAMLScalar) value).getTextValue());
		}
		return null;
	}

	static Mapping mapping(YAMLMapping mapping) {
		List<Entry> entries = new ArrayList<>();
		for (YAMLKeyValue kv : mapping.getKeyValues()) {
			entries.add(new Entry(kv.getKeyText(), tree(kv.getValue()), offsetOf(kv)));
		}
		return new Mapping(entries);
	}

	private static int offsetOf(YAMLKeyValue kv) {
		if (kv == null || kv.getKey() == null) {
			return -1;
		}
		return kv.getKey().getTextRange().getStartOffset();
	}
}
