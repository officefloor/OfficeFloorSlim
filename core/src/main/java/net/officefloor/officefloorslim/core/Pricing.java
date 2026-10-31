package net.officefloor.officefloorslim.core;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;

import net.officefloor.sinewslim.core.Assessment;
import net.officefloor.sinewslim.core.Assessment.Status;
import net.officefloor.sinewslim.core.Assessor;
import net.officefloor.sinewslim.core.Params;
import net.officefloor.sinewslim.core.SlimResult;
import net.officefloor.sinewslim.core.Slimmer;
import net.officefloor.sinewslim.facts.MethodFacts;
import net.officefloor.sinewslim.facts.Var;

/**
 * A long method priced both ways (DESIGN.md, Comparing the two costs): SinewSlim's conventional
 * assessment, the OfficeFloor column beside it in its own terms, the category and the migration
 * cost.
 *
 * <p>
 * Until OfficeFloorSlim's own split exists (PLAN.md, Phase 4) the OfficeFloor column is
 * SinewSlim's framework reading ({@link Params#framework()}): pass-through parameters injected
 * for nothing, each further result a declared value. It is labelled {@link #OFFICEFLOOR_READING}
 * wherever it is shown.
 *
 * @param conventional SinewSlim's assessment under its defaults
 * @param officefloor  the OfficeFloor column's assessment
 * @param category     the category, for a method SinewSlim cannot slim with one return per
 *                     piece; null otherwise
 * @param migration    what moving it to OfficeFloor would take
 */
public record Pricing(Assessment conventional, Assessment officefloor, Category category, Migration migration) {

	/** What the OfficeFloor column is, until Phase 4. */
	public static final String OFFICEFLOOR_READING = "sinewslim-framework-reading";

	static final Assessor CONVENTIONAL = Assessor.DEFAULTS;

	static final Assessor OFFICEFLOOR = new Assessor(Params.DEFAULTS.framework(), true, true, 1);

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
	 * @param functions     the functions the OfficeFloor column's split gives (its pieces and the
	 *                      remainder); -1 when it has none
	 * @param edges         the flows between them; -1 when it has none
	 */
	public record Migration(int fields, boolean transactional, int escalations, int functions, int edges) {
	}

	/** Whether SinewSlim cannot slim it with one return per piece: the go/no-go bar's denominator (M1). */
	public boolean notSlimmable() {
		return category != null;
	}

	public static Pricing of(MethodFacts facts, MethodShape shape) {
		Assessment conventional = CONVENTIONAL.assess(facts);
		Assessment officefloor = OFFICEFLOOR.assess(facts);
		Category category = category(conventional.status(), () -> Slimmer.slim(facts, RESTRUCTURED).feasible(),
				officefloor.proposal().feasible());
		return new Pricing(conventional, officefloor, category, migration(facts, shape, officefloor));
	}

	/**
	 * The category, tested in the decided order: records, then the restructure, then the
	 * OfficeFloor column; null for a method SinewSlim slims with one return per piece.
	 *
	 * @param conventional  SinewSlim's status
	 * @param restructured  whether SinewSlim's defaults slim it with the jump rule lifted (asked
	 *                      only when needed)
	 * @param officefloor   whether the OfficeFloor column slims it
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

	static Migration migration(MethodFacts facts, MethodShape shape, Assessment officefloor) {
		int fields = 0;
		for (Var v : facts.vars()) {
			if (v.kind() == Var.Kind.FIELD) {
				fields++;
			}
		}
		Set<String> exceptions = new LinkedHashSet<>();
		exceptions.addAll(shape.declared());
		exceptions.addAll(shape.caught());
		SlimResult split = officefloor.proposal();
		boolean has = split.applicable() && split.feasible();
		return new Migration(fields, shape.annotated("Transactional"), exceptions.size(), has ? split.pieces() + 1 : -1,
				has ? split.pieces() : -1);
	}
}
