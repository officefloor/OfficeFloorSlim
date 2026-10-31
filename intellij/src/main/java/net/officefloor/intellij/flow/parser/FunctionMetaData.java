package net.officefloor.intellij.flow.parser;

import java.util.ArrayList;
import java.util.List;

import net.officefloor.intellij.flow.model.HttpInput;
import net.officefloor.intellij.flow.model.VariableAccess;

/**
 * The resolved metadata of a function: the variables it accesses, the type of
 * its {@code @Parameter} (received from the previous function) and its non-void
 * return type (passed to the {@code next} function).
 *
 * <p>
 * Produced either from OfficeFloor's compiled {@code ProcedureType} or by
 * reading the Java source, and merged by {@link FunctionMetaDataResolver}.
 */
public final class FunctionMetaData {

	private final List<VariableAccess> variables;
	private final List<HttpInput> httpInputs;
	private final String returnType;
	private final String parameterType;

	public FunctionMetaData(List<VariableAccess> variables, String returnType, String parameterType) {
		this(variables, new ArrayList<>(), returnType, parameterType);
	}

	public FunctionMetaData(List<VariableAccess> variables, List<HttpInput> httpInputs, String returnType,
			String parameterType) {
		this.variables = variables == null ? new ArrayList<>() : variables;
		this.httpInputs = httpInputs == null ? new ArrayList<>() : httpInputs;
		this.returnType = returnType;
		this.parameterType = parameterType;
	}

	public static FunctionMetaData empty() {
		return new FunctionMetaData(new ArrayList<>(), null, null);
	}

	public List<VariableAccess> getVariables() {
		return this.variables;
	}

	public List<HttpInput> getHttpInputs() {
		return this.httpInputs;
	}

	public String getReturnType() {
		return this.returnType;
	}

	public String getParameterType() {
		return this.parameterType;
	}

	public boolean isEmpty() {
		return this.variables.isEmpty() && this.httpInputs.isEmpty() && this.returnType == null
				&& this.parameterType == null;
	}
}
