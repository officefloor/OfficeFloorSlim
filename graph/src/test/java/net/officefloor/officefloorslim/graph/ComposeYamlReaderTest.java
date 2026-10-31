package net.officefloor.officefloorslim.graph;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.Test;

/**
 * The compose YAML read outside the IDE: the functions, the class and method each is wired to,
 * and the flows between them, on the plugin's samples and on the edge cases of the rules.
 */
public class ComposeYamlReaderTest {

	static FlowGraph sample(String relative) throws IOException {
		Path samples = Path.of(System.getProperty("officefloorslim.samples"));
		String path = "src/main/resources/" + relative; // as a repository holds it
		return ComposeYamlReader.read(path,
				new String(Files.readAllBytes(samples.resolve(relative)), StandardCharsets.UTF_8));
	}

	static List<String> links(FlowGraph g) {
		return g.getAllLinks().stream()
				.map(l -> l.getFromId() + " -" + l.getKind() + ":" + l.getLabel() + "-> " + l.getToId())
				.collect(Collectors.toList());
	}

	@Test
	public void restEndpointWiresItsFunctions() throws IOException {
		FlowGraph g = sample("officefloor/rest/order/{id}.POST.yml");
		FlowNode validate = g.getNode("validate");
		assertEquals(NodeKind.PROCEDURE, validate.getKind());
		assertEquals("net.officefloor.sample.order.CreateOrderLogic", validate.getJavaClassName());
		assertEquals("validate", validate.getJavaMethodName());
		assertEquals("CreateOrderLogic#validate", validate.getSubtitle());
		assertEquals(NodeKind.HTTP_ENDPOINT, g.getNode("endpoint::POST /order/{id}").getKind());
		assertEquals(List.of(
				"endpoint::POST /order/{id} -ROUTE:-> service",
				"service -NEXT:next-> validate",
				"validate -FLOW:invalid-> rejectOrder",
				"validate -FLOW:valid-> persist",
				"persist -NEXT:next-> confirmation",
				"persist -ESCALATION:DuplicateOrderException-> rejectOrder",
				"service -ESCALATION:IOException (section)-> handleIoFailure"), links(g));
	}

	@Test
	public void activityHasNoEndpointAndAnExternalTarget() throws IOException {
		FlowGraph g = sample("activity/payment.yml");
		assertTrue(g.getNodes().stream().noneMatch(n -> n.getKind() == NodeKind.HTTP_ENDPOINT));
		assertEquals(NodeKind.SECTION, g.getNode("#notify").getKind());
		assertEquals("external input", g.getNode("#notify").getSubtitle());
		assertTrue(links(g).contains("receipt -NEXT:next-> #notify"));
	}

	@Test
	public void offsetsAreWhereTheKeysStart() {
		String yaml = "first:\n  class: a.B\n  next: second\nsecond:\n  class: a.C\n";
		FlowGraph g = ComposeYamlReader.read("x.yml", yaml);
		assertEquals(yaml.indexOf("first:"), g.getNode("first").getSourceOffset());
		assertEquals(yaml.indexOf("second:\n"), g.getNode("second").getSourceOffset());
		assertEquals(yaml.indexOf("next:"), g.getAllLinks().get(0).getSourceOffset());
	}

	@Test
	public void unresolvedTargetsAndEmptyValues() {
		FlowGraph g = ComposeYamlReader.read("x.yml", "a:\n  class:\n  procedure: p\n  next: missing\n");
		FlowNode a = g.getNode("a");
		assertNull(a.getJavaClassName()); // "class:" with no value is no class
		assertEquals("procedure: p", a.getSubtitle());
		assertEquals(NodeKind.UNKNOWN, g.getNode("missing").getKind());
	}

	@Test
	public void notComposeIsNoGraph() {
		assertNull(ComposeYamlReader.read("x.yml", "server:\n  port: 8080\n"));
		assertNull(ComposeYamlReader.read("x.yml", "- a\n- b\n"));
		assertNull(ComposeYamlReader.read("x.yml", ""));
	}

	@Test
	public void restFileNames() {
		assertEquals("GET /greeting/{name}",
				RestFileName.parse("src/main/resources/officefloor/rest/greeting/{name}.GET.yml").endpointLabel());
		assertEquals("GET /", RestFileName.parse("/abs/rest/index.GET.yml").endpointLabel());
		assertTrue(!RestFileName.parse("rest/path.yml").isEndpoint());
		assertNull(RestFileName.parse("src/main/resources/activity/payment.yml"));
	}
}
