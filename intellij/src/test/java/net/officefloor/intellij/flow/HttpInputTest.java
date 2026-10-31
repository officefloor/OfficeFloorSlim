package net.officefloor.intellij.flow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import net.officefloor.intellij.flow.model.HttpInput;

/** Tests the HTTP input chip label and tooltip formatting (the pure model piece). */
public class HttpInputTest {

	@Test
	public void pathLabel() {
		assertEquals("/id", of(HttpInput.Kind.PATH, "id", "java.lang.String").chipLabel());
	}

	@Test
	public void queryLabel() {
		assertEquals("?page", spring(HttpInput.Kind.QUERY, "page", "int").chipLabel());
	}

	@Test
	public void headerLabel() {
		assertEquals("H Authorization", of(HttpInput.Kind.HEADER, "Authorization", "java.lang.String").chipLabel());
	}

	@Test
	public void bodyLabelUsesSimpleType() {
		assertEquals("{} OrderRequest", spring(HttpInput.Kind.BODY, "", "com.example.OrderRequest").chipLabel());
	}

	@Test
	public void tooltipNamesSourceTypeAndAnnotation() {
		String tooltip = of(HttpInput.Kind.HEADER, "Authorization", "java.lang.String").tooltip();
		assertTrue(tooltip, tooltip.contains("OfficeFloor"));
		assertTrue(tooltip, tooltip.contains("@HttpHeaderParameter"));
		assertTrue(tooltip, tooltip.contains("Authorization"));
		assertTrue(tooltip, tooltip.contains("String"));
	}

	@Test
	public void tooltipNamesSpringSource() {
		String tooltip = spring(HttpInput.Kind.PATH, "id", "java.lang.String").tooltip();
		assertTrue(tooltip, tooltip.contains("Spring"));
		assertTrue(tooltip, tooltip.contains("@PathVariable"));
	}

	private static HttpInput of(HttpInput.Kind kind, String name, String type) {
		String annotation = kind == HttpInput.Kind.PATH ? "HttpPathParameter"
				: kind == HttpInput.Kind.QUERY ? "HttpQueryParameter"
						: kind == HttpInput.Kind.HEADER ? "HttpHeaderParameter" : "HttpObject";
		return new HttpInput(kind, name, type, HttpInput.Source.OFFICEFLOOR, annotation);
	}

	private static HttpInput spring(HttpInput.Kind kind, String name, String type) {
		String annotation = kind == HttpInput.Kind.PATH ? "PathVariable"
				: kind == HttpInput.Kind.QUERY ? "RequestParam"
						: kind == HttpInput.Kind.BODY ? "RequestBody" : "RequestHeader";
		return new HttpInput(kind, name, type, HttpInput.Source.SPRING, annotation);
	}
}
