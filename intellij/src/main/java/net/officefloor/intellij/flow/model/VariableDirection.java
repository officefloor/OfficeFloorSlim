package net.officefloor.intellij.flow.model;

/** Direction a function accesses a variable. */
public enum VariableDirection {

	/** Reads the variable value ({@code @Val T} or {@code In<T>}). */
	IN,

	/** Writes the variable value ({@code Out<T>}). */
	OUT,

	/** Both reads and writes ({@code Var<T>}). */
	IN_OUT,

	/**
	 * Direction not known. ProcedureType metadata flattens all variables and does
	 * not record In/Out, so a variable known only from the compiled type (e.g. a
	 * polyglot procedure with no readable Java source) has unknown direction.
	 */
	UNKNOWN;

	public boolean reads() {
		return this == IN || this == IN_OUT;
	}

	public boolean writes() {
		return this == OUT || this == IN_OUT;
	}
}
