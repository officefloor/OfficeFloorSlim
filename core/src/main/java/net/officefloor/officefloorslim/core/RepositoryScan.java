package net.officefloor.officefloorslim.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import net.officefloor.officefloorslim.core.Classification.Reading;
import net.officefloor.officefloorslim.core.Pricing.Category;
import net.officefloor.sinewslim.core.Assessment.Status;
import net.officefloor.sinewslim.facts.MethodFacts;
import net.officefloor.sinewslim.javaparser.JavaFacts;
import net.officefloor.sinewslim.javaparser.MethodUnit;
import net.officefloor.sinewslim.javaparser.ParsedFile;
import net.officefloor.sinewslim.scan.Glob;
import net.officefloor.sinewslim.scan.Scanner;
import net.officefloor.sinewslim.scan.Scanner.FileResult;
import net.officefloor.sinewslim.scan.Scanner.Unreadable;

/**
 * The scan (DESIGN.md, Surfaces): every method of a repository classified, and every long one
 * priced both ways, with its category, its migration cost and its growth; the wiring measured.
 * Report only: nothing is changed.
 */
public final class RepositoryScan {

	/** The version of what a scan reports; a report's readers check it. */
	public static final int SCHEMA = 2;

	/** Backend Java and the OfficeFloor configuration beside it; tests and build output left out. */
	public static final List<String> DEFAULT_INCLUDE = List.of("**/*.java", "**/*.yml", "**/*.yaml", "**/*.woof",
			"**/*.officefloor");

	public static final List<String> DEFAULT_EXCLUDE = List.of("**/src/test/**", "**/src/it/**", "**/build/**",
			"**/target/**", "**/generated/**", "**/node_modules/**");

	/**
	 * How a scan runs.
	 *
	 * @param include       the globs of the files read
	 * @param exclude       the globs of the files left out
	 * @param threads       the threads that read and price
	 * @param growthCommits the commits in the growth window ({@link Growth#DEFAULT_COMMITS}); 0 for
	 *                      no growth: none is read, and every growth and window count is null
	 *                      (a shallow clone has no history to read it from)
	 */
	public record Options(List<String> include, List<String> exclude, int threads, int growthCommits) {

		public static Options defaults() {
			return new Options(DEFAULT_INCLUDE, DEFAULT_EXCLUDE, Runtime.getRuntime().availableProcessors(),
					Growth.DEFAULT_COMMITS);
		}
	}

	/**
	 * A method, classified; priced when it is long.
	 *
	 * @param path           its file
	 * @param type           its declaring type, as SinewSlim names it ({@code Outer.Inner})
	 * @param signature      its name and parameter types
	 * @param line           the line of its name
	 * @param length         its code lines
	 * @param classification its reading and the evidence for it
	 * @param pricing        both prices, its category and its migration cost; null under the cap
	 * @param growth         its growth over the window; null under the cap, or without history
	 */
	public record Method(String path, String type, String signature, int line, int length,
			Classification classification, Pricing pricing, Growth.Of growth) {

		public boolean longMethod() {
			return pricing != null;
		}
	}

	/** A file that could not be read, and why. */
	public record Unparsed(String path, String problem) {
	}

	/**
	 * The wiring measures (W1 to W3): the configuration's size, and how often the window's commits
	 * touched it.
	 *
	 * @param files            the configuration files
	 * @param lines            their non-blank lines
	 * @param functions        the functions they declare
	 * @param edges            the flows they declare
	 * @param windowCommits    the commits in the growth window; -1 without history
	 * @param touchingCommits  those that changed a configuration file there now; -1 without history
	 */
	public record WiringMeasures(int files, int lines, int functions, int edges, int windowCommits,
			int touchingCommits) {
	}

	/**
	 * The go/no-go bar's two numbers on this repository (PLAN.md, Phase 3). On the study's Spring
	 * arms they decide; on any other repository they are a reading, not the decision.
	 *
	 * @param notSlimmable the long methods SinewSlim cannot slim with one return per piece (M1)
	 * @param byCategory   M1 by category
	 * @param share        category 3 over M1; 0 when M1 is empty
	 * @param go           category 3 is at least a quarter of M1, and at least 30
	 */
	public record Bar(int notSlimmable, Map<Category, Integer> byCategory, double share, boolean go) {

		public int category3() {
			return byCategory.getOrDefault(Category.FUNCTION_INJECTION, 0);
		}
	}

	/**
	 * A scan's result.
	 *
	 * @param root       the repository scanned
	 * @param commit     its {@code HEAD}; null outside git
	 * @param options    how it ran
	 * @param seconds    how long it took
	 * @param files      the files read
	 * @param unparsed   those that could not be
	 * @param methods    every method with a body, in file order
	 * @param wiring     the configuration files
	 * @param measures   the wiring measures
	 * @param byReading  every method by reading
	 * @param bar        the go/no-go bar's numbers, with the injection split as the OfficeFloor column
	 * @param combinedBar the same numbers with the combined split as the OfficeFloor column
	 */
	public record Result(Path root, String commit, Options options, double seconds, int files, List<Unparsed> unparsed,
			List<Method> methods, List<WiringFile> wiring, WiringMeasures measures, Map<Reading, Integer> byReading,
			Bar bar, Bar combinedBar) {
	}

	/** What one file gave: a Java file's methods (long ones priced), or a configuration's wiring. */
	private record Read(List<Unit> units, WiringFile wiring) {
	}

	private record Unit(MethodUnit method, MethodShape shape, Pricing pricing) {
	}

	private RepositoryScan() {
	}

	public static Result scan(Path root, Options options) throws IOException {
		long started = System.nanoTime();
		List<Glob> include = options.include().stream().map(Glob::new).toList();
		List<Glob> exclude = options.exclude().stream().map(Glob::new).toList();
		List<FileResult<Read>> results = Scanner.scan(root, include, exclude, options.threads(), RepositoryScan::read);

		List<Unparsed> unparsed = new ArrayList<>();
		List<WiringFile> wiringFiles = new ArrayList<>();
		for (FileResult<Read> r : results) {
			if (!r.read()) {
				unparsed.add(new Unparsed(r.path(), r.problem()));
			} else if (r.result() != null && r.result().wiring() != null) {
				wiringFiles.add(r.result().wiring());
			}
		}
		Wiring wiring = new Wiring(wiringFiles);

		Growth history = options.growthCommits() > 0 ? Growth.of(root, options.growthCommits()) : null;
		Map<String, String[]> blames = blames(history, results, options.threads());

		List<Method> methods = new ArrayList<>();
		Map<Reading, Integer> byReading = new EnumMap<>(Reading.class);
		Map<Category, Integer> byCategory = new EnumMap<>(Category.class);
		Map<Category, Integer> byCombinedCategory = new EnumMap<>(Category.class);
		int notSlimmable = 0;
		for (FileResult<Read> r : results) {
			if (!r.read() || r.result() == null) {
				continue;
			}
			for (Unit u : r.result().units()) {
				MethodUnit m = u.method();
				Classification c = Classification.of(u.shape(), m.name(), wiring);
				byReading.merge(c.reading(), 1, Integer::sum);
				Growth.Of growth = null;
				String[] blame = blames.get(r.path());
				if (u.pricing() != null && blame != null) {
					growth = history.of(blame, u.shape().codeLines());
				}
				if (u.pricing() != null && u.pricing().notSlimmable()) {
					notSlimmable++;
					byCategory.merge(u.pricing().category(), 1, Integer::sum);
					byCombinedCategory.merge(u.pricing().combinedCategory(), 1, Integer::sum);
				}
				methods.add(new Method(r.path(), m.type(), m.signature(), m.nameLine(), m.facts().length(), c,
						u.pricing(), growth));
			}
		}

		int lines = 0, functions = 0, edges = 0;
		Set<String> wiringPaths = new HashSet<>();
		for (WiringFile f : wiringFiles) {
			lines += f.lines();
			functions += f.functions();
			edges += f.edges();
			wiringPaths.add(f.path());
		}
		WiringMeasures measures = new WiringMeasures(wiringFiles.size(), lines, functions, edges,
				history == null ? -1 : history.window().size(), history == null ? -1 : history.touching(wiringPaths));

		Bar bar = bar(notSlimmable, byCategory);
		Bar combinedBar = bar(notSlimmable, byCombinedCategory);

		List<String> head = Growth.git(root, "rev-parse", "HEAD");
		String commit = head == null || head.isEmpty() ? null : head.get(0).trim();
		double seconds = (System.nanoTime() - started) / 1e9;
		return new Result(root, commit, options, seconds, results.size(), unparsed, methods, wiringFiles, measures,
				byReading, bar, combinedBar);
	}

	private static Bar bar(int notSlimmable, Map<Category, Integer> byCategory) {
		int category3 = byCategory.getOrDefault(Category.FUNCTION_INJECTION, 0);
		double share = notSlimmable == 0 ? 0 : (double) category3 / notSlimmable;
		return new Bar(notSlimmable, byCategory, share, share >= 0.25 && category3 >= 30);
	}

	/** One file: Java parsed with the shape extractor and its long methods priced, or a configuration read. */
	static Read read(String path, String source) throws Unreadable {
		if (path.endsWith(".java")) {
			ParsedFile parsed = JavaFacts.parse(path, source, List.of(ShapeExtractor.INSTANCE));
			if (!parsed.parsed()) {
				throw new Unreadable(parsed.problem());
			}
			List<Unit> units = new ArrayList<>(parsed.methods().size());
			for (MethodUnit m : parsed.methods()) {
				MethodShape shape = m.extras().get(ShapeExtractor.KEY).orElseThrow();
				MethodFacts facts = m.facts();
				Pricing pricing = Pricing.of(facts, shape);
				units.add(new Unit(m, shape, pricing.conventional().status() == Status.UNDER_CAP ? null : pricing));
			}
			return new Read(units, null);
		}
		if (WiringFile.candidate(path)) {
			try {
				return new Read(List.of(), WiringFile.read(path, source));
			} catch (RuntimeException e) {
				// a YAML or XML file that does not parse is not a configuration this scan reads
				return new Read(List.of(), null);
			}
		}
		return null;
	}

	/** The blame of every file with a long method, in parallel; empty without history. */
	private static Map<String, String[]> blames(Growth history, List<FileResult<Read>> results, int threads)
			throws IOException {
		Map<String, String[]> out = new LinkedHashMap<>();
		if (history == null) {
			return out;
		}
		List<String> paths = new ArrayList<>();
		for (FileResult<Read> r : results) {
			if (r.read() && r.result() != null && r.result().units().stream().anyMatch(u -> u.pricing() != null)) {
				paths.add(r.path());
			}
		}
		ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads), Scanner.deepStacks());
		try {
			List<Future<String[]>> futures = new ArrayList<>();
			for (String p : paths) {
				futures.add(pool.submit(() -> history.blame(p)));
			}
			for (int i = 0; i < paths.size(); i++) {
				String[] b = futures.get(i).get();
				if (b != null) {
					out.put(paths.get(i), b);
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("scan interrupted", e);
		} catch (ExecutionException e) {
			throw new IOException("git blame failed", e.getCause());
		} finally {
			pool.shutdownNow();
		}
		return out;
	}
}
