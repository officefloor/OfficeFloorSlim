package net.officefloor.officefloorslim.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import net.officefloor.officefloorslim.core.OfficeFloorSplit.Function;
import net.officefloor.officefloorslim.core.OfficeFloorSplit.Kind;
import net.officefloor.officefloorslim.core.OfficeFloorSplit.Plan;
import net.officefloor.officefloorslim.core.OfficeFloorSplit.Reason;
import net.officefloor.sinewslim.core.Assessment.Status;
import net.officefloor.sinewslim.core.Assessor;
import net.officefloor.sinewslim.javaparser.JavaFacts;
import net.officefloor.sinewslim.javaparser.MethodUnit;
import net.officefloor.sinewslim.javaparser.ParsedFile;

/**
 * The OfficeFloor split on methods small enough to work out by hand, each against the decided
 * cost model (DESIGN.md, The OfficeFloor split, questions 1 to 7): the seams are the carried
 * values, and the total is the seams, plus 1 per function, plus 1 per edge.
 */
class OfficeFloorSplitTest {

	static MethodUnit method(String body) {
		ParsedFile f = JavaFacts.parse("A.java", "class A {\n	int f(int a) {\n" + body + "	}\n}\n",
				List.of(ShapeExtractor.INSTANCE));
		assertTrue(f.parsed(), f.problem());
		return f.methods().get(0);
	}

	static Plan plan(String body) {
		MethodUnit m = method(body);
		return OfficeFloorSplit.plan(m.facts(), m.extras().get(ShapeExtractor.KEY).orElseThrow());
	}

	static String lines(String statement, int n) {
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < n; i++) {
			b.append("\t\t").append(statement.replace("#", String.valueOf(i))).append("\n");
		}
		return b.toString();
	}

	static List<Kind> kinds(Plan p) {
		return p.functions().stream().map(Function::kind).collect(Collectors.toList());
	}

	static int total(Plan p) {
		return p.functions().stream().mapToInt(Function::seam).sum() + p.functions().size() + p.edges();
	}

	@Test
	void underTheCapIsNotApplicable() {
		assertFalse(plan(lines("a += #;", 5) + "\t\treturn a;\n").applicable());
	}

	@Test
	void straightLineSplitsInTwoAndCarriesOneValue() {
		// 1 + 40 + 1 statements: two functions, the second declaring s, which the first computed
		Plan p = plan("\t\tint s = a * 2;\n" + lines("s += a * #;", 40) + "\t\treturn s;\n");
		assertTrue(p.feasible());
		assertEquals(List.of(Kind.RUN, Kind.RUN), kinds(p));
		assertEquals(List.of(List.of(), List.of("s")), p.functions().stream().map(Function::declares).toList());
		assertEquals(1, p.edges());
		assertEquals(1, p.worstSeam());
		assertEquals((0 + 1) + (1 + 1) + 1, p.total());
		assertEquals(p.total(), total(p));
	}

	@Test
	void aParameterHandedOnIsInjected() {
		// a is the method's parameter, read in both halves as it came: free (question 3)
		Plan p = plan(lines("System.out.println(a + #);", 40));
		assertEquals(0, p.worstSeam());
		assertEquals(1 + 1 + 1, p.total());
	}

	@Test
	void aLongLoopIsNotSlimmable() {
		Plan p = plan("\t\tint s = 0;\n\t\tfor (int i = 0; i < a; i++) {\n" + lines("s += i * #;", 30) + "\t\t}\n"
				+ "\t\treturn s;\n");
		assertTrue(p.applicable());
		assertFalse(p.feasible());
		assertEquals(Reason.LOOP, p.reason());
		assertTrue(p.detail().startsWith("the loop at lines 4-35"), p.detail());
	}

	@Test
	void guardClausesSplitWhereSinewSlimIsBlocked() {
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < 14; i++) {
			b.append("\t\tif (a == ").append(i).append(") {\n\t\t\treturn ").append(i).append(";\n\t\t}\n");
		}
		String body = b + "\t\treturn -1;\n";
		MethodUnit m = method(body);
		assertEquals(Status.NOT_SLIMMABLE, Assessor.DEFAULTS.assess(m.facts()).status()); // every run returns early
		Plan p = plan(body);
		assertTrue(p.feasible());
		assertEquals(2, p.functions().size()); // the guards in two runs, each return a flow to the end
		assertEquals(0, p.worstSeam());
		// the first run's returns are early: one flow to the end, and one edge on to the second run;
		// the second ends the method itself, so its own guards need no flow
		assertEquals(2, p.edges());
		assertEquals(p.total(), total(p));
	}

	@Test
	void aLongIfElseBecomesADecisionWithAJoin() {
		String body = "\t\tint s = a * 2;\n\t\tif (a > 0) {\n" + lines("s += a * #;", 20) + "\t\t} else {\n"
				+ lines("s -= a * #;", 20) + "\t\t}\n\t\treturn s;\n";
		Plan p = plan(body);
		assertTrue(p.feasible());
		// a decision is its own function: the first statement, the decision, each branch, the tail
		assertEquals(List.of(Kind.RUN, Kind.DECISION, Kind.RUN, Kind.RUN, Kind.RUN), kinds(p));
		// run -> decision, decision -> then, decision -> else, then -> tail, else -> tail (question 4)
		assertEquals(5, p.edges());
		assertEquals(List.of(List.of(), List.of(), List.of("s"), List.of("s"), List.of("s")),
				p.functions().stream().map(Function::declares).toList());
		assertEquals(3 + 5 + 5, p.total());
	}

	@Test
	void aBranchThatReturnsDoesNotReachTheJoin() {
		String body = "\t\tint s = a * 2;\n\t\tif (a > 0) {\n" + lines("s += a * #;", 20) + "\t\t\treturn s;\n"
				+ "\t\t} else {\n" + lines("s -= a * #;", 20) + "\t\t}\n\t\treturn s;\n";
		Plan p = plan(body);
		assertTrue(p.feasible());
		assertEquals(4, p.edges()); // as above, less the then branch's edge to the tail
	}

	@Test
	void aSwitchWithoutADefaultAlsoFlowsStraightToTheJoin() {
		String cases = "\t\tswitch (a) {\n\t\tcase 1:\n" + lines("s += #;", 15) + "\t\t\tbreak;\n\t\tcase 2:\n"
				+ lines("s -= #;", 15) + "\t\t\tbreak;\n";
		Plan without = plan("\t\tint s = a * 2;\n" + cases + "\t\t}\n\t\treturn s;\n");
		Plan with = plan("\t\tint s = a * 2;\n" + cases + "\t\tdefault:\n\t\t\ts = 0;\n\t\t}\n\t\treturn s;\n");
		assertTrue(without.feasible() && with.feasible());
		assertEquals(Kind.DECISION, without.functions().get(1).kind());
		// without: run->decision, two cases, two joins, and the decision's own flow to the join
		assertEquals(6, without.edges());
		// with a default: three cases, three joins, no flow straight to the join
		assertEquals(7, with.edges());
		assertEquals(with.functions().size(), without.functions().size() + 1);
	}

	@Test
	void anArrowSwitchIsCut() {
		Plan p = plan("\t\tint s = a * 2;\n\t\tswitch (a) {\n\t\tcase 1 -> {\n" + lines("s += #;", 15)
				+ "\t\t}\n\t\tdefault -> {\n" + lines("s -= #;", 15) + "\t\t}\n\t\t}\n\t\treturn s;\n");
		assertTrue(p.feasible());
		assertEquals(Kind.DECISION, p.functions().get(1).kind());
	}

	@Test
	void aLongTryIsCutAndItsCatchBecomesAHandler() {
		Plan p = plan("\t\tint s = a * 2;\n\t\ttry {\n" + lines("s += Integer.parseInt(\"#\");", 40)
				+ "\t\t} catch (IllegalStateException | NumberFormatException e) {\n\t\t\ts = e.hashCode();\n\t\t}\n"
				+ "\t\treturn s;\n");
		assertTrue(p.feasible());
		Function handler = p.functions().stream().filter(f -> f.kind() == Kind.HANDLER).findFirst().orElseThrow();
		// the exception is injected into its handler; s, computed earlier and updated here, is declared
		assertEquals(List.of("s"), handler.declares());
		assertEquals(List.of(Kind.RUN, Kind.RUN, Kind.RUN, Kind.HANDLER, Kind.RUN), kinds(p));
		// before -> body, body 1 -> body 2, the escalation's two edges (one per exception type,
		// question 5), body -> after, handler -> after
		assertEquals(6, p.edges());
		assertEquals((0 + 1 + 1 + 1 + 1) + 5 + 6, p.total());
	}

	@Test
	void aLongTryWithFinallyIsNotCut() {
		Plan p = plan("\t\tint s = a * 2;\n\t\ttry {\n" + lines("s += Integer.parseInt(\"#\");", 40)
				+ "\t\t} finally {\n\t\t\ts++;\n\t\t}\n\t\treturn s;\n");
		assertFalse(p.feasible());
		assertEquals(Reason.TRY, p.reason());
	}

	@Test
	void tooManyCarriedValues() {
		StringBuilder b = new StringBuilder();
		for (int v = 0; v < 7; v++) {
			b.append("\t\tint v").append(v).append(" = a * ").append(v + 2).append(";\n");
		}
		b.append(lines("System.out.println(\"#\");", 15));
		b.append(lines("a += v0 + v1 + v2 + v3 + v4 + v5 + v6 + #;", 25));
		Plan p = plan(b + "\t\treturn a;\n");
		assertFalse(p.feasible());
		assertEquals(Reason.CARRIED, p.reason());
	}
}
