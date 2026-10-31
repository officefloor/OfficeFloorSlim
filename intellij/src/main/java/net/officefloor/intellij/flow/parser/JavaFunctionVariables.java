package net.officefloor.intellij.flow.parser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiType;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiUtil;

import net.officefloor.intellij.flow.model.HttpInput;
import net.officefloor.intellij.flow.model.VariableAccess;
import net.officefloor.intellij.flow.model.VariableDirection;

/**
 * Extracts the OfficeFloor variables (reads/writes) declared on a function's
 * Java method, by inspecting its parameters for {@code @Val} / {@code In} /
 * {@code Out} / {@code Var}.
 *
 * <p>
 * Isolated in its own class (referencing the Java PSI) so callers can guard the
 * call with {@code catch (Throwable)}; on IDEs without the Java module this class
 * fails to load and no variables are produced.
 *
 * <p>
 * The variable identity string mirrors OfficeFloor's
 * {@code VariableManagedObjectSource.name(qualifier, type)}:
 * {@code (qualifier == null ? "" : qualifier + "-") + normalisedType}. Two
 * functions share a variable iff these strings match.
 */
public final class JavaFunctionVariables {

	private static final String VAL = "net.officefloor.plugin.variable.Val";
	private static final String IN = "net.officefloor.plugin.variable.In";
	private static final String OUT = "net.officefloor.plugin.variable.Out";
	private static final String VAR = "net.officefloor.plugin.variable.Var";

	private static final String QUALIFIER_META = "net.officefloor.plugin.clazz.Qualifier";
	private static final String QUALIFIED = "net.officefloor.plugin.clazz.Qualified";
	private static final String PARAMETER = "net.officefloor.plugin.section.clazz.Parameter";

	private static final String HTTP_OBJECT = "net.officefloor.web.HttpObject";

	/** OfficeFloor web parameter annotations, by FQN. */
	private static final Map<String, HttpInput.Kind> OFFICEFLOOR_HTTP = new LinkedHashMap<>();
	/** Spring MVC request-binding annotations, by FQN. */
	private static final Map<String, HttpInput.Kind> SPRING_HTTP = new LinkedHashMap<>();
	static {
		OFFICEFLOOR_HTTP.put("net.officefloor.web.HttpPathParameter", HttpInput.Kind.PATH);
		OFFICEFLOOR_HTTP.put("net.officefloor.web.HttpQueryParameter", HttpInput.Kind.QUERY);
		OFFICEFLOOR_HTTP.put("net.officefloor.web.HttpHeaderParameter", HttpInput.Kind.HEADER);
		OFFICEFLOOR_HTTP.put("net.officefloor.web.HttpCookieParameter", HttpInput.Kind.COOKIE);
		OFFICEFLOOR_HTTP.put("net.officefloor.web.HttpContentParameter", HttpInput.Kind.FORM);
		OFFICEFLOOR_HTTP.put(HTTP_OBJECT, HttpInput.Kind.BODY);

		SPRING_HTTP.put("org.springframework.web.bind.annotation.PathVariable", HttpInput.Kind.PATH);
		SPRING_HTTP.put("org.springframework.web.bind.annotation.RequestParam", HttpInput.Kind.QUERY);
		SPRING_HTTP.put("org.springframework.web.bind.annotation.RequestHeader", HttpInput.Kind.HEADER);
		SPRING_HTTP.put("org.springframework.web.bind.annotation.CookieValue", HttpInput.Kind.COOKIE);
		SPRING_HTTP.put("org.springframework.web.bind.annotation.RequestPart", HttpInput.Kind.FORM);
		SPRING_HTTP.put("org.springframework.web.bind.annotation.RequestBody", HttpInput.Kind.BODY);
	}

	private static final Map<String, String> BOXED = new HashMap<>();
	static {
		BOXED.put("boolean", "java.lang.Boolean");
		BOXED.put("byte", "java.lang.Byte");
		BOXED.put("short", "java.lang.Short");
		BOXED.put("char", "java.lang.Character");
		BOXED.put("int", "java.lang.Integer");
		BOXED.put("long", "java.lang.Long");
		BOXED.put("float", "java.lang.Float");
		BOXED.put("double", "java.lang.Double");
	}

	private JavaFunctionVariables() {
	}

	/** Reads the function metadata (with In/Out direction) from the Java source. */
	public static FunctionMetaData extract(Project project, String className, String methodName) {
		if (className == null || className.isEmpty()) {
			return FunctionMetaData.empty();
		}
		GlobalSearchScope scope = GlobalSearchScope.allScope(project);
		JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
		PsiClass psiClass = facade.findClass(className, scope);
		if (psiClass == null && className.indexOf('$') >= 0) {
			psiClass = facade.findClass(className.replace('$', '.'), scope);
		}
		if (psiClass == null) {
			return FunctionMetaData.empty();
		}
		PsiMethod method = resolveMethod(psiClass, methodName);
		if (method == null) {
			return FunctionMetaData.empty();
		}

		List<VariableAccess> variables = new ArrayList<>();
		List<HttpInput> httpInputs = new ArrayList<>();
		String parameterType = null;
		for (PsiParameter parameter : method.getParameterList().getParameters()) {
			VariableAccess access = toAccess(project, parameter);
			if (access != null) {
				variables.add(access);
				continue;
			}
			if (parameterType == null && parameter.hasAnnotation(PARAMETER)) {
				parameterType = normalise(parameter.getType().getCanonicalText());
				continue;
			}
			HttpInput httpInput = toHttpInput(parameter);
			if (httpInput != null) {
				httpInputs.add(httpInput);
			}
		}

		PsiType returnPsi = method.getReturnType();
		String returnType = (returnPsi != null && !returnPsi.equalsToText("void"))
				? normalise(returnPsi.getCanonicalText())
				: null;

		return new FunctionMetaData(variables, httpInputs, returnType, parameterType);
	}

	/** Detects an HTTP request input on a parameter (OfficeFloor web or Spring MVC). */
	private static HttpInput toHttpInput(PsiParameter parameter) {
		for (Map.Entry<String, HttpInput.Kind> entry : OFFICEFLOOR_HTTP.entrySet()) {
			PsiAnnotation annotation = parameter.getAnnotation(entry.getKey());
			if (annotation != null) {
				return buildHttpInput(parameter, annotation, entry.getValue(), HttpInput.Source.OFFICEFLOOR,
						entry.getKey());
			}
		}
		for (Map.Entry<String, HttpInput.Kind> entry : SPRING_HTTP.entrySet()) {
			PsiAnnotation annotation = parameter.getAnnotation(entry.getKey());
			if (annotation != null) {
				return buildHttpInput(parameter, annotation, entry.getValue(), HttpInput.Source.SPRING, entry.getKey());
			}
		}
		// @HttpObject may annotate the parameter's type (class) rather than the parameter.
		PsiClass typeClass = PsiUtil.resolveClassInClassTypeOnly(parameter.getType());
		if (typeClass != null && typeClass.hasAnnotation(HTTP_OBJECT)) {
			return new HttpInput(HttpInput.Kind.BODY, "", normalise(parameter.getType().getCanonicalText()),
					HttpInput.Source.OFFICEFLOOR, "HttpObject");
		}
		return null;
	}

	private static HttpInput buildHttpInput(PsiParameter parameter, PsiAnnotation annotation, HttpInput.Kind kind,
			HttpInput.Source source, String fqn) {
		String name = kind == HttpInput.Kind.BODY ? "" : httpInputName(annotation, parameter);
		String type = normalise(parameter.getType().getCanonicalText());
		String simpleAnnotation = fqn.substring(fqn.lastIndexOf('.') + 1);
		return new HttpInput(kind, name, type, source, simpleAnnotation);
	}

	/** The declared parameter name (annotation {@code value} or {@code name}), else the Java name. */
	private static String httpInputName(PsiAnnotation annotation, PsiParameter parameter) {
		String value = attributeString(annotation, "value");
		if (value == null || value.isEmpty()) {
			value = attributeString(annotation, "name");
		}
		if (value == null || value.isEmpty()) {
			value = parameter.getName();
		}
		return value == null ? "" : value;
	}

	private static String attributeString(PsiAnnotation annotation, String attribute) {
		PsiAnnotationMemberValue value = annotation.findAttributeValue(attribute);
		if (value == null) {
			return null;
		}
		String text = value.getText();
		if (text != null && text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
			return text.substring(1, text.length() - 1);
		}
		return null;
	}

	private static PsiMethod resolveMethod(PsiClass psiClass, String methodName) {
		if (methodName != null && !methodName.isEmpty()) {
			PsiMethod[] byName = psiClass.findMethodsByName(methodName, true);
			return byName.length > 0 ? byName[0] : null;
		}
		PsiMethod sole = null;
		for (PsiMethod method : psiClass.getMethods()) {
			if (!method.isConstructor() && method.hasModifierProperty(PsiModifier.PUBLIC)) {
				if (sole != null) {
					return null; // ambiguous; no single procedure method
				}
				sole = method;
			}
		}
		return sole;
	}

	private static VariableAccess toAccess(Project project, PsiParameter parameter) {
		PsiType type = parameter.getType();
		PsiClass typeClass = PsiUtil.resolveClassInClassTypeOnly(type);
		String typeFqn = typeClass == null ? null : typeClass.getQualifiedName();

		VariableDirection direction;
		String variableType;
		if (IN.equals(typeFqn)) {
			direction = VariableDirection.IN;
			variableType = firstTypeArgument(type);
		} else if (OUT.equals(typeFqn)) {
			direction = VariableDirection.OUT;
			variableType = firstTypeArgument(type);
		} else if (VAR.equals(typeFqn)) {
			direction = VariableDirection.IN_OUT;
			variableType = firstTypeArgument(type);
		} else if (parameter.hasAnnotation(VAL)) {
			direction = VariableDirection.IN;
			variableType = normalise(type.getCanonicalText());
		} else {
			return null; // not a variable parameter
		}

		String qualifier = resolveQualifier(project, parameter);
		String name = (qualifier == null ? "" : qualifier + "-") + variableType;
		return new VariableAccess(name, variableType, qualifier, direction);
	}

	private static String firstTypeArgument(PsiType type) {
		if (type instanceof PsiClassType) {
			PsiType[] params = ((PsiClassType) type).getParameters();
			if (params.length >= 1) {
				return normalise(params[0].getCanonicalText());
			}
		}
		return "java.lang.Object";
	}

	private static String resolveQualifier(Project project, PsiParameter parameter) {
		for (PsiAnnotation annotation : parameter.getAnnotations()) {
			String annFqn = annotation.getQualifiedName();
			if (annFqn == null || VAL.equals(annFqn)) {
				continue;
			}
			// Explicit @Qualified("name").
			if (QUALIFIED.equals(annFqn)) {
				String value = annotationStringValue(annotation);
				if (value != null && !value.isEmpty()) {
					return value;
				}
			}
			// An annotation meta-annotated with @Qualifier: default qualifier is its own FQN.
			PsiClass annClass = JavaPsiFacade.getInstance(project).findClass(annFqn,
					GlobalSearchScope.allScope(project));
			if (annClass != null && annClass.hasAnnotation(QUALIFIER_META)) {
				return annFqn;
			}
		}
		return null;
	}

	private static String annotationStringValue(PsiAnnotation annotation) {
		var value = annotation.findAttributeValue("value");
		if (value == null) {
			return null;
		}
		String text = value.getText();
		if (text != null && text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
			return text.substring(1, text.length() - 1);
		}
		return null;
	}

	private static String normalise(String typeText) {
		if (typeText == null) {
			return "java.lang.Object";
		}
		String t = typeText.trim();
		String boxed = BOXED.get(t);
		return boxed != null ? boxed : t;
	}
}
