package net.officefloor.officefloorslim.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import net.officefloor.officefloorslim.core.Classification.Reading;
import net.officefloor.officefloorslim.core.OfficeFloorSplit;
import net.officefloor.officefloorslim.core.Pricing;
import net.officefloor.officefloorslim.core.Pricing.Category;
import net.officefloor.officefloorslim.core.Pricing.Migration;
import net.officefloor.officefloorslim.core.RepositoryScan;
import net.officefloor.officefloorslim.core.RepositoryScan.Method;
import net.officefloor.officefloorslim.core.RepositoryScan.Result;
import net.officefloor.officefloorslim.core.WiringFile;
import net.officefloor.sinewslim.core.Assessment;
import net.officefloor.sinewslim.core.SlimResult;
import net.officefloor.sinewslim.facts.MethodFacts;

/**
 * The scan as JSON: the contract later tools and the study read. {@code schema} names the
 * version; every number the scan decided by is written into it.
 */
final class JsonReport {

	static final String SCHEMA = "officefloorslim-scan/" + RepositoryScan.SCHEMA;

	private JsonReport() {
	}

	static String write(Result r, String version) {
		ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
		ObjectNode o = json.createObjectNode();
		o.put("schema", SCHEMA);
		o.put("tool", "officefloorslim " + version);
		o.put("facts_schema", MethodFacts.SCHEMA);
		o.put("officefloor_reading", Pricing.OFFICEFLOOR_READING);
		o.put("combined_reading", Pricing.COMBINED_READING);
		o.put("repository", r.root().toString());
		o.put("commit", r.commit());
		o.put("seconds", Math.round(r.seconds() * 10) / 10.0);
		ObjectNode options = o.putObject("options");
		r.options().include().forEach(options.putArray("include")::add);
		r.options().exclude().forEach(options.putArray("exclude")::add);
		options.put("growth_commits", r.options().growthCommits());
		options.put("growth_months", 12);
		o.put("files", r.files());
		ArrayNode unparsed = o.putArray("unparsed");
		r.unparsed().forEach(u -> unparsed.addObject().put("path", u.path()).put("problem", u.problem()));

		ObjectNode readings = o.putObject("methods_by_reading");
		for (Reading reading : Reading.values()) {
			readings.put(reading.name().toLowerCase(), r.byReading().getOrDefault(reading, 0));
		}
		o.put("long_methods", r.methods().stream().filter(Method::longMethod).count());

		bar(o.putObject("bar"), r.bar());
		bar(o.putObject("combined_bar"), r.combinedBar());

		ObjectNode wiring = o.putObject("wiring");
		var m = r.measures();
		wiring.put("files", m.files()).put("lines", m.lines()).put("functions", m.functions()).put("edges", m.edges());
		putCount(wiring, "window_commits", m.windowCommits());
		putCount(wiring, "touching_commits", m.touchingCommits());
		ArrayNode wfiles = wiring.putArray("by_file");
		for (WiringFile f : r.wiring()) {
			ObjectNode w = wfiles.addObject().put("path", f.path()).put("kind", f.kind().name().toLowerCase())
					.put("lines", f.lines()).put("functions", f.functions()).put("edges", f.edges());
			ArrayNode refs = w.putArray("wires");
			f.refs().forEach(ref -> refs.add(ref.method() == null ? ref.className() : ref.className() + "#" + ref.method()));
		}

		ArrayNode methods = o.putArray("methods");
		for (Method x : r.methods()) {
			if (!x.longMethod()) {
				continue;
			}
			ObjectNode e = methods.addObject();
			e.put("path", x.path()).put("type", x.type()).put("signature", x.signature()).put("line", x.line())
					.put("length", x.length());
			e.put("reading", x.classification().reading().name().toLowerCase());
			e.put("evidence", x.classification().evidence());
			Pricing p = x.pricing();
			assessment(e.putObject("sinewslim"), p.conventional());
			plan(e.putObject("officefloor"), p.officefloor());
			combined(e.putObject("combined"), p.combined());
			category(e, "category", p.category());
			category(e, "combined_category", p.combinedCategory());
			Migration mig = p.migration();
			ObjectNode mo = e.putObject("migration");
			mo.put("fields", mig.fields()).put("transactional", mig.transactional()).put("escalations", mig.escalations());
			putCount(mo, "functions", mig.functions());
			putCount(mo, "edges", mig.edges());
			if (x.growth() == null) {
				e.putNull("growth");
			} else {
				e.putObject("growth").put("lines_added", x.growth().linesAdded()).put("commits", x.growth().commits());
			}
		}
		try {
			return json.writeValueAsString(o) + "\n";
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	static void assessment(ObjectNode o, Assessment a) {
		o.put("status", a.status().name().toLowerCase());
		if (a.obstacle() == null) {
			o.putNull("obstacle");
		} else {
			o.put("obstacle", a.obstacle().name().toLowerCase());
		}
		SlimResult s = a.proposal();
		if (s.applicable() && s.feasible()) {
			o.put("cost", s.cost()).put("width", s.width()).put("pieces", s.pieces()).put("remainder", s.remainder())
					.put("largest_piece", s.pieceLines().stream().mapToInt(Integer::intValue).max().orElse(0));
		} else {
			o.putNull("cost");
		}
	}

	/** The OfficeFloor split's plan: its functions and flows, or why there is none. */
	static void plan(ObjectNode o, OfficeFloorSplit.Plan p) {
		o.put("feasible", p.feasible());
		if (!p.feasible()) {
			o.put("reason", p.reason().name().toLowerCase());
			if (p.detail() == null) {
				o.putNull("detail");
			} else {
				o.put("detail", p.detail());
			}
			o.putNull("total");
			return;
		}
		o.put("total", p.total()).put("worst_seam", p.worstSeam()).put("edges", p.edges())
				.put("largest_function", p.functions().stream().mapToInt(OfficeFloorSplit.Function::lines).max().orElse(0));
		ArrayNode fns = o.putArray("functions");
		for (OfficeFloorSplit.Function f : p.functions()) {
			ObjectNode fo = fns.addObject().put("kind", f.kind().name().toLowerCase()).put("lo", f.lo()).put("hi", f.hi())
					.put("lines", f.lines());
			f.declares().forEach(fo.putArray("declares")::add);
		}
	}

	static void bar(ObjectNode bar, RepositoryScan.Bar b) {
		bar.put("not_slimmable", b.notSlimmable());
		ObjectNode cats = bar.putObject("by_category");
		for (Category c : Category.values()) {
			cats.put(String.valueOf(c.number()), b.byCategory().getOrDefault(c, 0));
		}
		bar.put("category3_share", Math.round(b.share() * 1000) / 1000.0);
		bar.put("go", b.go());
		bar.put("rule", "category 3 at least a quarter of not_slimmable, and at least 30");
	}

	static void category(ObjectNode e, String name, Category c) {
		if (c == null) {
			e.putNull(name);
		} else {
			e.putObject(name).put("number", c.number()).put("label", c.label());
		}
	}

	/**
	 * The combined split's plan: its functions, each with SinewSlim's pieces inside it, the two
	 * totals and their sum; or why there is none.
	 */
	static void combined(ObjectNode o, OfficeFloorSplit.Plan p) {
		o.put("feasible", p.feasible());
		if (!p.feasible()) {
			o.put("reason", p.reason().name().toLowerCase());
			if (p.detail() == null) {
				o.putNull("detail");
			} else {
				o.put("detail", p.detail());
			}
			o.putNull("sum");
			return;
		}
		o.put("sum", p.sum()).put("injection_total", p.total()).put("extraction_total", p.extraction())
				.put("worst_seam", p.worstSeam()).put("worst_piece", p.worstPiece()).put("edges", p.edges())
				.put("pieces", p.pieces()).put("extraction_only", p.extractionOnly())
				.put("tie_by_pieces", p.tieByPieces());
		ArrayNode fns = o.putArray("functions");
		for (OfficeFloorSplit.Function f : p.functions()) {
			ObjectNode fo = fns.addObject().put("kind", f.kind().name().toLowerCase()).put("lo", f.lo()).put("hi", f.hi())
					.put("lines", f.lines());
			f.declares().forEach(fo.putArray("declares")::add);
			ArrayNode pieces = fo.putArray("pieces");
			SlimResult in = f.inner();
			if (in != null) {
				for (int i = 0; i < in.pieces(); i++) {
					var s = in.cuts().get(i);
					ObjectNode po = pieces.addObject().put("lo", s.lo()).put("hi", s.hi()).put("lines", in.pieceLines().get(i));
					s.inputs().forEach(po.putArray("inputs")::add);
					s.outputs().forEach(po.putArray("outputs")::add);
				}
				fo.put("remainder", in.remainder()).put("extraction", in.cost());
			}
		}
	}

	/** A count, or null when it is not known (-1). */
	static void putCount(ObjectNode o, String name, int n) {
		if (n < 0) {
			o.putNull(name);
		} else {
			o.put(name, n);
		}
	}
}
