package net.officefloor.officefloorslim.core;

import java.util.List;
import java.util.Set;

import net.officefloor.officefloorslim.core.MethodShape.Param;

/**
 * Which reading a method is priced under, and the evidence for it (DESIGN.md, Classification).
 * The reading comes from how a framework marks its functions, never from the method's body.
 *
 * @param reading  the class the method is in
 * @param evidence what put it there, readable: {@code "wired in rest/api/owners.GET.yml"},
 *                 {@code "@GetMapping"}; for a conventional method, why nothing else applied
 */
public record Classification(Reading reading, String evidence) {

	public enum Reading {
		/** Wired by OfficeFloor: a configuration names it, or its signature is OfficeFloor's. */
		OFFICEFLOOR_FUNCTION,
		/** Called by a framework (Spring), not by the project's own code. */
		FRAMEWORK_ENTRY_POINT,
		/** Called by the project's own code. */
		CONVENTIONAL
	}

	/** OfficeFloor's markers on a method. */
	static final Set<String> OFFICEFLOOR_METHOD = Set.of("Next");

	/** OfficeFloor's markers on a parameter. */
	static final Set<String> OFFICEFLOOR_PARAM = Set.of("Parameter", "Val");

	/** OfficeFloor's variable types, as a parameter's type. */
	static final Set<String> OFFICEFLOOR_TYPES = Set.of("Out", "In", "Var");

	/** Spring's markers of a method a framework calls. */
	static final List<String> FRAMEWORK_METHOD = List.of("GetMapping", "PostMapping", "PutMapping", "DeleteMapping",
			"PatchMapping", "RequestMapping", "MessageMapping", "ExceptionHandler", "ModelAttribute", "InitBinder",
			"Scheduled", "EventListener", "TransactionalEventListener", "KafkaListener", "RabbitListener",
			"JmsListener", "SqsListener", "Bean", "PostConstruct", "PreDestroy");

	/** Spring's stereotypes: their public instance methods are called through the framework. */
	static final List<String> FRAMEWORK_TYPE = List.of("RestController", "Controller", "RestControllerAdvice",
			"ControllerAdvice", "Service", "Component", "Repository", "Configuration", "SpringBootApplication");

	public static Classification of(MethodShape shape, String methodName, Wiring wiring) {
		List<String> files = wiring.wiringMethod(shape.className(), methodName);
		if (files != null) {
			return new Classification(Reading.OFFICEFLOOR_FUNCTION, "wired in " + String.join(", ", files));
		}
		files = wiring.wiringClass(shape.className());
		if (files != null && shape.publicMethod()) {
			return new Classification(Reading.OFFICEFLOOR_FUNCTION, "its class is wired in " + String.join(", ", files));
		}
		for (String a : OFFICEFLOOR_METHOD) {
			if (MethodShape.has(shape.methodAnnotations(), a)) {
				return new Classification(Reading.OFFICEFLOOR_FUNCTION, "@" + a);
			}
		}
		for (Param p : shape.params()) {
			for (String a : OFFICEFLOOR_PARAM) {
				if (MethodShape.has(p.annotations(), a)) {
					return new Classification(Reading.OFFICEFLOOR_FUNCTION, "@" + a + " " + p.name());
				}
			}
			if (OFFICEFLOOR_TYPES.contains(p.rawSimpleType())) {
				return new Classification(Reading.OFFICEFLOOR_FUNCTION, p.type() + " " + p.name());
			}
		}
		for (String a : FRAMEWORK_METHOD) {
			if (MethodShape.has(shape.methodAnnotations(), a)) {
				return new Classification(Reading.FRAMEWORK_ENTRY_POINT, "@" + a);
			}
		}
		if (shape.publicMethod() && !shape.staticMethod()) {
			for (String a : FRAMEWORK_TYPE) {
				if (MethodShape.has(shape.typeAnnotations(), a)) {
					return new Classification(Reading.FRAMEWORK_ENTRY_POINT, "public, in a @" + a);
				}
			}
		}
		return new Classification(Reading.CONVENTIONAL, "no OfficeFloor wiring or framework marker");
	}
}
