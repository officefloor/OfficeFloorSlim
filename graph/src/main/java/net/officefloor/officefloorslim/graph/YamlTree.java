package net.officefloor.officefloorslim.graph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A YAML document as the graph builder reads it, whichever parser read it: IntelliJ's YAML PSI
 * in the plugin, SnakeYAML outside the IDE. Only what {@link ComposeGraph} reads is kept, so the
 * two readers cannot disagree on anything else.
 */
public sealed interface YamlTree {

	/**
	 * A mapping's entry.
	 *
	 * @param key    the key's text; null for a key that is not a scalar
	 * @param value  its value; null when it has none ({@code key:} alone)
	 * @param offset where the key starts in the document, in characters; -1 when unknown
	 */
	record Entry(String key, YamlTree value, int offset) {
	}

	/** A mapping, its entries in document order. */
	record Mapping(List<Entry> entries) implements YamlTree {

		public Mapping {
			entries = List.copyOf(entries);
		}

		/** The first entry with the key, or null. */
		public Entry get(String key) {
			for (Entry e : entries) {
				if (key.equals(e.key())) {
					return e;
				}
			}
			return null;
		}
	}

	/** A scalar's value: its text, quotes and escapes resolved. */
	record Scalar(String text) implements YamlTree {
	}

	/** A sequence, its items in document order; an item is null when it has no value. */
	record Sequence(List<YamlTree> items) implements YamlTree {

		public Sequence {
			items = Collections.unmodifiableList(new ArrayList<>(items)); // may hold nulls
		}
	}
}
