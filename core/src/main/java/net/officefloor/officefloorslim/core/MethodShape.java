package net.officefloor.officefloorslim.core;

import java.util.List;

/**
 * What OfficeFloorSlim reads about a method beyond SinewSlim's shared facts: what it declares
 * and what marks it, as written. Read by {@link ShapeExtractor} through SinewSlim's extension
 * point, so SinewSlim's facts and results are untouched by it.
 *
 * @param className          the declaring class's binary name ({@code a.b.Outer$Inner}), as
 *                           OfficeFloor's configuration names it; null inside an anonymous or
 *                           local class
 * @param methodAnnotations  the annotations on the method itself, by name as written
 * @param typeAnnotations    the annotations on the class that declares it (not on the classes
 *                           around that: a stereotype applies to the class it is on)
 * @param params             its parameters, in order
 * @param returnType         its return type as written; {@code void} for none, empty for a
 *                           constructor
 * @param declared           the exceptions it declares ({@code throws}), as written
 * @param caught             the exception types its catch clauses name, as written, in order
 * @param throwsStatements   its {@code throw} statements
 * @param publicMethod       whether it is public
 * @param staticMethod       whether it is static
 * @param codeLines          the lines of the declaration that hold code (a token other than a
 *                           comment), in order: what growth is counted on
 */
public record MethodShape(String className, List<String> methodAnnotations, List<String> typeAnnotations,
		List<Param> params, String returnType, List<String> declared, List<String> caught, int throwsStatements,
		boolean publicMethod, boolean staticMethod, int[] codeLines) {

	/**
	 * A parameter.
	 *
	 * @param name        its name
	 * @param type        its type as written ({@code Out<String>})
	 * @param annotations its annotations, by name as written
	 */
	public record Param(String name, String type, List<String> annotations) {

		public Param {
			annotations = List.copyOf(annotations);
		}

		/** The type's simple name without its type arguments: {@code Out} for {@code a.b.Out<String>}. */
		public String rawSimpleType() {
			String t = type;
			int lt = t.indexOf('<');
			if (lt >= 0) {
				t = t.substring(0, lt);
			}
			return t.substring(t.lastIndexOf('.') + 1).trim();
		}
	}

	public MethodShape {
		methodAnnotations = List.copyOf(methodAnnotations);
		typeAnnotations = List.copyOf(typeAnnotations);
		params = List.copyOf(params);
		declared = List.copyOf(declared);
		caught = List.copyOf(caught);
		codeLines = codeLines.clone();
	}

	/** Whether the method itself, or its declaring class, carries an annotation of this simple name. */
	public boolean annotated(String simpleName) {
		return has(methodAnnotations, simpleName) || has(typeAnnotations, simpleName);
	}

	static boolean has(List<String> annotations, String simpleName) {
		for (String a : annotations) {
			if (a.equals(simpleName) || a.endsWith("." + simpleName)) {
				return true;
			}
		}
		return false;
	}
}
