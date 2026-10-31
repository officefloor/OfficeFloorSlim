package net.officefloor.intellij.flow.model;

/**
 * An HTTP request value consumed by a function, declared on its Java method via
 * either an OfficeFloor web annotation ({@code @HttpPathParameter},
 * {@code @HttpQueryParameter}, {@code @HttpHeaderParameter},
 * {@code @HttpCookieParameter}, {@code @HttpContentParameter}, {@code @HttpObject})
 * or the equivalent Spring MVC annotation ({@code @PathVariable},
 * {@code @RequestParam}, {@code @RequestHeader}, {@code @CookieValue},
 * {@code @RequestBody}, {@code @RequestPart}).
 *
 * <p>
 * Unlike a {@link VariableAccess}, an HTTP input has no producing function: it
 * originates from the request, so it is shown purely as an input chip and never
 * participates in producer/consumer linking.
 */
public final class HttpInput {

	/** Where in the request the value comes from. */
	public enum Kind {
		PATH("path"), QUERY("query"), HEADER("header"), COOKIE("cookie"), FORM("form"), BODY("body");

		private final String label;

		Kind(String label) {
			this.label = label;
		}

		public String label() {
			return this.label;
		}
	}

	/** Which annotation family declared the input. */
	public enum Source {
		SPRING, OFFICEFLOOR
	}

	private final Kind kind;
	private final String name;
	private final String typeName;
	private final Source source;
	private final String annotation;

	public HttpInput(Kind kind, String name, String typeName, Source source, String annotation) {
		this.kind = kind;
		this.name = name == null ? "" : name;
		this.typeName = typeName;
		this.source = source;
		this.annotation = annotation;
	}

	public Kind getKind() {
		return this.kind;
	}

	public String getName() {
		return this.name;
	}

	public String getTypeName() {
		return this.typeName;
	}

	public Source getSource() {
		return this.source;
	}

	/** Compact chip label: a kind glyph plus the parameter name (or the body type). */
	public String chipLabel() {
		switch (this.kind) {
		case PATH:
			return "/" + this.name;
		case QUERY:
			return "?" + this.name;
		case HEADER:
			return "H " + this.name;
		case COOKIE:
			return "C " + this.name;
		case FORM:
			return "F " + this.name;
		case BODY:
		default:
			return "{} " + VariableAccess.simpleType(this.typeName);
		}
	}

	public String tooltip() {
		String src = this.source == Source.SPRING ? "Spring" : "OfficeFloor";
		String named = this.name.isEmpty() ? "" : " '" + this.name + "'";
		return "HTTP " + this.kind.label() + named + " (" + VariableAccess.simpleType(this.typeName) + "), " + src + " @"
				+ this.annotation;
	}

	@Override
	public String toString() {
		return "HttpInput[" + this.kind + " " + this.name + "]";
	}
}
