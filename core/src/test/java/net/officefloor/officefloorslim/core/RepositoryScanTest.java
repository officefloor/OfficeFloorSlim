package net.officefloor.officefloorslim.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.officefloor.officefloorslim.core.Classification.Reading;
import net.officefloor.officefloorslim.core.RepositoryScan.Method;
import net.officefloor.officefloorslim.core.RepositoryScan.Options;
import net.officefloor.officefloorslim.core.RepositoryScan.Result;

/** A small repository scanned end to end: the wiring found, the method it wires classified, the long one priced. */
class RepositoryScanTest {

	@TempDir
	Path root;

	void write(String path, String text) throws IOException {
		Path p = root.resolve(path);
		Files.createDirectories(p.getParent());
		Files.writeString(p, text);
	}

	static String longMethod(String name) {
		StringBuilder b = new StringBuilder("	public int " + name + "(int a) {\n		int s = 0;\n");
		for (int i = 0; i < 40; i++) {
			b.append("		s += a * ").append(i).append(";\n");
		}
		return b.append("		return s;\n	}\n").toString();
	}

	void git(String... args) throws IOException, InterruptedException {
		List<String> cmd = new java.util.ArrayList<>(List.of("git", "-C", root.toString(), "-c", "user.name=t",
				"-c", "user.email=t@t", "-c", "commit.gpgsign=false"));
		cmd.addAll(List.of(args));
		assertEquals(0, new ProcessBuilder(cmd).redirectErrorStream(true).start().waitFor());
	}

	@Test
	void wiredLongMethodIsPricedAndClassified() throws Exception {
		write("src/main/java/a/Logic.java", "package a;\npublic class Logic {\n" + longMethod("service") + "\n	int small() { return 1; }\n}\n");
		write("src/main/resources/officefloor/rest/api/x.GET.yml", "service:\n  class: a.Logic\n");
		write("src/test/java/a/LogicTest.java", "package a;\nclass LogicTest {\n" + longMethod("test") + "}\n");
		git("init", "-q");
		git("add", "-A");
		git("commit", "-q", "-m", "one");

		Result r = RepositoryScan.scan(root, new Options(RepositoryScan.DEFAULT_INCLUDE, RepositoryScan.DEFAULT_EXCLUDE, 2, 500));
		assertEquals(0, r.unparsed().size());
		assertEquals(1, r.wiring().size());
		assertEquals(2, r.methods().size()); // the test source left out
		Method service = r.methods().stream().filter(m -> m.signature().startsWith("service")).findFirst().orElseThrow();
		assertEquals(Reading.OFFICEFLOOR_FUNCTION, service.classification().reading());
		assertTrue(service.longMethod());
		assertNotNull(service.pricing().officefloor());
		assertEquals(service.length(), service.growth().linesAdded()); // all of it written in the one commit
		assertEquals(1, service.growth().commits());
		assertNull(r.methods().stream().filter(m -> m.signature().startsWith("small")).findFirst().orElseThrow().pricing());
		assertEquals(1, r.measures().windowCommits());
		assertEquals(1, r.measures().touchingCommits());
		assertNotNull(r.commit());
	}

	@Test
	void growthCountsOnlyTheWindow() throws Exception {
		write("src/main/java/a/Logic.java", "package a;\npublic class Logic {\n" + longMethod("service") + "}\n");
		git("init", "-q");
		git("add", "-A");
		git("commit", "-q", "-m", "one");
		String text = Files.readString(root.resolve("src/main/java/a/Logic.java")).replace("		return s;\n",
				"		s += 1;\n		s += 2;\n		return s;\n");
		write("src/main/java/a/Logic.java", text);
		git("commit", "-q", "-am", "two");

		Result r = RepositoryScan.scan(root, new Options(RepositoryScan.DEFAULT_INCLUDE, RepositoryScan.DEFAULT_EXCLUDE, 1, 1));
		Method service = r.methods().get(0);
		assertEquals(2, service.growth().linesAdded()); // the window is the last commit only
		assertEquals(1, service.growth().commits());
		assertEquals(0, r.measures().touchingCommits()); // no wiring
	}

	@Test
	void noGrowthReadsNoHistory() throws Exception {
		write("src/main/java/a/Logic.java", "package a;\npublic class Logic {\n" + longMethod("service") + "}\n");
		git("init", "-q");
		git("add", "-A");
		git("commit", "-q", "-m", "one");
		Result r = RepositoryScan.scan(root, new Options(RepositoryScan.DEFAULT_INCLUDE, RepositoryScan.DEFAULT_EXCLUDE, 1, 0));
		assertNull(r.methods().get(0).growth()); // a shallow clone's one commit is not a window
		assertEquals(-1, r.measures().windowCommits());
		assertEquals(-1, r.measures().touchingCommits());
		assertNotNull(r.commit()); // the commit scanned is still recorded
	}

	@Test
	void outsideGitThereIsNoGrowth() throws Exception {
		write("src/main/java/a/Logic.java", "package a;\npublic class Logic {\n" + longMethod("service") + "}\n");
		Result r = RepositoryScan.scan(root, Options.defaults());
		assertNull(r.methods().get(0).growth());
		assertEquals(-1, r.measures().windowCommits());
		assertNull(r.commit());
	}
}
