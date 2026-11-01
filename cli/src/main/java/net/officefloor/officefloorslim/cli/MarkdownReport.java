package net.officefloor.officefloorslim.cli;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import net.officefloor.officefloorslim.core.Classification.Reading;
import net.officefloor.officefloorslim.core.OfficeFloorSplit;
import net.officefloor.officefloorslim.core.Pricing;
import net.officefloor.officefloorslim.core.Pricing.Category;
import net.officefloor.officefloorslim.core.RepositoryScan.Method;
import net.officefloor.officefloorslim.core.RepositoryScan.Result;
import net.officefloor.sinewslim.core.Assessment;
import net.officefloor.sinewslim.core.SlimResult;

/** The scan for reading: the summary, the go/no-go numbers, and the not-slimmable methods by category. */
final class MarkdownReport {

	private MarkdownReport() {
	}

	static String write(Result r, String version) {
		StringBuilder b = new StringBuilder();
		List<Method> longs = r.methods().stream().filter(Method::longMethod).toList();
		b.append("# OfficeFloorSlim scan\n\n");
		b.append("`").append(r.root()).append("`");
		if (r.commit() != null) {
			b.append(" at `").append(r.commit(), 0, Math.min(12, r.commit().length())).append("`");
		}
		b.append(", officefloorslim ").append(version).append(", ").append(JsonReport.SCHEMA).append(".\n\n");
		b.append("The OfficeFloor column is **").append(Pricing.OFFICEFLOOR_READING)
				.append("**: functions and their flows, every line in one; the cost is the carried values, plus 1 per function and per flow.\n\n");

		b.append("## Summary\n\n| | Methods |\n|---|---:|\n");
		b.append("| Files read | ").append(r.files()).append(" |\n");
		b.append("| Files not parsed | ").append(r.unparsed().size()).append(" |\n");
		b.append("| Methods | ").append(r.methods().size()).append(" |\n");
		for (Reading reading : Reading.values()) {
			b.append("| … ").append(name(reading.name())).append(" | ").append(r.byReading().getOrDefault(reading, 0))
					.append(" |\n");
		}
		b.append("| Long (26 code lines or more) | ").append(longs.size()).append(" |\n");
		b.append("| Not slimmable with one return per piece (M1) | ").append(r.bar().notSlimmable()).append(" |\n");
		for (Category c : Category.values()) {
			b.append("| … ").append(c.number()).append(": ").append(c.label()).append(" | ")
					.append(r.bar().byCategory().getOrDefault(c, 0)).append(" |\n");
		}

		b.append("\n## The go/no-go bar\n\n");
		b.append(String.format(Locale.ROOT,
				"Category 3 is %d of %d not-slimmable methods (%.1f%%). The bar asks for at least a quarter, and at least 30: %s.\n",
				r.bar().category3(), r.bar().notSlimmable(), r.bar().share() * 100, r.bar().go() ? "**met**" : "**not met**"));
		b.append(String.format(Locale.ROOT,
				"With the combined split (%s: functions, with SinewSlim's extractions inside them) as the OfficeFloor column, category 3 is %d (%.1f%%): %s.\n",
				Pricing.COMBINED_READING, r.combinedBar().category3(), r.combinedBar().share() * 100,
				r.combinedBar().go() ? "**met**" : "**not met**"));
		b.append("The bar is a reading, not a gate.\n");

		var m = r.measures();
		b.append("\n## Wiring\n\n");
		b.append(m.files()).append(" configuration files, ").append(m.lines()).append(" non-blank lines, ")
				.append(m.functions()).append(" functions, ").append(m.edges()).append(" flows.");
		if (m.windowCommits() >= 0) {
			b.append(" Of the ").append(m.windowCommits()).append(" commits in the growth window, ")
					.append(m.touchingCommits()).append(" changed one.");
		}
		b.append("\n");

		for (Category c : List.of(Category.FUNCTION_INJECTION, Category.RESTRUCTURE, Category.RECORDS, Category.REDESIGN)) {
			List<Method> in = longs.stream().filter(x -> x.pricing().category() == c)
					.sorted(Comparator.comparingInt(Method::length).reversed()).toList();
			b.append("\n## Category ").append(c.number()).append(": ").append(c.label()).append(" (").append(in.size())
					.append(")\n\n");
			if (in.isEmpty()) {
				b.append("None.\n");
				continue;
			}
			b.append("| Method | Lines | Reading | SinewSlim | OfficeFloor column | Combined | Migration | Growth |\n");
			b.append("|---|---:|---|---|---|---|---|---|\n");
			for (Method x : in) {
				Pricing p = x.pricing();
				var mig = p.migration();
				b.append("| `").append(x.path()).append(":").append(x.line()).append("` ").append(cell(x.type()))
						.append(".").append(cell(x.signature())).append(" | ").append(x.length()).append(" | ")
						.append(name(x.classification().reading().name())).append(": ").append(cell(x.classification().evidence()))
						.append(" | ").append(price(p.conventional())).append(" | ").append(plan(p.officefloor()))
						.append(" | ").append(combined(p.combined()))
						.append(" | ").append(mig.fields()).append(" fields").append(mig.transactional() ? ", transactional" : "")
						.append(", ").append(mig.escalations()).append(" escalations")
						.append(mig.functions() < 0 ? "" : ", " + mig.functions() + " functions, " + mig.edges() + " flows")
						.append(" | ").append(x.growth() == null ? "–"
								: "+" + x.growth().linesAdded() + " lines, " + plural(x.growth().commits(), "commit"))
						.append(" |\n");
			}
		}

		if (!r.unparsed().isEmpty()) {
			b.append("\n## Not parsed\n\n");
			r.unparsed().forEach(u -> b.append("- `").append(u.path()).append("`: ").append(cell(u.problem())).append("\n"));
		}
		return b.toString();
	}

	static String price(Assessment a) {
		SlimResult s = a.proposal();
		String status = name(a.status().name());
		if (s.applicable() && s.feasible()) {
			return status + ", cost " + s.cost() + " (" + s.pieces() + " pieces)";
		}
		return a.obstacle() == null ? status : status + ": " + name(a.obstacle().name());
	}

	static String plural(int n, String noun) {
		return n + " " + noun + (n == 1 ? "" : "s");
	}

	static String plan(OfficeFloorSplit.Plan p) {
		if (!p.feasible()) {
			return "not splittable: " + name(p.reason().name()) + (p.detail() == null ? "" : ", " + p.detail());
		}
		return "cost " + p.total() + " (" + plural(p.functions().size(), "function") + ", " + plural(p.edges(), "flow")
				+ ", worst seam " + p.worstSeam() + ")";
	}

	static String combined(OfficeFloorSplit.Plan p) {
		if (!p.feasible()) {
			return "not splittable: " + name(p.reason().name());
		}
		return "sum " + p.sum() + " (" + plural(p.functions().size(), "function") + ", " + plural(p.pieces(), "piece")
				+ (p.extractionOnly() ? ", extraction only" : "") + ")";
	}

	static String name(String constant) {
		return constant.toLowerCase(Locale.ROOT).replace('_', ' ');
	}

	static String cell(String s) {
		return s == null ? "" : s.replace("|", "\\|").replace("\n", " ");
	}
}
