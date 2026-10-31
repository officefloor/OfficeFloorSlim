package net.officefloor.officefloorslim.core;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;

import net.officefloor.sinewslim.core.Assessment;
import net.officefloor.sinewslim.core.Assessment.Status;
import net.officefloor.sinewslim.core.Assessor;
import net.officefloor.sinewslim.core.Params;
import net.officefloor.sinewslim.core.Slimmer;
import net.officefloor.sinewslim.facts.MethodFacts;
import net.officefloor.sinewslim.facts.Var;

/**
 * A long method priced both ways (DESIGN.md, Comparing the two costs): SinewSlim's conventional
 * assessment, the OfficeFloor column beside it in its own terms, the category and the migration
 * cost.
 *
 * <p>
 * The OfficeFloor column is OfficeFloorSlim's own split ({@link OfficeFloorSplit}), labelled
 * {@link #OFFICEFLOOR_READING}. Until Phase 4 it was SinewSlim's framework reading, which counted
 * only the parameters and returns of the conventional search's pieces, and kept a remainder.
 *
 * @param conventional SinewSlim's assessment under its defaults
 * @param officefloor  the OfficeFloor split's plan
 * @param category     the category, for a method SinewSlim cannot slim with one return per
 *                     piece; null otherwise
 * @param migration    what moving it to OfficeFloor would take
 */
public record Pricing(Assessment conventional, OfficeFloorSplit.Plan officefloor, Category category,
		Migration migration) {

	/** What the OfficeFloor column is: OfficeFloorSlim's own split (Phase 4), version 1. */
	public static final String OFFICEFLOOR_READING = "officefloorslim-split/1";

	static final Assessor CONVENTIONAL = Assessor.DEFAULTS;

	/** SinewSlim's defaults with the jump rule lifted: the guard clauses inverted, as a restructure would. */
	static final Params RESTRUCTURED = Params.DEFAULTS.toBuilder().ignoreJumps(true).build();

	/** DESIGN.md, Comparing the two costs: tested in the order 2, 1, 3, then 4 (decided). */
	public enum Category {
		RESTRUCTURE(1, "conventionally fixable once restructured"),
		RECORDS(2, "needs records"),
		FUNCTION_INJECTION(3, "only splits under function injection"),
		REDESIGN(4, "needs a redesign");

		private final int number;
		private final String label;

		Category(int number, String label) {
			this.number = number;
			this.label = label;
		}

		public int number() {
			return number;
		}

		public String label() {
			return label;
		}
	}

	/**
	 * What a migration to OfficeFloor would take, each part in its own unit; the parts are not
	 * weighed against each other.
	 *
	 * @param fields        the fields the method reads or writes: the beans to bridge or turn into
	 *                      managed objects
	 * @param transactional whether it, or its class, is {@code @Transactional}: a transaction
	 *                      boundary to keep
	 * @param escalations   the exception types it declares or catches: each becomes an escalation
	 * @param functions     the functions the OfficeFloor split's plan has (every line in one); -1
	 *                      when it has no plan
	 * @param edges         the flows between them, escalations included; -1 when it has no plan
	 */
	public record Migration(int fields, boolean transactional, int escalations, int functions, int edges) {
	}

	/** Whether SinewSlim cannot slim it with one return per piece: the go/no-go bar's denominator (M1). */
	public boolean notSlimmable() {
		return category != null;
	}

	public static Pricing of(MethodFacts facts, MethodShape shape) {
		Assessment conventional = CONVENTIONAL.assess(facts);
		OfficeFloorSplit.Plan officefloor = OfficeFloorSplit.plan(facts, shape);
		Category category = category(conventional.status(), () -> Slimmer.slim(facts, RESTRUCTURED).feasible(),
				officefloor.feasible());
		return new Pricing(conventional, officefloor, category, migration(facts, shape, officefloor));
	}

	/**
	 * The category, tested in the decided order: records, then the restructure, then the
	 * OfficeFloor column; null for a method SinewSlim slims with one return per piece.
	 *
	 * @param conventional  SinewSlim's status
	 * @param restructured  whether SinewSlim's defaults slim it with the jump rule lifted (asked
	 *                      only when needed)
	 * @param officefloor   whether the OfficeFloor split has a plan
	 */
	static Category category(Status conventional, BooleanSupplier restructured, boolean officefloor) {
		if (conventional == Status.SLIMMABLE_WITH_RECORD) {
			return Category.RECORDS;
		}
		if (conventional != Status.NOT_SLIMMABLE) {
			return null;
		}
		if (restructured.getAsBoolean()) {
			return Category.RESTRUCTURE;
		}
		return officefloor ? Category.FUNCTION_INJECTION : Category.REDESIGN;
	}

	static Migration migration(MethodFacts facts, MethodShape shape, OfficeFloorSplit.Plan officefloor) {
		int fields = 0;
		for (Var v : facts.vars()) {
			if (v.kind() == Var.Kind.FIELD) {
				fields++;
			}
		}
		Set<String> exceptions = new LinkedHashSet<>();
		exceptions.addAll(shape.declared());
		exceptions.addAll(shape.caught());
		boolean has = officefloor.applicable() && officefloor.feasible();
		return new Migration(fields, shape.annotated("Transactional"), exceptions.size(),
				has ? officefloor.functions().size() : -1, has ? officefloor.edges() : -1);
	}
}
