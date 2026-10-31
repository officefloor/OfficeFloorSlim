package net.officefloor.intellij.flow.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A node in an OfficeFloor flow graph: an HTTP endpoint, a function/procedure, a
 * section, etc.
 *
 * <p>
 * The model is deliberately free of any IntelliJ dependency so it can be unit
 * tested in isolation. Navigation targets are captured as plain data (a text
 * offset into the configuration file, and an optional Java class / method); the
 * UI layer resolves these to PSI elements when the user clicks.
 */
public final class FlowNode {

	private final String id;
	private final NodeKind kind;
	private final String title;
	private final String subtitle;

	/** Offset into the configuration document where this node is declared (or -1). */
	private final int sourceOffset;

	/** Fully qualified Java class this node delegates to, or {@code null}. */
	private final String javaClassName;

	/** Java method name on {@link #javaClassName}, or {@code null}. */
	private final String javaMethodName;

	/** Variables this function reads/writes (populated from the Java method signature). */
	private final List<VariableAccess> variables = new ArrayList<>();

	/** HTTP request inputs this function consumes (populated from the Java method signature). */
	private final List<HttpInput> httpInputs = new ArrayList<>();

	/** The method's non-void return type (the argument passed to the {@code next} function), or null. */
	private String returnType;

	/** The type of the method's {@code @Parameter} argument (received from the previous function), or null. */
	private String parameterType;

	public FlowNode(String id, NodeKind kind, String title, String subtitle, int sourceOffset, String javaClassName,
			String javaMethodName) {
		this.id = Objects.requireNonNull(id, "id");
		this.kind = Objects.requireNonNull(kind, "kind");
		this.title = Objects.requireNonNull(title, "title");
		this.subtitle = subtitle == null ? "" : subtitle;
		this.sourceOffset = sourceOffset;
		this.javaClassName = javaClassName;
		this.javaMethodName = javaMethodName;
	}

	public String getId() {
		return this.id;
	}

	public NodeKind getKind() {
		return this.kind;
	}

	public String getTitle() {
		return this.title;
	}

	public String getSubtitle() {
		return this.subtitle;
	}

	public int getSourceOffset() {
		return this.sourceOffset;
	}

	public String getJavaClassName() {
		return this.javaClassName;
	}

	public String getJavaMethodName() {
		return this.javaMethodName;
	}

	public boolean hasJavaTarget() {
		return this.javaClassName != null;
	}

	public void addVariable(VariableAccess access) {
		this.variables.add(access);
	}

	public void clearVariables() {
		this.variables.clear();
	}

	public List<VariableAccess> getVariables() {
		return Collections.unmodifiableList(this.variables);
	}

	public boolean hasVariables() {
		return !this.variables.isEmpty();
	}

	public void addHttpInput(HttpInput input) {
		this.httpInputs.add(input);
	}

	public void clearHttpInputs() {
		this.httpInputs.clear();
	}

	public List<HttpInput> getHttpInputs() {
		return Collections.unmodifiableList(this.httpInputs);
	}

	public boolean hasHttpInputs() {
		return !this.httpInputs.isEmpty();
	}

	public String getReturnType() {
		return this.returnType;
	}

	public void setReturnType(String returnType) {
		this.returnType = returnType;
	}

	public boolean hasReturnType() {
		return this.returnType != null;
	}

	public String getParameterType() {
		return this.parameterType;
	}

	public void setParameterType(String parameterType) {
		this.parameterType = parameterType;
	}

	public boolean hasParameterType() {
		return this.parameterType != null;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof FlowNode)) {
			return false;
		}
		return this.id.equals(((FlowNode) o).id);
	}

	@Override
	public int hashCode() {
		return this.id.hashCode();
	}

	@Override
	public String toString() {
		return "FlowNode[" + this.id + ", " + this.kind + "]";
	}
}
