package net.officefloor.officefloorslim.core;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import net.officefloor.officefloorslim.graph.ComposeYamlReader;
import net.officefloor.officefloorslim.graph.FlowGraph;
import net.officefloor.officefloorslim.graph.FlowLink;
import net.officefloor.officefloorslim.graph.FlowNode;
import net.officefloor.officefloorslim.graph.LinkKind;
import net.officefloor.officefloorslim.graph.NodeKind;

/**
 * One OfficeFloor configuration file and what it wires: the classes (and, where it says, the
 * methods) it makes into functions, and its size, for the wiring measures.
 *
 * @param path      the file, relative to the repository root
 * @param kind      what it is
 * @param lines     its non-blank lines
 * @param functions the functions it declares
 * @param edges     the flows between them it declares ({@code next}, outputs, escalations)
 * @param refs      the classes and methods it wires
 */
public record WiringFile(String path, Kind kind, int lines, int functions, int edges, List<Ref> refs) {

	public enum Kind {
		/** Compose / procedure YAML: a REST endpoint or an activity. */
		COMPOSE_YAML,
		/** A {@code .woof} file. */
		WOOF,
		/** An {@code application.officefloor} file. */
		OFFICEFLOOR
	}

	/**
	 * A class a configuration wires, and the method when it names one.
	 *
	 * @param className the class's binary name, as written
	 * @param method    the method; null when the whole class is wired (each of its methods a function)
	 */
	public record Ref(String className, String method) {
	}

	public WiringFile {
		refs = List.copyOf(refs);
	}

	/** Whether a file at this path is one {@link #read} reads. */
	public static boolean candidate(String path) {
		String p = path.toLowerCase(Locale.ROOT);
		return p.endsWith(".yml") || p.endsWith(".yaml") || p.endsWith(".woof") || p.endsWith(".officefloor");
	}

	/**
	 * @return the file's wiring, or null when it is not an OfficeFloor configuration (most YAML
	 *         files are not)
	 * @throws RuntimeException when the file does not parse
	 */
	public static WiringFile read(String path, String source) {
		String p = path.toLowerCase(Locale.ROOT);
		if (p.endsWith(".woof")) {
			return xml(path, source, Kind.WOOF);
		}
		if (p.endsWith(".officefloor")) {
			return xml(path, source, Kind.OFFICEFLOOR);
		}
		if (!p.endsWith(".yml") && !p.endsWith(".yaml")) {
			return null;
		}
		FlowGraph g = ComposeYamlReader.read(path, source);
		if (g == null) {
			return null;
		}
		List<Ref> refs = new ArrayList<>();
		int functions = 0;
		for (FlowNode n : g.getNodes()) {
			if (n.getSourceOffset() < 0) {
				continue; // an endpoint, an external input or an unresolved target: not declared here
			}
			functions++;
			if (n.getJavaClassName() != null) {
				// a function with a class and no method runs the method of the function's own name
				String method = n.getKind() == NodeKind.SECTION ? null
						: n.getJavaMethodName() != null ? n.getJavaMethodName() : n.getId();
				refs.add(new Ref(n.getJavaClassName(), method));
			}
		}
		int edges = 0;
		for (FlowLink l : g.getAllLinks()) {
			if (l.getKind() != LinkKind.ROUTE) {
				edges++;
			}
		}
		return new WiringFile(path, Kind.COMPOSE_YAML, nonBlank(source), functions, edges, refs);
	}

	/**
	 * A {@code .woof} or {@code application.officefloor}. A declaration carries its place on the
	 * graphical editor's canvas ({@code x}, {@code y}); a reference to one does not. A procedure
	 * declared names its class ({@code resource}) and method ({@code procedure}) when its source is
	 * a class; a section of
	 * class source names its class ({@code location}), every method of which is a function. Every
	 * reference (an element with a {@code name} and no place, inside a declaration: a
	 * {@code next}, an output, an escalation, a continuation's target) is a flow.
	 */
	static WiringFile xml(String path, String source, Kind kind) {
		Document doc;
		try {
			DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
			f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
			f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			f.setExpandEntityReferences(false);
			DocumentBuilder b = f.newDocumentBuilder();
			b.setErrorHandler(null);
			doc = b.parse(new InputSource(new StringReader(source)));
		} catch (Exception e) {
			throw new IllegalArgumentException("not XML: " + e.getMessage(), e);
		}
		List<Ref> refs = new ArrayList<>();
		int functions = 0, edges = 0;
		NodeList all = doc.getElementsByTagName("*");
		for (int i = 0; i < all.getLength(); i++) {
			Element e = (Element) all.item(i);
			String name = e.getTagName().toLowerCase(Locale.ROOT);
			boolean declared = e.hasAttribute("x");
			if (name.equals("procedure") && e.hasAttribute("resource")) {
				functions++;
				String method = e.getAttribute("procedure"), procedureSource = e.getAttribute("source");
				if (procedureSource.isEmpty() || procedureSource.equalsIgnoreCase("Class")) {
					refs.add(new Ref(e.getAttribute("resource"), method.isEmpty() ? null : method));
				} // else a procedure of another source (a script): its resource is not a class
			} else if (name.equals("section") && e.hasAttribute("location") && classSource(e.getAttribute("source"))) {
				functions++;
				refs.add(new Ref(e.getAttribute("location"), null));
			} else if (!declared && e.hasAttribute("name") && insideDeclaration(e)) {
				edges++;
			}
		}
		return new WiringFile(path, kind, nonBlank(source), functions, edges, refs);
	}

	private static boolean classSource(String source) {
		return "CLASS".equalsIgnoreCase(source) || source.endsWith("ClassSectionSource");
	}

	private static boolean insideDeclaration(Element e) {
		for (org.w3c.dom.Node p = e.getParentNode(); p instanceof Element pe; p = pe.getParentNode()) {
			if (pe.hasAttribute("x")) {
				return true;
			}
		}
		return false;
	}

	static int nonBlank(String source) {
		return (int) source.lines().filter(l -> !l.isBlank()).count();
	}
}
