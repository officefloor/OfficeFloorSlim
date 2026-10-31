package net.officefloor.officefloorslim.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import picocli.CommandLine;

/** The scan command's reports: the JSON contract and the Markdown for reading. */
class ScanCommandTest {

	@TempDir
	Path root;

	void fixture() throws IOException {
		StringBuilder b = new StringBuilder("package a;\npublic class Logic {\n	public int service(int a) {\n		int s = 0;\n");
		for (int i = 0; i < 40; i++) {
			b.append("		s += a * ").append(i).append(";\n");
		}
		b.append("		return s;\n	}\n}\n");
		Files.createDirectories(root.resolve("src/main/java/a"));
		Files.writeString(root.resolve("src/main/java/a/Logic.java"), b);
		Files.createDirectories(root.resolve("src/main/resources/officefloor/rest"));
		Files.writeString(root.resolve("src/main/resources/officefloor/rest/x.GET.yml"), "service:\n  class: a.Logic\n");
	}

	int run(String... args) {
		return new CommandLine(new OfficeFloorSlim()).execute(args);
	}

	@Test
	void json() throws IOException {
		fixture();
		Path out = root.resolve("scan.json");
		assertEquals(0, run("scan", "--repo", root.toString(), "--out", out.toString()));
		JsonNode j = new ObjectMapper().readTree(out.toFile());
		assertEquals("officefloorslim-scan/2", j.get("schema").asText());
		assertEquals(3, j.get("facts_schema").asInt());
		assertEquals("officefloorslim-split/1", j.get("officefloor_reading").asText());
		assertEquals(1, j.get("methods_by_reading").get("officefloor_function").asInt());
		assertEquals(1, j.get("wiring").get("files").asInt());
		assertTrue(j.get("bar").has("go"));
		JsonNode m = j.get("methods").get(0);
		assertEquals("officefloor_function", m.get("reading").asText());
		assertEquals("wired in src/main/resources/officefloor/rest/x.GET.yml", m.get("evidence").asText());
		for (String field : new String[] { "sinewslim", "officefloor", "category", "migration", "growth" }) {
			assertTrue(m.has(field), field);
		}
	}

	@Test
	void markdown() throws IOException {
		fixture();
		Path out = root.resolve("scan.md");
		assertEquals(0, run("scan", "--repo", root.toString(), "--format", "markdown", "--out", out.toString()));
		String md = Files.readString(out);
		assertTrue(md.contains("## The go/no-go bar"), md);
		assertTrue(md.contains("## Category 3: only splits under function injection"), md);
	}

	@Test
	void unknownFormat() {
		assertEquals(2, run("scan", "--repo", root.toString(), "--format", "xml"));
	}
}
