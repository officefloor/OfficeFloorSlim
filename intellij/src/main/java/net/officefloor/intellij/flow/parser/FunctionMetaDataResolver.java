package net.officefloor.intellij.flow.parser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.officefloor.intellij.flow.model.VariableAccess;
import net.officefloor.intellij.flow.model.VariableDirection;

/**
 * Merges the authoritative compiled {@link FunctionMetaData} (from
 * {@code ProcedureType}) with the Java-source metadata.
 *
 * <p>
 * ProcedureType is preferred for the {@code @Parameter} type, the return type
 * and the set of variables (names + types) — it is authoritative and also covers
 * custom / polyglot procedures. The Java source supplies only the In/Out
 * direction, which the compiled type discards, matched by variable name, and the
 * HTTP inputs, which are read directly from the parameter annotations. When
 * ProcedureType is unavailable the source metadata is used in full.
 */
final class FunctionMetaDataResolver {

	private FunctionMetaDataResolver() {
	}

	static FunctionMetaData merge(FunctionMetaData procedureType, FunctionMetaData source) {
		FunctionMetaData src = source == null ? FunctionMetaData.empty() : source;
		if (procedureType == null || procedureType.isEmpty()) {
			return src; // full fallback to the Java source
		}

		String parameterType = procedureType.getParameterType() != null ? procedureType.getParameterType()
				: src.getParameterType();
		String returnType = procedureType.getReturnType() != null ? procedureType.getReturnType()
				: src.getReturnType();

		Map<String, VariableDirection> directionByName = new HashMap<>();
		for (VariableAccess access : src.getVariables()) {
			directionByName.put(access.getVariableName(), access.getDirection());
		}

		List<VariableAccess> variables = new ArrayList<>();
		for (VariableAccess variable : procedureType.getVariables()) {
			VariableDirection direction = directionByName.getOrDefault(variable.getVariableName(),
					VariableDirection.UNKNOWN);
			variables.add(new VariableAccess(variable.getVariableName(), variable.getTypeName(),
					variable.getQualifier(), direction));
		}

		// HTTP inputs are read directly from the parameter annotations, so they
		// always come from the Java source (ProcedureType does not label them).
		return new FunctionMetaData(variables, src.getHttpInputs(), returnType, parameterType);
	}
}
