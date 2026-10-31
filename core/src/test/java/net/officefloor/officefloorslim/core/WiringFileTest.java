package net.officefloor.officefloorslim.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.officefloor.officefloorslim.core.WiringFile.Kind;
import net.officefloor.officefloorslim.core.WiringFile.Ref;

/** What each kind of OfficeFloor configuration wires, and its size. */
class WiringFileTest {

	@Test
	void composeYaml() {
		WiringFile f = WiringFile.read("src/main/resources/officefloor/rest/api/owners.GET.yml", """
				service:
				  class: a.OwnersLogic

				render:
				  class: a.View
				  method: draw
				  next: "#out"
				section:
				  source: a.Section
				""");
		assertEquals(Kind.COMPOSE_YAML, f.kind());
		assertEquals(8, f.lines());
		assertEquals(3, f.functions());
		assertEquals(1, f.edges());
		// with no method, the function's own name is the method
		assertEquals(List.of(new Ref("a.OwnersLogic", "service"), new Ref("a.View", "draw"), new Ref("a.Section", null)),
				f.refs());
	}

	@Test
	void woof() {
		WiringFile f = WiringFile.read("application.woof", """
				<woof>
				  <http-continuations>
				    <http-continuation path="/a" secure="false" x="1" y="2">
				      <procedure name="first"/>
				    </http-continuation>
				  </http-continuations>
				  <procedures>
				    <procedure name="first" resource="a.Logic" source="Class" procedure="first" x="3" y="4">
				      <next argument-type="">
				        <procedure name="second"/>
				      </next>
				    </procedure>
				    <procedure name="second" resource="script.js" source="JavaScript" procedure="second" x="5" y="6"/>
				  </procedures>
				  <sections>
				    <section name="s" source="CLASS" location="a.SectionClass" x="7" y="8"/>
				  </sections>
				</woof>
				""");
		assertEquals(Kind.WOOF, f.kind());
		assertEquals(3, f.functions());
		assertEquals(2, f.edges()); // the continuation to first, first's next to second
		assertEquals(List.of(new Ref("a.Logic", "first"), new Ref("a.SectionClass", null)), f.refs());
	}

	@Test
	void notConfiguration() {
		assertNull(WiringFile.read("application.yml", "server:\n  port: 8080\n"));
		assertNull(WiringFile.read("README.md", "# no"));
	}
}
