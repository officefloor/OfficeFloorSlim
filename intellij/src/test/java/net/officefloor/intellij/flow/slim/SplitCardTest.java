package net.officefloor.intellij.flow.slim;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import net.officefloor.intellij.flow.slim.SplitCard.Row;
import net.officefloor.intellij.flow.slim.SplitCard.Style;
import net.officefloor.officefloorslim.core.MethodSplit;

/** The split preview card's rows, from the same pricing the scan reports. */
public class SplitCardTest {

	private static String lines(String statement, int n) {
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < n; i++) {
			b.append("\t\t").append(statement.replace("#", String.valueOf(i))).append("\n");
		}
		return b.toString();
	}

	private static MethodSplit method(String body) {
		String source = "class A {\n\tint f(int a) {\n" + body + "\t}\n}\n";
		return MethodSplit.at("A.java", source, 2);
	}

	@Test
	public void underTheCapHasNoCard() {
		MethodSplit m = method("\t\treturn a;\n");
		assertTrue(SplitCard.rows(m).isEmpty());
		assertEquals("3 code lines", SplitCard.summary(m));
	}

	@Test
	public void guardsAndALoopSplitIntoFunctionsWithAnExtractionInside() {
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < 10; i++) {
			b.append("\t\tif (a == ").append(i).append(") {\n\t\t\treturn ").append(i).append(";\n\t\t}\n");
		}
		MethodSplit m = method(b + "\t\tint s = 0;\n\t\tfor (int i = 0; i < a; i++) {\n" + lines("s += i * #;", 30)
				+ "\t\t}\n\t\treturn s;\n");
		List<Row> rows = SplitCard.rows(m);
		assertEquals(Style.TITLE, rows.get(0).style());
		assertEquals(2, rows.get(0).line());
		assertTrue(rows.get(1).text(), rows.get(1).text().startsWith("Extract Method (SinewSlim): not slimmable"));
		assertTrue(rows.get(2).text(), rows.get(2).text().startsWith("Split into "));
		assertTrue(rows.stream().filter(r -> r.style() == Style.FUNCTION).count() >= 2);
		Row piece = rows.stream().filter(r -> r.style() == Style.PIECE).findFirst().orElseThrow();
		assertTrue(piece.text(), piece.text().startsWith("↳ extract lines "));
		assertTrue(piece.line() > 0);
		// every function row goes to its first line
		rows.stream().filter(r -> r.style() == Style.FUNCTION).forEach(r -> assertTrue(r.line() > 2));
		assertEquals(m.length() + " lines", SplitCard.badge(m));
	}

	@Test
	public void aLoopAloneIsBestAsOneFunction() {
		MethodSplit m = method("\t\tint s = 0;\n\t\tfor (int i = 0; i < a; i++) {\n" + lines("s += i * #;", 30)
				+ "\t\t}\n\t\treturn s;\n");
		List<Row> rows = SplitCard.rows(m);
		assertTrue(rows.get(2).text(), rows.get(2).text().startsWith("Best as one function: Extract Method's plan, cost "));
		assertTrue(SplitCard.summary(m).endsWith("best as one function, with Extract Method"));
	}

	@Test
	public void noSplitSaysWhy() {
		MethodSplit m = method("\t\tint s = 0;\n\t\tfor (int i = 0; i < a; i++) {\n"
				+ lines("if (i == #) { s += #; continue; }", 30) + "\t\t}\n\t\treturn s;\n");
		List<Row> rows = SplitCard.rows(m);
		Row last = rows.get(rows.size() - 1);
		assertEquals(Style.BLOCKED, last.style());
		assertTrue(last.text(), last.text().startsWith("No split: loop, the loop at lines "));
		assertFalse(SplitCard.summary(m).contains("cost"));
	}
}
