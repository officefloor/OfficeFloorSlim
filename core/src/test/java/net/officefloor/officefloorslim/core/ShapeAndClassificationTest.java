package net.officefloor.officefloorslim.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import net.officefloor.officefloorslim.core.Classification.Reading;
import net.officefloor.sinewslim.javaparser.JavaFacts;
import net.officefloor.sinewslim.javaparser.MethodUnit;
import net.officefloor.sinewslim.javaparser.ParsedFile;

/** What the shape extractor reads, and the reading each piece of evidence gives. */
class ShapeAndClassificationTest {

	static final String SOURCE = """
			package a.b;

			import net.officefloor.plugin.variable.Out;

			@Service
			public class Logic {

				@Override
				public void service(@Parameter String in, Out<String> out) throws java.io.IOException {
					try {
						out.set(in);
					} catch (IllegalStateException | IllegalArgumentException e) {
						throw new RuntimeException(e);
					}
					Runnable r = new Runnable() {
						public void run() { throw new Error(); }
					};
				}

				@GetMapping("/x")
				String get() { return ""; }

				public void publicInService() { }

				private void helper() { }

				@Next("done")
				void next() { }

				public void wired() { }

				static class Inner {
					public void inner() { }
				}
			}
			""";

	static Map<String, MethodUnit> methods() {
		ParsedFile f = JavaFacts.parse("a/b/Logic.java", SOURCE, List.of(ShapeExtractor.INSTANCE));
		assertTrue(f.parsed(), f.problem());
		return f.methods().stream().collect(Collectors.toMap(MethodUnit::name, m -> m, (x, y) -> x));
	}

	static MethodShape shape(MethodUnit m) {
		return m.extras().get(ShapeExtractor.KEY).orElseThrow();
	}

	@Test
	void readsTheShape() {
		MethodShape s = shape(methods().get("service"));
		assertEquals("a.b.Logic", s.className());
		assertEquals(List.of("Override"), s.methodAnnotations());
		assertEquals(List.of("Service"), s.typeAnnotations());
		assertEquals("Out<String>", s.params().get(1).type());
		assertEquals("Out", s.params().get(1).rawSimpleType());
		assertEquals(List.of("Parameter"), s.params().get(0).annotations());
		assertEquals(List.of("java.io.IOException"), s.declared());
		assertEquals(List.of("IllegalStateException", "IllegalArgumentException"), s.caught());
		assertEquals(1, s.throwsStatements()); // the anonymous class's throw is its own
		assertEquals("a.b.Logic$Inner", shape(methods().get("inner")).className());
		assertNull(shape(methods().get("run")).className()); // anonymous: no configuration names it
	}

	@Test
	void codeLinesAreSinewSlimsLength() {
		for (MethodUnit m : methods().values()) {
			assertEquals(m.facts().length(), shape(m).codeLines().length, m.signature());
		}
	}

	@Test
	void eachPieceOfEvidence() {
		Map<String, MethodUnit> ms = methods();
		Wiring wiring = new Wiring(List.of(new WiringFile("rest/api/x.GET.yml", WiringFile.Kind.COMPOSE_YAML, 2, 1, 0,
				List.of(new WiringFile.Ref("a.b.Logic", "wired")))));
		Map<String, Classification> c = ms.values().stream().collect(Collectors.toMap(MethodUnit::name,
				m -> Classification.of(shape(m), m.name(), wiring), (x, y) -> x));
		assertEquals(new Classification(Reading.OFFICEFLOOR_FUNCTION, "wired in rest/api/x.GET.yml"), c.get("wired"));
		assertEquals(new Classification(Reading.OFFICEFLOOR_FUNCTION, "@Parameter in"), c.get("service"));
		assertEquals(new Classification(Reading.OFFICEFLOOR_FUNCTION, "@Next"), c.get("next"));
		assertEquals(new Classification(Reading.FRAMEWORK_ENTRY_POINT, "@GetMapping"), c.get("get"));
		assertEquals(new Classification(Reading.FRAMEWORK_ENTRY_POINT, "public, in a @Service"), c.get("publicInService"));
		assertEquals(Reading.CONVENTIONAL, c.get("helper").reading()); // private: the project's own code calls it
		assertEquals(Reading.CONVENTIONAL, c.get("inner").reading()); // a nested class is not the @Service
	}

	@Test
	void aWiredClassMakesItsPublicMethodsFunctions() {
		Map<String, MethodUnit> ms = methods();
		Wiring wiring = new Wiring(List.of(new WiringFile("application.woof", WiringFile.Kind.WOOF, 3, 1, 0,
				List.of(new WiringFile.Ref("a.b.Logic", null)))));
		assertEquals(Reading.OFFICEFLOOR_FUNCTION,
				Classification.of(shape(ms.get("publicInService")), "publicInService", wiring).reading());
		assertEquals(Reading.CONVENTIONAL, Classification.of(shape(ms.get("helper")), "helper", wiring).reading());
	}

	@Test
	void anOutParameterAlone() {
		ParsedFile f = JavaFacts.parse("A.java", "class A { void f(Out<String> o) { } }", List.of(ShapeExtractor.INSTANCE));
		MethodUnit m = f.methods().get(0);
		assertEquals(new Classification(Reading.OFFICEFLOOR_FUNCTION, "Out<String> o"),
				Classification.of(shape(m), m.name(), Wiring.NONE));
	}
}
