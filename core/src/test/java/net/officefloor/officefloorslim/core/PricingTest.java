package net.officefloor.officefloorslim.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import net.officefloor.officefloorslim.core.Pricing.Category;
import net.officefloor.sinewslim.core.Assessment.Status;

/** The categories, tested conventional first (PLAN.md, Decided): 2, then 1, then 3, else 4. */
class PricingTest {

	@Test
	void recordsBeforeAnything() {
		assertEquals(Category.RECORDS, Pricing.category(Status.SLIMMABLE_WITH_RECORD, () -> true, true));
	}

	@Test
	void restructureBeforeFunctionInjection() {
		assertEquals(Category.RESTRUCTURE, Pricing.category(Status.NOT_SLIMMABLE, () -> true, true));
	}

	@Test
	void functionInjectionOnlyWhenNothingConventionalDoes() {
		assertEquals(Category.FUNCTION_INJECTION, Pricing.category(Status.NOT_SLIMMABLE, () -> false, true));
		assertEquals(Category.REDESIGN, Pricing.category(Status.NOT_SLIMMABLE, () -> false, false));
	}

	@Test
	void slimmableIsNoCategory() {
		for (Status s : new Status[] { Status.UNDER_CAP, Status.SLIMMABLE, Status.SLIMMABLE_BUT_LOCKED }) {
			assertNull(Pricing.category(s, () -> {
				throw new AssertionError("not asked");
			}, true), s.name());
		}
	}
}
