package net.officefloor.officefloorslim.core;

import static net.officefloor.officefloorslim.core.OfficeFloorSplitTest.lines;
import static net.officefloor.officefloorslim.core.OfficeFloorSplitTest.method;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.officefloor.officefloorslim.core.OfficeFloorSplit.Function;
import net.officefloor.officefloorslim.core.OfficeFloorSplit.Plan;
import net.officefloor.officefloorslim.core.OfficeFloorSplit.Reason;
import net.officefloor.sinewslim.core.Assessment;
import net.officefloor.sinewslim.core.Assessment.Status;
import net.officefloor.sinewslim.core.Assessor;
import net.officefloor.sinewslim.javaparser.MethodUnit;

/**
 * The combined split ({@code officefloorslim-split/2}) on methods small enough to work out by
 * hand, each against its decided questions (DESIGN.md, The combined split, 8 to 11): functions
 * cut by the injection split's rules, SinewSlim's extractions inside any function over the target,
 * the plan the smallest sum of the two totals, then the fewer pieces, then the fewer functions.
 */
class CombinedSplitTest {

	static Plan combined(String body) {
		MethodUnit m = method(body);
		return OfficeFloorSplit.combined(m.facts(), m.extras().get(ShapeExtractor.KEY).orElseThrow());
	}

	static Plan injection(String body) {
		return OfficeFloorSplitTest.plan(body);
	}

	static Assessment sinewslim(String body) {
		return Assessor.DEFAULTS.assess(method(body).facts());
	}

	@Test
	void underTheCapIsNotApplicable() {
		assertFalse(combined(lines("a += #;", 5) + "\t\treturn a;\n").applicable());
	}

	/**
	 * A loop longer than the target blocks the injection split outright. The combined split keeps
	 * it whole in one function and SinewSlim extracts inside it: here the whole method as one
	 * function, SinewSlim's own plan, at its cost plus 1 for the function, with nothing carried and
	 * no flow (the method's returns are its own).
	 */
	@Test
	void aLongLoopIsKeptWholeAndSlimmedInside() {
		String body = "\t\tint s = 0;\n\t\tfor (int i = 0; i < a; i++) {\n" + lines("s += i * #;", 30) + "\t\t}\n"
				+ "\t\treturn s;\n";
		assertEquals(Reason.LOOP, injection(body).reason());
		Assessment conventional = sinewslim(body);
		assertEquals(Status.SLIMMABLE, conventional.status());

		Plan p = combined(body);
		assertTrue(p.feasible());
		assertTrue(p.extractionOnly());
		assertEquals(1, p.total()); // one function, nothing carried, no edge
		assertEquals(conventional.proposal().cost(), p.extraction());
		assertEquals(conventional.proposal().pieces(), p.pieces());
		assertEquals(conventional.proposal().cost() + 1, p.sum());
		assertFalse(p.tieByPieces());
	}

	/**
	 * Guard clauses block SinewSlim (every run returns early); a long loop after them blocks the
	 * injection split. Together: the guards as functions whose returns are flows, and the loop
	 * kept whole in a function of its own, with SinewSlim's pieces inside it. Neither method alone
	 * slims it.
	 */
	@Test
	void injectionAndExtractionTogetherWhereNeitherAloneCan() {
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < 10; i++) {
			b.append("\t\tif (a == ").append(i).append(") {\n\t\t\treturn ").append(i).append(";\n\t\t}\n");
		}
		String body = b + "\t\tint s = 0;\n\t\tfor (int i = 0; i < a; i++) {\n" + lines("s += i * #;", 30) + "\t\t}\n"
				+ "\t\treturn s;\n";
		assertEquals(Status.NOT_SLIMMABLE, sinewslim(body).status());
		assertFalse(injection(body).feasible());

		Plan p = combined(body);
		assertTrue(p.feasible(), String.valueOf(p));
		assertFalse(p.extractionOnly());
		int loopStart = 3 + 30 + 2; // the class and signature lines, the guards, int s = 0
		Function withLoop = p.functions().stream().filter(f -> f.lo() <= loopStart && loopStart <= f.hi()).findFirst()
				.orElseThrow();
		assertNotNull(withLoop.inner());
		assertTrue(withLoop.pieces() > 0);
		assertEquals(withLoop.inner().cost(), withLoop.extraction());
		// the guards' functions are under the target and slimmed by nothing
		for (Function f : p.functions()) {
			if (f != withLoop) {
				assertEquals(0, f.pieces());
			}
		}
		assertEquals(p.total() + p.extraction(), p.sum());
		assertEquals(p.functions().stream().mapToInt(Function::extraction).sum(), p.extraction());
	}

	/**
	 * A loop whose body SinewSlim cannot slim (every statement continues the loop, so no run of
	 * them can move) makes every cut that holds it infeasible (question 10), and the reason says
	 * what SinewSlim found in it as a function body.
	 */
	@Test
	void aCutIsRefusedWhenSinewSlimCannotSlimTheFunctionsBody() {
		String body = "\t\tint s = 0;\n\t\tfor (int i = 0; i < a; i++) {\n"
				+ lines("if (i == #) { s += #; continue; }", 30) + "\t\t}\n\t\treturn s;\n";
		Plan p = combined(body);
		assertTrue(p.applicable());
		assertFalse(p.feasible());
		assertEquals(Reason.LOOP, p.reason());
		assertTrue(p.detail().startsWith("the loop at lines 4-35"), p.detail());
		assertTrue(p.detail().contains("as a function body, SinewSlim: "), p.detail());
	}

	/**
	 * A body SinewSlim slims only with a record is refused (question 10). Every line of the loop
	 * writes x and y, which the next pass reads, so every piece inside the loop returns two values:
	 * SinewSlim needs a record. The loop is never cut between functions, so the function holding it
	 * would need that record: there is no plan, and the reason says what SinewSlim found.
	 */
	@Test
	void aBodyNeedingARecordIsRefused() {
		String body = "\t\tint x = 0;\n\t\tint y = 0;\n\t\tfor (int i = 0; i < a; i++) {\n"
				+ lines("x += y * #; y += x * #;", 30) + "\t\t}\n\t\treturn x + y;\n";
		assertEquals(Status.SLIMMABLE_WITH_RECORD, sinewslim(body).status());

		Plan p = combined(body);
		assertFalse(p.feasible());
		assertEquals(Reason.LOOP, p.reason());
		assertTrue(p.detail().contains("as a function body, SinewSlim: every cut carries two or more results"),
				p.detail());
	}

	/**
	 * The smallest sum wins, here extraction (question 11). Straight-line code carrying one value:
	 * the injection split's two functions cost (0+1) + (1+1) + 1 edge = 4, s declared by the second.
	 * SinewSlim's one piece runs to the end, its return included, so it returns nothing to the
	 * method: s in, and 1 for the piece, 2; the method as one function is 2 + 1 = 3. Three is less
	 * than four: extraction only, no tie. (Were the lines to read the parameter a as well, the
	 * piece would pay for it, 3 + 1 = 4, and tie: injected values are free to functions only.)
	 */
	@Test
	void theSmallestSumWinsHereExtraction() {
		String body = "\t\tint s = a * 2;\n" + lines("s += #;", 40) + "\t\treturn s;\n";
		assertEquals(4, injection(body).total());
		Assessment conventional = sinewslim(body);
		assertEquals(Status.SLIMMABLE, conventional.status());
		assertEquals(1, conventional.proposal().pieces());
		assertEquals(2, conventional.proposal().cost()); // s in; nothing out; the piece

		Plan p = combined(body);
		assertTrue(p.extractionOnly());
		assertEquals(3, p.sum());
		assertEquals(1, p.total());
		assertEquals(2, p.extraction());
		assertFalse(p.tieByPieces());
	}

	/**
	 * A tie on the sum, settled by the fewer pieces (question 11). A parameter handed on is
	 * injected and free to a function (question 3), but a piece pays for it as an input (question
	 * 9). Injection: two functions and an edge, 1 + 1 + 1 = 3, no pieces. SinewSlim: one piece
	 * taking a, 1 + 1 = 2, and the function around the method, 3. Equal sums: the fewer pieces
	 * wins, so injection is chosen; ordered by functions first the one function would have been,
	 * so the tie is counted.
	 */
	@Test
	void aTieOnTheSumGoesToTheFewerPieces() {
		String body = lines("System.out.println(a + #);", 40);
		Assessment conventional = sinewslim(body);
		assertEquals(2, conventional.proposal().cost()); // a in; the piece

		Plan p = combined(body);
		assertEquals(2, p.functions().size());
		assertEquals(3, p.total());
		assertEquals(0, p.pieces());
		assertEquals(3, p.sum());
		assertTrue(p.tieByPieces());
		assertFalse(p.extractionOnly());
	}

	/** The method as one function is never more than SinewSlim's cost plus 1, wherever SinewSlim slims it. */
	@Test
	void neverMoreThanSinewSlimPlusOne() {
		for (String body : List.of( //
				"\t\tint s = a * 2;\n" + lines("s += a * #;", 40) + "\t\treturn s;\n", //
				"\t\tint s = 0;\n\t\tfor (int i = 0; i < a; i++) {\n" + lines("s += i * #;", 30) + "\t\t}\n\t\treturn s;\n", //
				"\t\tif (a < 0) {\n\t\t\treturn 0;\n\t\t}\n\t\tint s = 0;\n" + lines("s += a * #;", 40) + "\t\treturn s;\n")) {
			Assessment conventional = sinewslim(body);
			if (conventional.status() == Status.SLIMMABLE || conventional.status() == Status.SLIMMABLE_BUT_LOCKED) {
				Plan p = combined(body);
				assertTrue(p.feasible());
				assertTrue(p.sum() <= conventional.proposal().cost() + 1, body);
			}
		}
	}
}
