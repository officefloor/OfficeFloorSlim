package net.officefloor.officefloorslim.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.officefloor.officefloorslim.core.MethodShape.Catch;
import net.officefloor.officefloorslim.core.MethodShape.Switch;
import net.officefloor.officefloorslim.core.MethodShape.Try;
import net.officefloor.sinewslim.core.Assessment;
import net.officefloor.sinewslim.core.Assessment.Status;
import net.officefloor.sinewslim.core.Assessor;
import net.officefloor.sinewslim.core.SlimResult;
import net.officefloor.sinewslim.core.Slimmer;
import net.officefloor.sinewslim.facts.MethodFacts;
import net.officefloor.sinewslim.facts.MethodIndex;
import net.officefloor.sinewslim.facts.Seam;
import net.officefloor.sinewslim.facts.Stmt;
import net.officefloor.sinewslim.facts.StmtKind;
import net.officefloor.sinewslim.facts.Var;

/**
 * The OfficeFloor split (DESIGN.md, The OfficeFloor split): a long method partitioned into
 * functions OfficeFloor injects and sequences, every line in one, none over the target, with the
 * flows between them. There is no remainder.
 *
 * <p>
 * The cost model is the decided one (DESIGN.md, questions 1 to 7):
 * <ul>
 * <li>a function's seam is the values computed inside the method that it declares, 1 each; what
 * comes from outside the method (its parameters as given, fields, managed objects) is injected and
 * free, and so is a catch's parameter in its handler (questions 1 and 3);</li>
 * <li>a function costs 1 and an edge costs 1 (question 2);</li>
 * <li>an {@code if} or {@code switch} too long for one function becomes a decision function (1)
 * with an edge per branch; the edges into the join are ordinary edges, and a branch that ends in a
 * {@code return} or {@code throw} does not reach the join (question 4);</li>
 * <li>a {@code try} cut across functions makes each catch a handler: its function (1) and an
 * escalation edge per exception type it catches; a throw that leaves the method stays free
 * (question 5);</li>
 * <li>a {@code return} inside a function, not at its end, is one more flow, to the end;</li>
 * <li>the plan is the smallest worst seam, then the fewest functions, then the smallest total
 * (question 6), the total being the seams, the functions and the edges (question 7).</li>
 * </ul>
 * A {@code break}, {@code continue} or {@code yield} leaving a function blocks it; loops,
 * {@code synchronized} and labeled statements, a try with resources or a finally, and a switch
 * whose cases fall through are never cut.
 *
 * <p>
 * That is {@link Version#INJECTION}, {@code officefloorslim-split/1}. {@link Version#COMBINED},
 * {@code officefloorslim-split/2} (DESIGN.md, The combined split, questions 8 to 11), cuts the
 * same functions by the same rules, but a function may be over the target if SinewSlim's search,
 * under its defaults, slims its body as a method of its own (its declared values its parameters,
 * priced as SinewSlim prices any method's); a body SinewSlim cannot slim with one return per
 * piece makes that cut infeasible. The plan is the smallest total, the injection total plus
 * SinewSlim's cost of every piece inside the functions, then the fewest pieces, then the fewest
 * functions. The whole method as one function is SinewSlim's own assessment of it.
 */
public final class OfficeFloorSplit {

	/**
	 * The numbers the split decides by.
	 *
	 * @param trigger    a method this long or longer is long (SinewSlim's)
	 * @param target     every function at or under this many code lines (SinewSlim's)
	 * @param maxCarried the most values a function may declare from earlier ones: as SinewSlim's
	 *                   limit on a piece's parameters
	 */
	public record Params(int trigger, int target, int maxCarried) {

		public static final Params DEFAULTS = new Params(26, 25, 5);
	}

	/** Which split. */
	public enum Version {
		/** Function injection alone. */
		INJECTION("officefloorslim-split/1"),
		/** Functions where the flow divides, SinewSlim's extractions inside them. */
		COMBINED("officefloorslim-split/2");

		private final String reading;

		Version(String reading) {
			this.reading = reading;
		}

		/** The reading's name, as the scan's JSON gives it. */
		public String reading() {
			return reading;
		}
	}

	public enum Kind {
		/** A run of whole statements. */
		RUN,
		/** The condition of an {@code if} or {@code switch}, whose branches are flows. */
		DECISION,
		/** The first function of a catch's body, which an escalation reaches. */
		HANDLER
	}

	/** What joins one function of a plan to another. */
	public enum LinkKind {
		/** The function's end, on to the next: its {@code @Next}. */
		NEXT,
		/** A decision's branch: a flow it chooses. */
		FLOW,
		/** A return before the function's end: a flow to the method's end. */
		RETURN,
		/** A cut try's exception, escalated to its handler. */
		ESCALATION
	}

	/**
	 * One flow of a plan, between its functions by their index in {@link Plan#functions()}.
	 *
	 * @param from the function it leaves
	 * @param to   the function it reaches; {@link #END} for the method's end
	 * @param kind what it is
	 */
	public record Link(int from, int to, LinkKind kind) {

		/** The method's end: what follows the method. */
		public static final int END = -1;
	}

	/** Why no plan brings it under the target. */
	public enum Reason {
		/** A loop longer than the target: its body cannot be cut. */
		LOOP,
		/** A single statement longer than the target that is not an if, switch, try or block. */
		LONG_STATEMENT,
		/** A try with resources or a finally, longer than the target. */
		TRY,
		/** A switch whose cases fall through, longer than the target. */
		SWITCH,
		/** Every plan needs a function that declares more carried values than the limit. */
		CARRIED,
		/** Every plan cuts across a break, continue or yield. */
		JUMPS
	}

	/**
	 * One function of the plan.
	 *
	 * @param kind     what it is
	 * @param lo       its first line
	 * @param hi       its last line
	 * @param lines    its code lines
	 * @param declares the carried values it declares (its seam)
	 * @param inner    SinewSlim's slimming of its body, under the combined split, when it is over
	 *                 the target or is the whole method; null otherwise
	 */
	public record Function(Kind kind, int lo, int hi, int lines, List<String> declares, SlimResult inner) {

		public Function(Kind kind, int lo, int hi, int lines, List<String> declares) {
			this(kind, lo, hi, lines, declares, null);
		}

		public int seam() {
			return declares.size();
		}

		/** The pieces SinewSlim extracts inside it. */
		public int pieces() {
			return inner == null ? 0 : inner.pieces();
		}

		/** SinewSlim's cost of those pieces. */
		public int extraction() {
			return inner == null ? 0 : inner.cost();
		}
	}

	/**
	 * The split of a method.
	 *
	 * @param applicable whether the method is long
	 * @param feasible   whether a plan exists
	 * @param reason     why not, when there is none
	 * @param detail     where, when one statement is the reason
	 * @param functions  the plan's functions, in source order
	 * @param edges      its flows (escalations included)
	 * @param worstSeam  the largest seam
	 * @param total       the injection total: the seams, plus 1 per function, plus 1 per edge
	 * @param pieces      the pieces SinewSlim extracts inside the functions (combined split only)
	 * @param extraction  SinewSlim's cost of those pieces (combined split only)
	 * @param tieByPieces the plan won a tie on the total by having fewer pieces: ordering the
	 *                    functions first would have chosen another (combined split only)
	 * @param links       its flows, function to function: each edge, an escalation drawn once
	 *                    however many types it carries, and the functions whose flow leaves the
	 *                    method listed in {@code outs}
	 * @param outs        the functions whose flow leaves the method at its end, one entry per flow
	 *                    (a decision straight to the method's end can be listed twice); a return
	 *                    before a function's end is a {@link LinkKind#RETURN} link instead
	 * @param returns     the functions that end in a {@code return}: the method's own end, so in
	 *                    the flow graph they too go on to what follows the method (its value, if
	 *                    any, passed to it)
	 */
	public record Plan(boolean applicable, boolean feasible, Reason reason, String detail, List<Function> functions,
			int edges, int worstSeam, int total, int pieces, int extraction, boolean tieByPieces, List<Link> links,
			List<Integer> outs, List<Integer> returns) {

		static final Plan NOT_LONG = new Plan(false, true, null, null, List.of(), 0, 0, 0);

		public Plan {
			functions = List.copyOf(functions);
			links = List.copyOf(links);
			outs = List.copyOf(outs);
			returns = List.copyOf(returns);
		}

		public Plan(boolean applicable, boolean feasible, Reason reason, String detail, List<Function> functions,
				int edges, int worstSeam, int total) {
			this(applicable, feasible, reason, detail, functions, edges, worstSeam, total, 0, 0, false, List.of(),
					List.of(), List.of());
		}

		/** The injection total and the extraction total: what the combined split chooses by. */
		public int sum() {
			return total + extraction;
		}

		/** A one-function plan: SinewSlim's own, with a function around it; injection not used. */
		public boolean extractionOnly() {
			return feasible && functions.size() == 1;
		}

		/** The worst seam of the pieces inside the functions, by SinewSlim's width; 0 when none. */
		public int worstPiece() {
			int w = 0;
			for (Function f : functions) {
				if (f.inner() != null && f.inner().pieces() > 0) {
					w = Math.max(w, f.inner().width());
				}
			}
			return w;
		}
	}

	/** A way of covering some statements: its functions, cost and edges so far, and the flows leaving it. */
	/**
	 * A way of covering some statements. {@code links} join its functions by their index in
	 * {@code fns}; {@code outs} are the functions whose flow leaves it, one entry per flow (so
	 * {@code exits} is their number once it has a function).
	 */
	private record Part(int functions, int cost, int edges, int exits, int worst, int pieces, int extraction,
			List<Function> fns, List<Link> links, List<Integer> outs, List<Integer> returns) {

		static final Part EMPTY = new Part(0, 0, 0, 1, 0, 0, 0, List.of(), List.of(), List.of(), List.of());

		/** One function on its own, its flows leaving from it. */
		static Part of(Function f, int cost, int edges, int exits, int worst, List<Link> links) {
			return of(f, cost, edges, exits, worst, links, false);
		}

		/** One function on its own, its flows leaving from it; {@code returns} when it ends in a return. */
		static Part of(Function f, int cost, int edges, int exits, int worst, List<Link> links, boolean returns) {
			List<Integer> outs = new ArrayList<>();
			for (int i = 0; i < exits; i++) {
				outs.add(0);
			}
			return new Part(1, cost, edges, exits, worst, f.pieces(), f.extraction(), List.of(f), links, outs,
					returns ? List.of(0) : List.of());
		}

		Part with(List<Function> fns, List<Link> links) {
			return new Part(functions, cost, edges, exits, worst, pieces, extraction, fns, links, outs, returns);
		}

		/** This, then {@code next}: the flows leaving this are the edges into {@code next}. */
		Part then(Part next) {
			if (functions == 0) {
				return next;
			}
			if (next.functions == 0) {
				return this;
			}
			int n = fns.size();
			List<Function> all = new ArrayList<>(fns);
			all.addAll(next.fns);
			List<Link> links = new ArrayList<>(this.links);
			for (int out : outs) {
				links.add(new Link(out, n, LinkKind.NEXT));
			}
			links.addAll(shift(next.links, n));
			List<Integer> returns = new ArrayList<>(this.returns);
			returns.addAll(shiftOuts(next.returns, n));
			return new Part(functions + next.functions, cost + next.cost + exits, edges + next.edges + exits, next.exits,
					Math.max(worst, next.worst), pieces + next.pieces, extraction + next.extraction, all, links,
					shiftOuts(next.outs, n), returns);
		}

		/**
		 * This and {@code o} side by side (a branch, a handler): {@code into} is the link from this
		 * part's first function into {@code o}'s first, costing one edge; null for none.
		 */
		Part beside(Part o, LinkKind into) {
			int n = fns.size(), extra = into == null ? 0 : 1;
			List<Function> all = new ArrayList<>(fns);
			all.addAll(o.fns);
			List<Link> links = new ArrayList<>(this.links);
			if (into != null) {
				links.add(new Link(0, n, into));
			}
			links.addAll(shift(o.links, n));
			List<Integer> outs = new ArrayList<>(this.outs);
			outs.addAll(shiftOuts(o.outs, n));
			List<Integer> returns = new ArrayList<>(this.returns);
			returns.addAll(shiftOuts(o.returns, n));
			return new Part(functions + o.functions, cost + o.cost + extra, edges + o.edges + extra, exits + o.exits,
					Math.max(worst, o.worst), pieces + o.pieces, extraction + o.extraction, all, links, outs, returns);
		}

		/** One more flow leaving from the first function (a decision straight to the join). */
		Part leaving() {
			List<Integer> outs = new ArrayList<>(this.outs);
			outs.add(0);
			return new Part(functions, cost, edges, exits + 1, worst, pieces, extraction, fns, links, outs, returns);
		}

		private static List<Link> shift(List<Link> links, int n) {
			List<Link> out = new ArrayList<>(links.size());
			for (Link l : links) {
				out.add(new Link(l.from() + n, l.to() == Link.END ? Link.END : l.to() + n, l.kind()));
			}
			return out;
		}

		private static List<Integer> shiftOuts(List<Integer> outs, int n) {
			List<Integer> out = new ArrayList<>(outs.size());
			for (int i : outs) {
				out.add(i + n);
			}
			return out;
		}
	}

	/** Whether {@code a} is the better plan, by the version's order (questions 6 and 11). */
	private boolean better(Part a, Part b) {
		if (version == Version.INJECTION) {
			return a.functions != b.functions ? a.functions < b.functions : a.cost < b.cost;
		}
		int sa = a.cost + a.extraction, sb = b.cost + b.extraction;
		if (sa != sb) {
			return sa < sb;
		}
		if (piecesFirst) {
			return a.pieces != b.pieces ? a.pieces < b.pieces : a.functions < b.functions;
		}
		return a.functions != b.functions ? a.functions < b.functions : a.pieces < b.pieces;
	}

	private final MethodFacts facts;
	private final MethodIndex index;
	private final Params p;
	private final Version version;
	/** The combined split's tie order: the fewer pieces first (question 11); off only to tell a tie. */
	private boolean piecesFirst = true;
	/** SinewSlim's slimming of a run as a function body, by its lines; {@link #CANNOT} when it cannot. */
	private final Map<Long, SlimResult> inner = new HashMap<>();
	private static final SlimResult CANNOT = new SlimResult(true, false, SlimResult.INFEASIBLE, SlimResult.INFEASIBLE, 0,
			List.of(), List.of(), false, Assessor.DEFAULTS.params());
	private final List<Try> tries;
	private final List<Switch> switches;
	private int limit;
	private final Map<Long, Map<Integer, Part>> memo = new HashMap<>();

	private OfficeFloorSplit(MethodFacts facts, MethodShape shape, Params p, Version version) {
		this.facts = facts;
		this.index = new MethodIndex(facts, true); // a parameter handed on as it came is injected
		this.p = p;
		this.version = version;
		this.tries = shape == null ? List.of() : shape.tries();
		this.switches = shape == null ? List.of() : shape.switches();
	}

	public static Plan plan(MethodFacts facts, MethodShape shape, Params p) {
		return plan(facts, shape, p, Version.INJECTION);
	}

	/** The combined split ({@code officefloorslim-split/2}) under the defaults. */
	public static Plan combined(MethodFacts facts, MethodShape shape) {
		return plan(facts, shape, Params.DEFAULTS, Version.COMBINED);
	}

	public static Plan plan(MethodFacts facts, MethodShape shape, Params p, Version version) {
		OfficeFloorSplit s = new OfficeFloorSplit(facts, shape, p, version);
		int length = facts.length() > 0 ? facts.length() : s.index.codeLines();
		if (length < p.trigger()) {
			return Plan.NOT_LONG;
		}
		if (version == Version.COMBINED) {
			return s.combinedPlan();
		}
		for (int w = 0; w <= p.maxCarried(); w++) {
			Part best = s.solve(w);
			if (best != null) {
				return new Plan(true, true, null, null, best.fns, best.edges, best.worst, best.cost, 0, 0, false,
						best.links, best.outs, best.returns);
			}
		}
		return s.why();
	}

	public static Plan plan(MethodFacts facts, MethodShape shape) {
		return plan(facts, shape, Params.DEFAULTS);
	}

	/** The best plan with every seam at or under {@code w}; null when there is none. */
	private Part solve(int w) {
		limit = w;
		memo.clear();
		Map<Integer, Part> top = seq(facts.body().kids(), Set.of());
		Part best = null;
		for (Part c : top.values()) {
			if (best == null || better(c, best)) {
				best = c; // the flows leaving the method go nowhere, so cost nothing
			}
		}
		return best;
	}

	/**
	 * The combined split's plan: the smallest total, then the fewer pieces, then the fewer
	 * functions (question 11), every function within the carried limit. Solved again with the
	 * functions ordered first, to tell whether a tie on the total was settled by the pieces.
	 */
	private Plan combinedPlan() {
		piecesFirst = true;
		Part best = solve(p.maxCarried());
		if (best == null) {
			return why();
		}
		piecesFirst = false;
		Part alt = solve(p.maxCarried());
		piecesFirst = true;
		boolean tie = alt != null && alt.cost + alt.extraction == best.cost + best.extraction
				&& (alt.pieces != best.pieces || alt.functions != best.functions);
		return new Plan(true, true, null, null, best.fns, best.edges, best.worst, best.cost, best.pieces, best.extraction,
				tie, best.links, best.outs, best.returns);
	}

	/** Why there is no plan: carried values when lifting the limit finds one, else the blocking statement. */
	private Plan why() {
		limit = Integer.MAX_VALUE;
		memo.clear();
		if (!seq(facts.body().kids(), Set.of()).isEmpty()) {
			return new Plan(true, false, Reason.CARRIED, null, List.of(), 0, 0, 0);
		}
		Stmt[] at = new Stmt[1];
		Reason r = blocking(facts.body(), at);
		String detail = at[0] == null ? null
				: "the " + name(at[0].kind()) + " at lines " + at[0].start() + "-" + at[0].end() + " ("
						+ index.lines(at[0].start(), at[0].end()) + " lines)";
		if (detail != null && version == Version.COMBINED) {
			// what SinewSlim says of it as a function body of its own (question 10)
			Assessment a = Assessor.DEFAULTS.assess(body(at[0].start(), at[0].end(), List.of(at[0])));
			if (a.reason() != null) {
				detail += "; as a function body, SinewSlim: " + a.reason();
			}
		}
		return new Plan(true, false, r == null ? Reason.JUMPS : r, detail, List.of(), 0, 0, 0);
	}

	/** The first statement over the target that cannot be expanded, and why; null when every one can. */
	private Reason blocking(Stmt node, Stmt[] at) {
		for (Stmt k : node.seq() ? node.kids() : List.of(node)) {
			if (index.lines(k.start(), k.end()) <= p.target()) {
				continue;
			}
			if (version == Version.COMBINED && inner(List.of(k)) != null) {
				continue; // slimmed inside its function: not what blocks
			}
			Reason own = switch (k.kind()) {
			case LOOP -> Reason.LOOP;
			case TRY -> cuttable(k) ? null : Reason.TRY;
			case SWITCH -> fallsThrough(k) ? Reason.SWITCH : null;
			case IF, BLOCK -> null;
			default -> Reason.LONG_STATEMENT;
			};
			if (own != null) {
				at[0] = k;
				return own;
			}
			for (Stmt c : k.kids()) {
				Reason r = blocking(c, at);
				if (r != null) {
					return r;
				}
			}
		}
		return null;
	}

	// ------------------------------------------------------------------------------------------
	// sequences
	// ------------------------------------------------------------------------------------------

	/**
	 * The best ways to cover a sequence of sibling statements, by the flows leaving its end.
	 *
	 * @param free names injected here (a catch's parameter in its handler)
	 */
	private Map<Integer, Part> seq(List<Stmt> kids, Set<String> free) {
		int n = kids.size();
		if (n == 0) {
			return Map.of(1, Part.EMPTY);
		}
		long key = ((long) kids.get(0).start() << 32) | (kids.get(n - 1).end() & 0xffffffffL);
		Map<Integer, Part> cached = free.isEmpty() ? memo.get(key) : null;
		if (cached != null) {
			return cached;
		}
		List<Map<Integer, Part>> at = new ArrayList<>(n + 1);
		at.add(Map.of(1, Part.EMPTY));
		for (int i = 1; i <= n; i++) {
			Map<Integer, Part> here = new HashMap<>();
			for (int j = i - 1; j >= 0; j--) {
				if (at.get(j).isEmpty()) {
					continue;
				}
				// a run of whole statements j..i-1, as one function
				Part run = run(kids.subList(j, i), free);
				if (run == null && version == Version.INJECTION
						&& index.lines(kids.get(j).start(), kids.get(i - 1).end()) > p.target()) {
					if (i - j > 1) {
						break; // longer runs only get longer
					}
				}
				if (run != null) {
					join(here, at.get(j), run);
				}
				// one statement, expanded
				if (i - j == 1) {
					for (Part x : expand(kids.get(j), free)) {
						join(here, at.get(j), x);
					}
				}
			}
			at.add(here);
		}
		Map<Integer, Part> out = at.get(n);
		if (free.isEmpty()) {
			memo.put(key, out);
		}
		return out;
	}

	private void join(Map<Integer, Part> into, Map<Integer, Part> before, Part next) {
		for (Part b : before.values()) {
			Part c = b.then(next);
			Part cur = into.get(c.exits);
			if (cur == null || better(c, cur)) {
				into.put(c.exits, c);
			}
		}
	}

	/**
	 * Statements j..i-1 as one function; null when they do not fit or a jump leaves them. Under
	 * the combined split a run over the target fits when SinewSlim slims it as a function body, and
	 * the whole method as one function is SinewSlim's own assessment of the method.
	 */
	private Part run(List<Stmt> run, Set<String> free) {
		Stmt first = run.get(0), last = run.get(run.size() - 1);
		int lines = index.lines(first.start(), last.end());
		if (!jumpsStay(run)) {
			return null;
		}
		SlimResult slimmed = null;
		boolean method = version == Version.COMBINED && whole(run);
		if (method) {
			Assessment a = Assessor.DEFAULTS.assess(facts);
			if (!slimmed(a.status())) {
				return null;
			}
			slimmed = a.proposal();
		} else if (lines > p.target()) {
			if (version == Version.INJECTION || (slimmed = inner(run)) == null) {
				return null;
			}
		}
		List<String> declares = declares(first.start(), last.end(), free);
		if (declares.size() > limit) {
			return null;
		}
		Function f = new Function(Kind.RUN, first.start(), last.end(), lines, declares, slimmed);
		boolean ends = ends(last);
		// a return before the end skips what follows: one flow, to the end; a function that ends the
		// flow itself has nothing after it to skip, nor does the method as one function, whose returns
		// are its own
		int early = !ends && !method && returnsInside(run) > 0 ? 1 : 0;
		return Part.of(f, 1 + declares.size() + early, early, ends ? 0 : 1, declares.size(),
				early == 1 ? List.of(new Link(0, Link.END, LinkKind.RETURN)) : List.of(), last.kind() == StmtKind.RETURN);
	}

	/** Whether the run is the method's whole body. */
	private boolean whole(List<Stmt> run) {
		List<Stmt> top = facts.body().kids();
		return run.get(0) == top.get(0) && run.get(run.size() - 1) == top.get(top.size() - 1);
	}

	/** SinewSlim slims it with one return per piece; with a record does not count (question 10). */
	private static boolean slimmed(Status s) {
		return s == Status.SLIMMABLE || s == Status.SLIMMABLE_BUT_LOCKED;
	}

	/**
	 * SinewSlim's slimming of a run of statements as a function body of its own, under its
	 * defaults (questions 8 to 10): what it proposes, or null when it cannot slim it with one
	 * return per piece.
	 */
	private SlimResult inner(List<Stmt> run) {
		int lo = run.get(0).start(), hi = run.get(run.size() - 1).end();
		SlimResult r = inner.computeIfAbsent(((long) lo << 32) | (hi & 0xffffffffL), k -> {
			MethodFacts body = body(lo, hi, run);
			if (!Slimmer.slim(body, Assessor.DEFAULTS.params()).feasible()) {
				return CANNOT; // the rest of the assessment is needed only for the proposal
			}
			Assessment a = Assessor.DEFAULTS.assess(body);
			return slimmed(a.status()) ? a.proposal() : CANNOT;
		});
		return r == CANNOT ? null : r;
	}

	/**
	 * A run of statements as a method of its own: its statements the body, its length their code
	 * lines. The method's variables are kept as they are, so what the run uses from before it is a
	 * parameter, priced as SinewSlim prices any (question 9), and what is read after it is a
	 * result; a field, and a call to the class's own methods, count only inside the run.
	 */
	private MethodFacts body(int lo, int hi, List<Stmt> run) {
		List<Var> vars = new ArrayList<>();
		for (Var v : facts.vars()) {
			if (v.kind() != Var.Kind.FIELD) {
				vars.add(v);
				continue;
			}
			int[] refs = within(v.refs(), lo, hi), defs = within(v.defs(), lo, hi), muts = within(v.muts(), lo, hi);
			if (refs.length + defs.length + muts.length > 0) {
				vars.add(new Var(v.name(), v.kind(), v.first(), v.literal(), refs, defs, muts));
			}
		}
		List<int[]> loops = new ArrayList<>();
		for (int[] l : facts.loops()) {
			if (lo <= l[0] && l[1] <= hi) {
				loops.add(l);
			}
		}
		return new MethodFacts(lo, new Stmt(lo, hi, true, StmtKind.BLOCK, run), vars, loops,
				within(facts.ownCallLines(), lo, hi), index.lines(lo, hi));
	}

	private static int[] within(int[] lines, int lo, int hi) {
		return Arrays.stream(lines).filter(x -> lo <= x && x <= hi).toArray();
	}

	/** Whether control never reaches the statement after it. */
	private static boolean ends(Stmt s) {
		return s.kind() == StmtKind.RETURN || s.kind() == StmtKind.THROW;
	}

	/**
	 * The carried values a function over [lo, hi] declares: SinewSlim's seam inputs (computed
	 * earlier in the method and touched here), less what is injected. A value computed earlier and
	 * only assigned here counts too: OfficeFloor needs an {@code Out} or {@code Var} declared to
	 * update it.
	 */
	private List<String> declares(int lo, int hi, Set<String> free) {
		Seam s = index.seam(lo, hi);
		if (free.isEmpty()) {
			return s.inputs();
		}
		List<String> out = new ArrayList<>();
		for (String v : s.inputs()) {
			if (!free.contains(v)) {
				out.add(v);
			}
		}
		return out;
	}

	// ------------------------------------------------------------------------------------------
	// expansions
	// ------------------------------------------------------------------------------------------

	/** The ways to cover one statement by more than one function. */
	private List<Part> expand(Stmt s, Set<String> free) {
		return switch (s.kind()) {
		case IF -> decisionIf(s, free);
		case SWITCH -> fallsThrough(s) ? List.of() : decisionSwitch(s, free);
		case TRY -> cuttable(s) ? tryParts(s, free) : List.of();
		case BLOCK -> new ArrayList<>(seq(s.kids(), free).values());
		default -> List.of();
		};
	}

	/** An if as a decision function: the condition, then a flow per branch. */
	private List<Part> decisionIf(Stmt s, Set<String> free) {
		Stmt then = s.kids().get(0);
		int hi = then.seq() ? then.start() : Math.max(s.start(), then.start() - 1);
		Function decision = decision(s.start(), hi, free);
		if (decision == null) {
			return List.of();
		}
		List<Map<Integer, Part>> branches = new ArrayList<>();
		branches.add(branch(then, free));
		if (s.kids().size() > 1) {
			branches.add(branch(s.kids().get(1), free));
		}
		return decide(decision, branches, s.kids().size() > 1 ? 0 : 1);
	}

	/** A switch as a decision function: the selector, then a flow per case (empty labels share the next's). */
	private List<Part> decisionSwitch(Stmt s, Set<String> free) {
		if (s.kids().isEmpty()) {
			return List.of();
		}
		Function decision = decision(s.start(), Math.max(s.start(), s.kids().get(0).start() - 1), free);
		if (decision == null) {
			return List.of();
		}
		List<Map<Integer, Part>> branches = new ArrayList<>();
		for (Stmt entry : s.kids()) {
			List<Stmt> body = new ArrayList<>(entry.kids());
			if (body.isEmpty()) {
				continue; // a label sharing the next case's body
			}
			if (body.get(body.size() - 1).kind() == StmtKind.BREAK) {
				body.remove(body.size() - 1); // the flow to the join
			}
			branches.add(seq(body, free));
		}
		// no default: when nothing matches, the decision flows straight to the join
		return decide(decision, branches, hasDefault(s) ? 0 : 1);
	}

	/**
	 * A decision and its branches: an edge into each branch that has a function, and the flows
	 * leaving the branches (and the decision itself, for a branch with none) leave the whole.
	 */
	private List<Part> decide(Function decision, List<Map<Integer, Part>> branches, int direct) {
		List<Part> combos = List.of(Part.of(decision, 1 + decision.seam(), 0, direct, decision.seam(), List.of()));
		for (Map<Integer, Part> options : branches) {
			if (options.isEmpty()) {
				return List.of();
			}
			Map<Integer, Part> next = new HashMap<>();
			for (Part c : combos) {
				for (Part b : options.values()) {
					Part d;
					if (b.functions == 0) { // straight to the join
						d = c.leaving();
					} else {
						d = c.beside(b, LinkKind.FLOW);
					}
					Part cur = next.get(d.exits);
					if (cur == null || better(d, cur)) {
						next.put(d.exits, d);
					}
				}
			}
			combos = new ArrayList<>(next.values());
		}
		return combos;
	}

	private Function decision(int lo, int hi, Set<String> free) {
		int lines = index.lines(lo, hi);
		List<String> declares = declares(lo, hi, free);
		if (lines > p.target() || declares.size() > limit) {
			return null;
		}
		return new Function(Kind.DECISION, lo, hi, lines, declares);
	}

	/** A branch: a sequence's statements, an else-if as its own decision, or one statement. */
	private Map<Integer, Part> branch(Stmt arm, Set<String> free) {
		if (arm.seq()) {
			return seq(arm.kids(), free);
		}
		return seq(List.of(arm), free);
	}

	/** A try cut across functions: its body, and each catch as a handler reached by an escalation per type. */
	private List<Part> tryParts(Stmt s, Set<String> free) {
		Try t = Try.at(tries, s.start(), s.end());
		List<Stmt> kids = s.kids();
		if (t == null || kids.size() != 1 + t.catches().size()) {
			return List.of(); // the tree and the shape disagree: do not cut it
		}
		Map<Integer, Part> body = seq(kids.get(0).kids(), free);
		List<Part> combos = new ArrayList<>(body.values());
		for (int c = 0; c < t.catches().size(); c++) {
			Catch clause = t.catches().get(c);
			Set<String> handlerFree = new HashSet<>(free);
			handlerFree.add(clause.parameter());
			Map<Integer, Part> handler = seq(kids.get(c + 1).kids(), handlerFree);
			Map<Integer, Part> next = new HashMap<>();
			for (Part a : combos) {
				for (Part h : handler.values()) {
					Part d = escalate(a, handler(h, kids.get(c + 1), clause));
					Part cur = next.get(d.exits);
					if (cur == null || better(d, cur)) {
						next.put(d.exits, d);
					}
				}
			}
			combos = new ArrayList<>(next.values());
		}
		return combos;
	}

	/** The try's parts beside a handler, with the escalation from the try's first function into it drawn once. */
	private static Part escalate(Part tried, Part handler) {
		Part d = tried.beside(handler, null);
		if (tried.functions == 0) {
			return d;
		}
		List<Link> links = new ArrayList<>(d.links);
		links.add(new Link(0, tried.fns.size(), LinkKind.ESCALATION));
		return d.with(d.fns, links);
	}

	/**
	 * A catch's body as a handler: its first function marked the handler (an empty body is a
	 * handler of its own), and an escalation edge per exception type the clause catches.
	 */
	private static Part handler(Part body, Stmt block, Catch clause) {
		if (body.fns.isEmpty()) {
			Part empty = Part.of(new Function(Kind.HANDLER, block.start(), block.end(), 0, List.of()), 1, 0, 1, 0,
					List.of());
			return new Part(1, 1 + clause.types(), clause.types(), 1, 0, 0, 0, empty.fns, empty.links, empty.outs,
					List.of());
		}
		List<Function> fns = new ArrayList<>(body.fns);
		Function f = fns.get(0);
		fns.set(0, new Function(Kind.HANDLER, f.lo(), f.hi(), f.lines(), f.declares(), f.inner()));
		return new Part(body.functions, body.cost + clause.types(), body.edges + clause.types(), body.exits, body.worst,
				body.pieces, body.extraction, fns, body.links, body.outs, body.returns);
	}

	// ------------------------------------------------------------------------------------------
	// rules
	// ------------------------------------------------------------------------------------------

	/** Whether a try can be cut: no resources, no finally, and its shape known. */
	private boolean cuttable(Stmt s) {
		Try t = Try.at(tries, s.start(), s.end());
		return t != null && !t.resources() && !t.hasFinally();
	}

	/**
	 * Whether a case other than the last runs on into the next: a colon case that does not end in a
	 * break, return, throw, continue or yield. Arrow cases never do; a switch whose shape is not
	 * known is taken to, so it is not cut.
	 */
	private boolean fallsThrough(Stmt s) {
		Switch shape = Switch.at(switches, s.start(), s.end());
		if (shape == null) {
			return true;
		}
		if (shape.arrows()) {
			return false;
		}
		List<Stmt> entries = s.kids();
		for (int i = 0; i < entries.size() - 1; i++) {
			List<Stmt> body = entries.get(i).kids();
			if (body.isEmpty()) {
				continue; // a shared label
			}
			StmtKind last = body.get(body.size() - 1).kind();
			if (last != StmtKind.BREAK && last != StmtKind.RETURN && last != StmtKind.THROW && last != StmtKind.CONTINUE
					&& last != StmtKind.YIELD) {
				return true;
			}
		}
		return false;
	}

	/** Whether a switch has a default case; without one, the decision also flows straight to the join. */
	private boolean hasDefault(Stmt s) {
		Switch shape = Switch.at(switches, s.start(), s.end());
		return shape != null && shape.hasDefault();
	}

	/** Whether no break, continue or yield leaves the run (one inside a loop or switch of the run stays). */
	private static boolean jumpsStay(List<Stmt> run) {
		for (Stmt s : run) {
			if (leaves(s, false, false)) {
				return false;
			}
		}
		return true;
	}

	private static boolean leaves(Stmt node, boolean inLoop, boolean inSwitch) {
		StmtKind k = node.kind();
		if ((k == StmtKind.BREAK && !(inLoop || inSwitch)) || (k == StmtKind.CONTINUE && !inLoop)
				|| (k == StmtKind.YIELD && !inSwitch)) {
			return true;
		}
		boolean loop = inLoop || k == StmtKind.LOOP, sw = inSwitch || k == StmtKind.SWITCH;
		for (Stmt c : node.kids()) {
			if (!(k == StmtKind.OTHER && c.kind() == StmtKind.BLOCK) // a lambda's body is its own
					&& leaves(c, loop, sw)) {
				return true;
			}
		}
		return false;
	}

	/** The returns inside a run, a lambda's own left out. */
	private static int returnsInside(List<Stmt> run) {
		int n = 0;
		for (Stmt s : run) {
			n += returns(s);
		}
		return n;
	}

	private static int returns(Stmt node) {
		int n = node.kind() == StmtKind.RETURN ? 1 : 0;
		for (Stmt c : node.kids()) {
			if (!(node.kind() == StmtKind.OTHER && c.kind() == StmtKind.BLOCK)) {
				n += returns(c);
			}
		}
		return n;
	}

	private static String name(StmtKind k) {
		return switch (k) {
		case OTHER -> "statement";
		case LOOP -> "loop";
		case SYNCHRONIZED -> "synchronized block";
		case LABELED -> "labeled statement";
		case ENTRY -> "switch entry";
		default -> k.name().toLowerCase();
		};
	}
}
