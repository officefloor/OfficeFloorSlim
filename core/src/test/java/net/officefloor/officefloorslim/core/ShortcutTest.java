package net.officefloor.officefloorslim.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import net.officefloor.officefloorslim.core.OfficeFloorSplit.Plan;
import net.officefloor.sinewslim.javaparser.JavaFacts;
import net.officefloor.sinewslim.javaparser.MethodUnit;
import net.officefloor.sinewslim.javaparser.ParsedFile;

/**
 * The combined split's shortcut ({@code OfficeFloorSplit.provablyBest}): the whole method as one
 * function, taken without the search when no other plan can match its total, is the plan the
 * search chooses.
 */
class ShortcutTest {

	/** The method {@code int f(int a)} with this body, in a class with the fields {@code F} and {@code G}. */
	static Plan combined(String body) {
		ParsedFile f = JavaFacts.parse("A.java",
				"class A {\n\tint[] F = new int[9000];\n\tjava.util.Map<String, Integer> G = new java.util.HashMap<>();\n"
						+ "\tint f(int a) {\n" + body + "\t}\n}\n",
				List.of(ShapeExtractor.INSTANCE));
		assertTrue(f.parsed(), f.problem());
		MethodUnit m = f.methods().get(0);
		return OfficeFloorSplit.combined(m.facts(), m.extras().get(ShapeExtractor.KEY).orElseThrow());
	}

	/** The plan with the shortcut, and without it. */
	static Plan[] both(String body) {
		OfficeFloorSplit.SHORTCUT = true;
		Plan with = combined(body);
		OfficeFloorSplit.SHORTCUT = false;
		try {
			return new Plan[] { with, combined(body) };
		} finally {
			OfficeFloorSplit.SHORTCUT = true;
		}
	}

	/** A generated table: entries put in a field, one statement each. */
	static String table(int n) {
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < n; i++) {
			b.append(i % 2 == 0 ? "\t\tF[" + i + "] = " + (i * 7 % 600) + ";\n" : "\t\tG.put(\"k" + i + "\", " + i + ");\n");
		}
		return b.append("\t\treturn a;\n").toString();
	}

	/**
	 * A random flat (or nearly flat) method: a table in a field or in a local, values carried
	 * along, loops, and when {@code ifs} now and then an if.
	 */
	static String method(Random r, int n, boolean ifs) {
		StringBuilder b = new StringBuilder("\t\tint[] t = new int[9000];\n");
		List<String> locals = new ArrayList<>();
		int fieldShare = r.nextInt(4); // how much of the table is in the field: none to most
		for (int i = 0; i < n; i++) {
			int pick = r.nextInt(ifs ? 10 : 9);
			switch (pick) {
			case 0, 1, 2, 3 -> b.append(r.nextInt(4) < fieldShare ? "\t\tF[" + i + "] = " + r.nextInt(600) + ";\n"
					: "\t\tt[" + i + "] = " + r.nextInt(600) + ";\n");
			case 4 -> b.append("\t\tSystem.out.println(\"" + i + "\");\n");
			case 5 -> b.append("\t\ta += " + i + ";\n");
			case 6 -> {
				String v = "v" + i;
				b.append("\t\tint " + v + " = a + " + i + ";\n");
				locals.add(v);
			}
			case 7 -> b.append(locals.isEmpty() ? "\t\ta -= 1;\n"
					: "\t\ta += " + locals.get(r.nextInt(locals.size())) + ";\n");
			case 8 -> b.append("\t\tfor (int i = 0; i < " + i + "; i++) {\n\t\t\tF[i] += i;\n\t\t}\n");
			default -> b.append("\t\tif (a > " + i + ") {\n\t\t\ta = " + i + ";\n\t\t}\n");
			}
		}
		return b.append("\t\treturn a;\n").toString();
	}

	@Test
	void theShortcutsPlanIsTheSearchs() {
		Random r = new Random(20261103);
		int methods = 0, fired = 0;
		List<String> differ = new ArrayList<>();
		for (int i = 0; i < 400; i++) {
			int n = 20 + r.nextInt(i % 10 == 0 ? 200 : 70);
			String body = i % 4 == 0 ? table(n) : method(r, n, i % 3 == 0);
			int before = OfficeFloorSplit.SHORTCUTS.get();
			Plan[] p = both(body);
			fired += OfficeFloorSplit.SHORTCUTS.get() - before;
			methods++;
			if (!p[0].equals(p[1]) && differ.size() < 5) {
				differ.add(p[0] + "\n  !=\n" + p[1] + "\nfor\n" + body);
			}
		}
		assertEquals(List.of(), differ);
		assertTrue(fired > 50, "too few shortcuts to show anything: " + fired + " of " + methods);
		System.out.println("ShortcutTest: " + methods + " methods, " + fired + " by the shortcut: all the search's plan");
	}

	/** A generated table that fits one function: the shortcut takes it, in well under a second. */
	@Test
	void aTableThatFitsOneFunctionIsTakenWithoutTheSearch() {
		int before = OfficeFloorSplit.SHORTCUTS.get();
		Plan plan = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> combined(table(400)));
		assertEquals(1, plan.functions().size());
		assertEquals(before + 1, OfficeFloorSplit.SHORTCUTS.get());
	}

	/**
	 * A generated table too long for one function (a flat run holds about 25 × 25 lines at most):
	 * searched, its walks ending early. 0.5.0 took over two minutes at 800 statements, and hours
	 * at a few thousand.
	 */
	@Test
	void aTableTooLongForOneFunctionIsPlannedInMinutes() {
		Plan plan = assertTimeoutPreemptively(Duration.ofSeconds(180), () -> combined(table(1000)));
		assertTrue(plan.feasible());
		assertTrue(plan.functions().size() >= 2, plan.functions().size() + " functions");
	}
}
