package net.officefloor.intellij.flow.model;

/**
 * Kind of node in an OfficeFloor flow graph. Determines how the node is drawn
 * (colour, icon, shape) in the diagram.
 */
public enum NodeKind {

	/** An HTTP endpoint (the entry point of a request pipeline). */
	HTTP_ENDPOINT,

	/** A procedure / injected function (a method on a class). */
	PROCEDURE,

	/** A section: a reusable sub-graph of functions. */
	SECTION,

	/** A server-rendered template. */
	TEMPLATE,

	/** A static resource. */
	RESOURCE,

	/** An HTTP security handler. */
	SECURITY,

	/** An exception / escalation handler. */
	EXCEPTION_HANDLER,

	/** A start-up trigger. */
	START,

	/** Unknown / unclassified. */
	UNKNOWN
}
