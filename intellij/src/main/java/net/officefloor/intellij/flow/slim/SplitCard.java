package net.officefloor.intellij.flow.slim;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.officefloor.officefloorslim.core.MethodSplit;
import net.officefloor.officefloorslim.core.OfficeFloorSplit;
import net.officefloor.officefloorslim.core.Pricing;
import net.officefloor.sinewslim.core.Assessment;
import net.officefloor.sinewslim.core.SlimResult;
import net.officefloor.sinewslim.facts.Seam;

/**
 * What the diagram shows for a long function: the rows of its split preview card, worked out
 * from the same pricing {@code officefloorslim scan} reports, so the card and the scan agree.
 * Plain text, no drawing: the panel paints the rows.
 */
public final class SplitCard {

	/** How a row is drawn. */
	public enum Style {
		/** The card's title. */
		TITLE,
		/** A line of summary. */
		NOTE,
		/** A proposed function. */
		FUNCTION,
		/** A method SinewSlim extracts inside a function. */
		PIECE,
		/** Why there is no split. */
		BLOCKED
	}

	/**
	 * One row of the card.
	 *
	 * @param text  what it says
	 * @param style how it is drawn
	 * @param line  the source line a click goes to; 0 for none
	 */
	public record Row(String text, Style style, int line) {
	}

	private SplitCard() {
	}

	/** The badge on a long function's node: its length. */
	public static String badge(MethodSplit m) {
		return m.length() + " lines";
	}

	/** The rows of the card for a long method; empty for one under the cap. */
	public static List<Row> rows(MethodSplit m) {
		List<Row> rows = new ArrayList<>();
		if (!m.longMethod()) {
			return rows;
		}
		Pricing p = m.pricing();
		rows.add(new Row(m.name() + ": " + m.length() + " code lines, over the 25-line cap", Style.TITLE, m.nameLine()));
		rows.add(new Row("Extract Method (SinewSlim): " + sinewslim(p.conventional()), Style.NOTE, 0));

		OfficeFloorSplit.Plan c = p.combined();
		if (!c.feasible()) {
			rows.add(new Row("No split: " + name(c.reason().name()) + (c.detail() == null ? "" : ", " + c.detail()),
					Style.BLOCKED, 0));
			return rows;
		}
		if (c.extractionOnly()) {
			rows.add(new Row("Best as one function: Extract Method's plan, cost " + c.sum() + " (its "
					+ c.extraction() + " + 1 for the function)", Style.NOTE, 0));
		} else {
			rows.add(new Row("Split into " + plural(c.functions().size(), "function") + ", " + plural(c.edges(), "flow")
					+ (c.pieces() > 0 ? ", " + plural(c.pieces(), "extracted method") : "") + ": cost " + c.sum()
					+ (c.pieces() > 0 ? " (wiring " + c.total() + " + extraction " + c.extraction() + ")" : ""),
					Style.NOTE, 0));
			if (c.tieByPieces()) {
				rows.add(new Row("A tie with Extract Method alone, settled for fewer extracted methods", Style.NOTE, 0));
			}
		}
		int n = 0;
		for (OfficeFloorSplit.Function f : c.functions()) {
			n++;
			String declares = f.declares().isEmpty() ? "" : " · declares " + String.join(", ", f.declares());
			rows.add(new Row(n + ". " + kind(f.kind()) + ", lines " + f.lo() + "–" + f.hi() + " (" + f.lines() + " lines)"
					+ declares, Style.FUNCTION, f.lo()));
			SlimResult in = f.inner();
			if (in != null) {
				for (int i = 0; i < in.pieces(); i++) {
					Seam s = in.cuts().get(i);
					String io = (s.inputs().isEmpty() ? "" : " · in: " + String.join(", ", s.inputs()))
							+ (s.outputs().isEmpty() ? "" : " · out: " + String.join(", ", s.outputs()));
					rows.add(new Row("↳ extract lines " + s.lo() + "–" + s.hi() + " (" + in.pieceLines().get(i) + " lines)" + io,
							Style.PIECE, s.lo()));
				}
			}
		}
		return rows;
	}

	/** The tooltip line for a long function's node. */
	public static String summary(MethodSplit m) {
		if (!m.longMethod()) {
			return m.length() + " code lines";
		}
		OfficeFloorSplit.Plan c = m.pricing().combined();
		String split = !c.feasible() ? "no split (" + name(c.reason().name()) + ")"
				: c.extractionOnly() ? "best as one function, with Extract Method"
						: plural(c.functions().size(), "function") + ", cost " + c.sum();
		return m.length() + " code lines, over the cap: " + split;
	}

	static String sinewslim(Assessment a) {
		SlimResult s = a.proposal();
		String status = name(a.status().name());
		if (s.applicable() && s.feasible()) {
			return status + ", cost " + s.cost() + " (" + plural(s.pieces(), "method") + ")";
		}
		return a.obstacle() == null ? status : status + ": " + a.obstacle().reason();
	}

	private static String kind(OfficeFloorSplit.Kind k) {
		return switch (k) {
		case RUN -> "function";
		case DECISION -> "decision";
		case HANDLER -> "handler";
		};
	}

	private static String plural(int n, String noun) {
		return n + " " + noun + (n == 1 ? "" : "s");
	}

	private static String name(String constant) {
		return constant.toLowerCase(Locale.ROOT).replace('_', ' ');
	}
}
