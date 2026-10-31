package net.officefloor.officefloorslim.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import net.officefloor.officefloorslim.core.Classification.Reading;
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

		ObjectNode bar = o.putObject("bar");
		bar.put("not_slimmable", r.bar().notSlimmable());
		ObjectNode cats = bar.putObject("by_category");
		for (Category c : Category.values()) {
			cats.put(String.valueOf(c.number()), r.bar().byCategory().getOrDefault(c, 0));
		}
		bar.put("category3_share", Math.round(r.bar().share() * 1000) / 1000.0);
		bar.put("go", r.bar().go());
		bar.put("rule", "category 3 at least a quarter of not_slimmable, and at least 30");

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
			assessment(e.putObject("officefloor"), p.officefloor());
			if (p.category() == null) {
				e.putNull("category");
			} else {
				e.putObject("category").put("number", p.category().number()).put("label", p.category().label());
			}
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
			o.put("cost", s.cost()).put("width", s.width()).put("pieces", s.pieces()).put("remainder", s.remainder());
		} else {
			o.putNull("cost");
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
