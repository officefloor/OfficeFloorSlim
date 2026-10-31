package net.officefloor.intellij.flow.model;

import java.util.Objects;

/**
 * A single variable read or written by a function, as declared on its Java
 * method (via {@code @Val} / {@code In} / {@code Out} / {@code Var}).
 *
 * <p>
 * Two accesses refer to the <em>same</em> variable when their
 * {@link #getVariableName() variable name} matches. OfficeFloor derives that
 * name from the variable's type, optionally prefixed by a qualifier, so the name
 * is what links a producer in one function to a consumer in another.
 */
public final class VariableAccess {

	private final String variableName;
	private final String typeName;
	private final String qualifier;
	private final VariableDirection direction;

	public VariableAccess(String variableName, String typeName, String qualifier, VariableDirection direction) {
		this.variableName = Objects.requireNonNull(variableName, "variableName");
		this.typeName = typeName == null ? variableName : typeName;
		this.qualifier = qualifier;
		this.direction = Objects.requireNonNull(direction, "direction");
	}

	public String getVariableName() {
		return this.variableName;
	}

	public String getTypeName() {
		return this.typeName;
	}

	public String getQualifier() {
		return this.qualifier;
	}

	public VariableDirection getDirection() {
		return this.direction;
	}

	/** Short label for a chip: simple type name, with the qualifier when present. */
	public String displayLabel() {
		String simple = simpleType(this.typeName);
		return this.qualifier == null || this.qualifier.isEmpty() ? simple : simpleType(this.qualifier) + ":" + simple;
	}

	public String tooltip() {
		return (this.qualifier == null || this.qualifier.isEmpty() ? "" : "@" + this.qualifier + " ") + this.typeName;
	}

	public static String simpleType(String typeName) {
		if (typeName == null) {
			return "";
		}
		// Drop generics and package / nesting prefixes for a compact chip label.
		String name = typeName;
		int generic = name.indexOf('<');
		if (generic >= 0) {
			name = name.substring(0, generic);
		}
		int dollar = name.lastIndexOf('$');
		if (dollar >= 0) {
			name = name.substring(dollar + 1);
		}
		int dot = name.lastIndexOf('.');
		if (dot >= 0) {
			name = name.substring(dot + 1);
		}
		return name;
	}

	@Override
	public String toString() {
		return "VariableAccess[" + this.direction + " " + this.variableName + "]";
	}
}
