package net.officefloor.officefloorslim.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

import net.officefloor.officefloorslim.core.Growth;
import net.officefloor.officefloorslim.core.RepositoryScan;
import net.officefloor.officefloorslim.core.RepositoryScan.Options;
import net.officefloor.officefloorslim.core.RepositoryScan.Result;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** {@code officefloorslim scan}: a report-only scan of a repository. */
@Command(name = "officefloorslim", mixinStandardHelpOptions = true, versionProvider = OfficeFloorSlim.Version.class,
		subcommands = OfficeFloorSlim.Scan.class, description = "Prices long Java methods both ways: SinewSlim's conventional split and OfficeFloor's.")
public final class OfficeFloorSlim {

	/** The parser and the search recurse with the code's nesting: a long else-if chain needs a deep stack. */
	static final long STACK_BYTES = 256L * 1024 * 1024;

	public static void main(String[] args) throws InterruptedException {
		int[] code = new int[1];
		Thread t = new Thread(null, () -> code[0] = new CommandLine(new OfficeFloorSlim()).execute(args), "officefloorslim",
				STACK_BYTES);
		t.start();
		t.join();
		System.exit(code[0]);
	}

	static String version() {
		String v = OfficeFloorSlim.class.getPackage().getImplementationVersion();
		return v == null ? "dev" : v;
	}

	static final class Version implements CommandLine.IVersionProvider {
		@Override
		public String[] getVersion() {
			return new String[] { "officefloorslim " + version() };
		}
	}

	@Command(name = "scan", mixinStandardHelpOptions = true, description = "Classify every method, price every long one both ways, measure the wiring.")
	static final class Scan implements Callable<Integer> {

		@Option(names = "--repo", description = "The repository (default: the current directory)")
		Path repo = Path.of(".");

		@Option(names = "--format", description = "json or markdown (default: json)")
		String format = "json";

		@Option(names = "--out", description = "Write the report here (default: standard output)")
		Path out;

		@Option(names = "--include", description = "A glob of files to read; repeat for more (default: Java and OfficeFloor configuration)")
		List<String> include;

		@Option(names = "--exclude", description = "A glob of files to leave out; repeat for more (default: tests and build output)")
		List<String> exclude;

		@Option(names = "--commits", description = "Commits in the growth window, capped at 12 months (default: "
				+ Growth.DEFAULT_COMMITS + ")")
		int commits = Growth.DEFAULT_COMMITS;

		@Option(names = "--no-growth", description = "Read no growth: for a clone without its history (a shallow one); growth is null in the report")
		boolean noGrowth;

		@Option(names = "--threads", description = "Threads (default: one per processor)")
		int threads = Runtime.getRuntime().availableProcessors();

		@Override
		public Integer call() throws IOException {
			if (!format.equals("json") && !format.equals("markdown")) {
				System.err.println("--format is json or markdown, not " + format);
				return 2;
			}
			Path root = repo.toAbsolutePath().normalize();
			Options options = new Options(include == null ? RepositoryScan.DEFAULT_INCLUDE : include,
					exclude == null ? RepositoryScan.DEFAULT_EXCLUDE : exclude, threads, noGrowth ? 0 : commits);
			Result r = RepositoryScan.scan(root, options);
			String text = format.equals("json") ? JsonReport.write(r, version()) : MarkdownReport.write(r, version());
			if (out == null) {
				System.out.print(text);
			} else {
				Files.writeString(out, text, StandardCharsets.UTF_8);
			}
			return 0;
		}
	}
}
