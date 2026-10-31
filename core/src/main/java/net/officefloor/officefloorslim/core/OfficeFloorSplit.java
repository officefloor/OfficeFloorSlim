package net.officefloor.officefloorslim.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.officefloor.officefloorslim.core.MethodShape.Catch;
import net.officefloor.officefloorslim.core.MethodShape.Switch;
import net.officefloor.officefloorslim.core.MethodShape.Try;
import net.officefloor.sinewslim.facts.MethodFacts;
import net.officefloor.sinewslim.facts.MethodIndex;
import net.officefloor.sinewslim.facts.Seam;
import net.officefloor.sinewslim.facts.Stmt;
import net.officefloor.sinewslim.facts.StmtKind;

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

	public enum Kind {
		/** A run of whole statements. */
		RUN,
		/** The condition of an {@code if} or {@code switch}, whose branches are flows. */
		DECISION,
		/** The first function of a catch's body, which an escalation reaches. */
		HANDLER
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
	 */
	public record Function(Kind kind, int lo, int hi, int lines, List<String> declares) {

		public int seam() {
			return declares.size();
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
	 * @param total      the seams, plus 1 per function, plus 1 per edge
	 */
	public record Plan(boolean applicable, boolean feasible, Reason reason, String detail, List<Function> functions,
			int edges, int worstSeam, int total) {

		static final Plan NOT_LONG = new Plan(false, true, null, null, List.of(), 0, 0, 0);

		public Plan {
			functions = List.copyOf(functions);
		}
	}

	/** A way of covering some statements: its functions, cost and edges so far, and the flows leaving it. */
	private record Part(int functions, int cost, int edges, int exits, int worst, List<Function> fns) {

		static final Part EMPTY = new Part(0, 0, 0, 1, 0, List.of());

		boolean better(Part o) {
			return functions != o.functions ? functions < o.functions : cost < o.cost;
		}

		/** This, then {@code next}: the flows leaving this are the edges into {@code next}. */
		Part then(Part next) {
			if (functions == 0) {
				return next;
			}
			if (next.functions == 0) {
				return new Part(functions, cost, edges, exits, worst, fns);
			}
			List<Function> all = new ArrayList<>(fns);
			all.addAll(next.fns);
			return new Part(functions + next.functions, cost + next.cost + exits, edges + next.edges + exits, next.exits,
					Math.max(worst, next.worst), all);
		}
	}

	private final MethodFacts facts;
	private final MethodIndex index;
	private final Params p;
	private final List<Try> tries;
	private final List<Switch> switches;
	private int limit;
	private final Map<Long, Map<Integer, Part>> memo = new HashMap<>();

	private OfficeFloorSplit(MethodFacts facts, MethodShape shape, Params p) {
		this.facts = facts;
		this.index = new MethodIndex(facts, true); // a parameter handed on as it came is injected
		this.p = p;
		this.tries = shape == null ? List.of() : shape.tries();
		this.switches = shape == null ? List.of() : shape.switches();
	}

	public static Plan plan(MethodFacts facts, MethodShape shape, Params p) {
		OfficeFloorSplit s = new OfficeFloorSplit(facts, shape, p);
		int length = facts.length() > 0 ? facts.length() : s.index.codeLines();
		if (length < p.trigger()) {
			return Plan.NOT_LONG;
		}
		for (int w = 0; w <= p.maxCarried(); w++) {
			Part best = s.solve(w);
			if (best != null) {
				return new Plan(true, true, null, null, best.fns, best.edges, best.worst, best.cost);
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
			if (best == null || c.better(best)) {
				best = c; // the flows leaving the method go nowhere, so cost nothing
			}
		}
		return best;
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
		return new Plan(true, false, r == null ? Reason.JUMPS : r, detail, List.of(), 0, 0, 0);
	}

	/** The first statement over the target that cannot be expanded, and why; null when every one can. */
	private Reason blocking(Stmt node, Stmt[] at) {
		for (Stmt k : node.seq() ? node.kids() : List.of(node)) {
			if (index.lines(k.start(), k.end()) <= p.target()) {
				continue;
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
				if (run == null && index.lines(kids.get(j).start(), kids.get(i - 1).end()) > p.target()) {
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

	private static void join(Map<Integer, Part> into, Map<Integer, Part> before, Part next) {
		for (Part b : before.values()) {
			Part c = b.then(next);
			Part cur = into.get(c.exits);
			if (cur == null || c.better(cur)) {
				into.put(c.exits, c);
			}
		}
	}

	/** Statements j..i-1 as one function; null when they do not fit or a jump leaves them. */
	private Part run(List<Stmt> run, Set<String> free) {
		Stmt first = run.get(0), last = run.get(run.size() - 1);
		int lines = index.lines(first.start(), last.end());
		if (lines > p.target() || !jumpsStay(run)) {
			return null;
		}
		List<String> declares = declares(first.start(), last.end(), free);
		if (declares.size() > limit) {
			return null;
		}
		Function f = new Function(Kind.RUN, first.start(), last.end(), lines, declares);
		boolean ends = ends(last);
		// a return before the end skips what follows: one flow, to the end; a function that ends the
		// flow itself has nothing after it to skip
		int early = !ends && returnsInside(run) > 0 ? 1 : 0;
		return new Part(1, 1 + declares.size() + early, early, ends ? 0 : 1, declares.size(), List.of(f));
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
		List<Part> combos = List.of(new Part(1, 1 + decision.seam(), 0, direct, decision.seam(), List.of(decision)));
		for (Map<Integer, Part> options : branches) {
			if (options.isEmpty()) {
				return List.of();
			}
			Map<Integer, Part> next = new HashMap<>();
			for (Part c : combos) {
				for (Part b : options.values()) {
					Part d;
					if (b.functions == 0) {
						d = new Part(c.functions, c.cost, c.edges, c.exits + 1, c.worst, c.fns); // straight to the join
					} else {
						List<Function> fns = new ArrayList<>(c.fns);
						fns.addAll(b.fns);
						d = new Part(c.functions + b.functions, c.cost + b.cost + 1, c.edges + b.edges + 1,
								c.exits + b.exits, Math.max(c.worst, b.worst), fns);
					}
					Part cur = next.get(d.exits);
					if (cur == null || d.better(cur)) {
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
					Part withHandler = handler(h, kids.get(c + 1), clause);
					List<Function> fns = new ArrayList<>(a.fns);
					fns.addAll(withHandler.fns);
					Part d = new Part(a.functions + withHandler.functions, a.cost + withHandler.cost,
							a.edges + withHandler.edges, a.exits + withHandler.exits, Math.max(a.worst, withHandler.worst),
							fns);
					Part cur = next.get(d.exits);
					if (cur == null || d.better(cur)) {
						next.put(d.exits, d);
					}
				}
			}
			combos = new ArrayList<>(next.values());
		}
		return combos;
	}

	/**
	 * A catch's body as a handler: its first function marked the handler (an empty body is a
	 * handler of its own), and an escalation edge per exception type the clause catches.
	 */
	private static Part handler(Part body, Stmt block, Catch clause) {
		List<Function> fns = new ArrayList<>(body.fns);
		int functions = body.functions, cost = body.cost, exits = body.exits;
		if (fns.isEmpty()) {
			fns.add(new Function(Kind.HANDLER, block.start(), block.end(), 0, List.of()));
			functions = 1;
			cost = 1;
			exits = 1;
		} else {
			Function f = fns.get(0);
			fns.set(0, new Function(Kind.HANDLER, f.lo(), f.hi(), f.lines(), f.declares()));
		}
		return new Part(functions, cost + clause.types(), body.edges + clause.types(), exits, body.worst, fns);
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
