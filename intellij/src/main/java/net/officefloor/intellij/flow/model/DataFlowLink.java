package net.officefloor.intellij.flow.model;

/**
 * A derived data-flow connection: a function that writes a variable (the
 * {@code writer}) feeding a function that reads the same variable (the
 * {@code reader}).
 */
public final class DataFlowLink {

	private final String writerId;
	private final String readerId;
	private final String variableName;
	private final String typeLabel;

	public DataFlowLink(String writerId, String readerId, String variableName, String typeLabel) {
		this.writerId = writerId;
		this.readerId = readerId;
		this.variableName = variableName;
		this.typeLabel = typeLabel;
	}

	public String getWriterId() {
		return this.writerId;
	}

	public String getReaderId() {
		return this.readerId;
	}

	public String getVariableName() {
		return this.variableName;
	}

	public String getTypeLabel() {
		return this.typeLabel;
	}

	@Override
	public String toString() {
		return "DataFlowLink[" + this.writerId + " =" + this.typeLabel + "=> " + this.readerId + "]";
	}
}
