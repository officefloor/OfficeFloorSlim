package net.officefloor.officefloorslim.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.javaparser.ast.body.BodyDeclaration;

import net.officefloor.sinewslim.core.Assessment.Status;
import net.officefloor.sinewslim.core.Assessor;
import net.officefloor.sinewslim.facts.FactKey;
import net.officefloor.sinewslim.facts.MethodFacts;
import net.officefloor.sinewslim.javaparser.JavaFacts.Extractor;
import net.officefloor.sinewslim.scan.Glob;
import net.officefloor.sinewslim.scan.Scanner;
import net.officefloor.sinewslim.scan.Scanner.FileResult;

/**
 * The SinewSlim libraries as this project uses them: a repository scanned, its Java read with an
 * extractor of this project's own beside the shared facts, and each method given SinewSlim's
 * status. Proves the wiring (the composite build locally, the coordinates elsewhere), not any
 * algorithm.
 */
class SinewSlimLibrariesTest {

	static final FactKey<Integer> ANNOTATIONS = FactKey.of("officefloorslim.test.annotations");

	static final Extractor<Integer> COUNT_ANNOTATIONS = new Extractor<>() {
		public FactKey<Integer> key() {
			return ANNOTATIONS;
		}

		public Integer extract(BodyDeclaration<?> callable) {
			return callable.getAnnotations().size();
		}
	};

	record Read(String name, Optional<Integer> annotations, Status status) {
	}

	@TempDir
	Path root;

	@Test
	void scansReadsAndAssesses() throws IOException {
		assertEquals(3, MethodFacts.SCHEMA);
		Files.createDirectories(root.resolve("src"));
		Files.writeString(root.resolve("src/A.java"), """
				class A {
					@Deprecated @SuppressWarnings("x")
					int f(int a) { return a + 1; }
				}
				""");
		List<FileResult<List<Read>>> rs = Scanner.scan(root, List.of(new Glob("**/*.java")), List.of(), 1,
				Scanner.javaMethods(List.of(COUNT_ANNOTATIONS), (path, m) -> new Read(m.name(),
						m.extras().get(ANNOTATIONS), Assessor.DEFAULTS.assess(m.facts()).status())));
		assertEquals(1, rs.size());
		assertTrue(rs.get(0).read(), rs.get(0).problem());
		assertEquals(List.of(new Read("f", Optional.of(2), Status.UNDER_CAP)), rs.get(0).result());
	}
}
