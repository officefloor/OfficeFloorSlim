package net.officefloor.intellij.flow.parser;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import net.officefloor.intellij.flow.model.VariableAccess;
import net.officefloor.intellij.flow.model.VariableDirection;

/**
 * Loads OfficeFloor's compiled {@code ProcedureType} for a function and converts
 * it to a {@link FunctionMetaData}.
 *
 * <p>
 * This runs OfficeFloor's compiler, so it works entirely by reflection over a
 * {@link ClassLoader} built from the user's project classpath — the plugin does
 * <em>not</em> bundle OfficeFloor. That way the project's own OfficeFloor version
 * and its service-loaded procedure sources / dependency manufacturers are used,
 * and class identity stays consistent. Anything missing (not an OfficeFloor
 * project, classes not compiled, a load issue) throws and the caller falls back
 * to reading the Java source.
 *
 * <p>
 * Note: {@code ProcedureType} flattens variables to name + value-type and does
 * <em>not</em> record In/Out direction, so every variable here has
 * {@link VariableDirection#UNKNOWN}; the resolver recovers direction from source.
 */
final class ProcedureTypeMetaData {

	private static final String OFFICE_FLOOR_COMPILER = "net.officefloor.compile.OfficeFloorCompiler";
	private static final String PROCEDURE_EMPLOYER = "net.officefloor.activity.procedure.build.ProcedureEmployer";
	private static final String PROCEDURE_LOADER = "net.officefloor.activity.procedure.ProcedureLoader";
	private static final String PROCEDURE_TYPE = "net.officefloor.activity.procedure.ProcedureType";
	private static final String PROCEDURE_VARIABLE_TYPE = "net.officefloor.activity.procedure.ProcedureVariableType";
	private static final String PROPERTY_LIST = "net.officefloor.compile.properties.PropertyList";
	private static final String PROPERTY_LIST_IMPL = "net.officefloor.compile.impl.properties.PropertyListImpl";

	private ProcedureTypeMetaData() {
	}

	/**
	 * A reusable ProcedureLoader bound to a project classloader. Creating the
	 * OfficeFloor compiler is relatively expensive, so one loader serves all the
	 * procedures in a file.
	 */
	static final class Loader {
		private final ClassLoader classLoader;
		private final Object procedureLoader;
		private final Method loadMethod;
		private final Class<?> propertyListImplClass;

		private Loader(ClassLoader classLoader, Object procedureLoader, Method loadMethod,
				Class<?> propertyListImplClass) {
			this.classLoader = classLoader;
			this.procedureLoader = procedureLoader;
			this.loadMethod = loadMethod;
			this.propertyListImplClass = propertyListImplClass;
		}
	}

	/**
	 * @return a reusable loader, or {@code null} if OfficeFloor's compiler is not
	 *         on the given classloader (i.e. not an OfficeFloor project).
	 */
	static Loader newLoader(ClassLoader projectClassLoader) throws Exception {
		Class<?> compilerClass = projectClassLoader.loadClass(OFFICE_FLOOR_COMPILER);
		Object compiler = compilerClass.getMethod("newOfficeFloorCompiler", ClassLoader.class).invoke(null,
				projectClassLoader);

		Class<?> employerClass = projectClassLoader.loadClass(PROCEDURE_EMPLOYER);
		Object procedureLoader = employerClass.getMethod("employProcedureLoader", compilerClass).invoke(null, compiler);

		Class<?> propertyListClass = projectClassLoader.loadClass(PROPERTY_LIST);
		Class<?> propertyListImplClass = projectClassLoader.loadClass(PROPERTY_LIST_IMPL);
		Class<?> loaderClass = projectClassLoader.loadClass(PROCEDURE_LOADER);
		Method loadMethod = loaderClass.getMethod("loadProcedureType", String.class, String.class, String.class,
				propertyListClass);

		return new Loader(projectClassLoader, procedureLoader, loadMethod, propertyListImplClass);
	}

	/**
	 * @return the metadata, or {@code null} if the ProcedureType could not be
	 *         loaded (class not compiled, a load issue, etc.).
	 */
	static FunctionMetaData load(Loader loader, String resource, String sourceName, String procedureName)
			throws Exception {
		if (resource == null || resource.isEmpty()) {
			return null;
		}
		ClassLoader projectClassLoader = loader.classLoader;
		Object properties = loader.propertyListImplClass.getConstructor().newInstance();
		Object type = loader.loadMethod.invoke(loader.procedureLoader, resource, sourceName, procedureName, properties);
		if (type == null) {
			return null;
		}

		Class<?> ptClass = projectClassLoader.loadClass(PROCEDURE_TYPE);
		Class<?> parameterType = (Class<?>) ptClass.getMethod("getParameterType").invoke(type);
		Class<?> nextType = (Class<?>) ptClass.getMethod("getNextArgumentType").invoke(type);
		Object[] variableTypes = (Object[]) ptClass.getMethod("getVariableTypes").invoke(type);

		Class<?> pvtClass = projectClassLoader.loadClass(PROCEDURE_VARIABLE_TYPE);
		Method getVariableName = pvtClass.getMethod("getVariableName");
		Method getVariableType = pvtClass.getMethod("getVariableType");

		List<VariableAccess> variables = new ArrayList<>();
		for (Object variableType : variableTypes) {
			String name = (String) getVariableName.invoke(variableType);
			String valueType = (String) getVariableType.invoke(variableType);
			variables.add(new VariableAccess(name, valueType, qualifierOf(name, valueType), VariableDirection.UNKNOWN));
		}

		return new FunctionMetaData(variables, typeName(nextType), typeName(parameterType));
	}

	/** The variable name is {@code (qualifier + "-")? + type}; recover the qualifier for display. */
	private static String qualifierOf(String variableName, String valueType) {
		if (variableName == null || valueType == null) {
			return null;
		}
		String suffix = "-" + valueType;
		if (variableName.length() > suffix.length() && variableName.endsWith(suffix)) {
			return variableName.substring(0, variableName.length() - suffix.length());
		}
		return null;
	}

	private static String typeName(Class<?> type) {
		if (type == null) {
			return null;
		}
		String canonical = type.getCanonicalName();
		return canonical != null ? canonical : type.getName();
	}
}
