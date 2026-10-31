package net.officefloor.intellij.flow.model;

/**
 * Kind of connection between two {@link FlowNode}s. Determines the line style
 * and default label of the connection in the diagram.
 */
public enum LinkKind {

	/** Sequential continuation ({@code next}). */
	NEXT,

	/** A named branch / flow output ({@code outputs: { name: target }}). */
	FLOW,

	/** An exception / escalation handling flow ({@code escalations}). */
	ESCALATION,

	/** HTTP endpoint routing into the first function of its pipeline. */
	ROUTE,

	/** A redirect to another HTTP continuation. */
	REDIRECT
}
