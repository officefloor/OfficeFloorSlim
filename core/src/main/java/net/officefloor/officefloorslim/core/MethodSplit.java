package net.officefloor.officefloorslim.core;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import net.officefloor.sinewslim.javaparser.JavaFacts;
import net.officefloor.sinewslim.javaparser.MethodUnit;
import net.officefloor.sinewslim.javaparser.ParsedFile;

/**
 * One method of a source file, priced as the scan prices it: SinewSlim's assessment, the
 * injection split and the combined split. For a caller holding one file, the IDE's plugin, so
 * that what it shows on a function is what {@code officefloorslim scan} reports for it.
 *
 * @param type       the declaring type, outer types first
 * @param name       the method's name
 * @param signature  its name and parameter types, as written
 * @param declLine   the declaration's first line
 * @param nameLine   the line of its name
 * @param endLine    its last line
 * @param length     its code lines, as SinewSlim counts them
 * @param pricing    both prices; null under the cap
 */
public record MethodSplit(String type, String name, String signature, int declLine, int nameLine, int endLine,
		int length, Pricing pricing) {

	/** Whether it is long: 26 code lines or more (SinewSlim's trigger). */
	public boolean longMethod() {
		return pricing != null;
	}

	/**
	 * Every method of the file with a body, long ones priced; empty when it does not parse. Runs
	 * the search on a thread of its own with a large stack.
	 */
	public static List<MethodSplit> of(String path, String source) {
		return onLargeStack(() -> read(path, source));
	}

	private static List<MethodSplit> read(String path, String source) {
		ParsedFile f = JavaFacts.parse(path, source, List.of(ShapeExtractor.INSTANCE));
		List<MethodSplit> out = new ArrayList<>();
		if (!f.parsed()) {
			return out;
		}
		for (MethodUnit m : f.methods()) {
			int length = m.facts().length();
			Pricing pricing = null;
			if (length >= OfficeFloorSplit.Params.DEFAULTS.trigger()) {
				pricing = Pricing.of(m.facts(), m.extras().get(ShapeExtractor.KEY).orElseThrow());
			}
			out.add(new MethodSplit(m.type(), m.name(), m.signature(), m.declLine(), m.nameLine(), m.endLine(), length,
					pricing));
		}
		return out;
	}

	/**
	 * The method of the file whose name is on {@code nameLine}; null when there is none or the
	 * file does not parse.
	 */
	public static MethodSplit at(String path, String source, int nameLine) {
		for (MethodSplit m : of(path, source)) {
			if (m.nameLine() == nameLine) {
				return m;
			}
		}
		return null;
	}

	/**
	 * Runs {@code work} on a thread with a large stack: SinewSlim's search recurses once or twice
	 * per level of the statement tree, and a long else-if chain is deep.
	 */
	static <T> T onLargeStack(Supplier<T> work) {
		Object[] result = new Object[1];
		Throwable[] failure = new Throwable[1];
		Thread t = new Thread(null, () -> {
			try {
				result[0] = work.get();
			} catch (Throwable e) {
				failure[0] = e;
			}
		}, "officefloorslim-split", 512L << 20);
		t.start();
		try {
			t.join();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
		if (failure[0] instanceof RuntimeException r) {
			throw r;
		}
		if (failure[0] instanceof Error e) {
			throw e;
		}
		if (failure[0] != null) {
			throw new IllegalStateException(failure[0]);
		}
		@SuppressWarnings("unchecked")
		T t2 = (T) result[0];
		return t2;
	}
}
