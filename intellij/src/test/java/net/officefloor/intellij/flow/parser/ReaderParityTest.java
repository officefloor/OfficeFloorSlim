package net.officefloor.intellij.flow.parser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.jetbrains.yaml.psi.YAMLFile;

import com.intellij.psi.PsiFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import net.officefloor.officefloorslim.graph.ComposeYamlReader;
import net.officefloor.officefloorslim.graph.FlowGraph;
import net.officefloor.officefloorslim.graph.FlowLink;
import net.officefloor.officefloorslim.graph.FlowNode;

/**
 * The plugin's reader (IntelliJ's YAML PSI) and the scan's (SnakeYAML) give the same graph, to
 * the offset, on every sample and on the edge cases: what the plugin draws is what the
 * classification reads.
 */
public class ReaderParityTest extends BasePlatformTestCase {

	static final String[] EDGE_CASES = {
			"a:\n  class:\n  procedure: p\n  next: missing\n",
			"a:\n  class: x.Y\n  govern: [g1, g2]\n  outputs:\n    o: '#external'\n",
			"composition:\n  escalations:\n    java.lang.Exception: b\na:\n  source: ${Section}\n  location: l\nb:\n  method: m\n",
			"\"quoted key\":\n  class: \"x.Q\"\n  next: 'a'\na:\n  resource: r\n  procedure: p\n",
			"server:\n  port: 8080\n",
			"--- \nnot: [a]\n---\nf:\n  class: x.Z\n" };

	public void testSamples() throws IOException {
		Path samples = Path.of("samples").toAbsolutePath();
		List<Path> files;
		try (Stream<Path> s = Files.walk(samples)) {
			files = s.filter(p -> p.toString().endsWith(".yml")).sorted().toList();
		}
		assertFalse(files.isEmpty());
		for (Path f : files) {
			String relative = samples.relativize(f).toString().replace('\\', '/');
			same(relative, Files.readString(f, StandardCharsets.UTF_8));
		}
	}

	public void testEdgeCases() {
		for (int i = 0; i < EDGE_CASES.length; i++) {
			same("edge" + i + ".yml", EDGE_CASES[i]);
		}
	}

	void same(String relative, String text) {
		PsiFile psi = myFixture.addFileToProject(relative, text);
		assertInstanceOf(psi, YAMLFile.class);
		String path = psi.getVirtualFile().getPath();
		FlowGraph fromPsi = new ComposeYamlParser().parse((YAMLFile) psi, psi.getVirtualFile());
		FlowGraph fromSnake = ComposeYamlReader.read(path, text);
		assertEquals(relative, describe(fromSnake), describe(fromPsi));
	}

	static List<String> describe(FlowGraph g) {
		List<String> out = new ArrayList<>();
		if (g == null) {
			out.add("no graph");
			return out;
		}
		for (FlowNode n : g.getNodes()) {
			out.add("node " + n.getId() + " " + n.getKind() + " '" + n.getTitle() + "' '" + n.getSubtitle() + "' @"
					+ n.getSourceOffset() + " " + n.getJavaClassName() + "#" + n.getJavaMethodName());
		}
		for (FlowLink l : g.getAllLinks()) {
			out.add("link " + l.getFromId() + " " + l.getKind() + " '" + l.getLabel() + "' " + l.getToId() + " @"
					+ l.getSourceOffset());
		}
		return out;
	}
}
