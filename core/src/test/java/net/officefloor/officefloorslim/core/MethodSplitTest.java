package net.officefloor.officefloorslim.core;

import static net.officefloor.officefloorslim.core.OfficeFloorSplitTest.lines;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** One file's methods priced as the scan prices them, for the plugin. */
class MethodSplitTest {

	static final String SOURCE = "class A {\n" //
			+ "	int small(int a) {\n		return a;\n	}\n" // lines 2-4
			+ "	int big(int a) {\n" // line 5
			+ "		int s = 0;\n		for (int i = 0; i < a; i++) {\n" + lines("s += i * #;", 30) + "		}\n"
			+ "		return s;\n	}\n}\n";

	@Test
	void everyMethodLongOnesPriced() {
		List<MethodSplit> ms = MethodSplit.of("A.java", SOURCE);
		assertEquals(List.of("small", "big"), ms.stream().map(MethodSplit::name).toList());
		assertFalse(ms.get(0).longMethod());
		assertNull(ms.get(0).pricing());
		assertTrue(ms.get(1).longMethod());
		assertEquals(5, ms.get(1).nameLine());
	}

	@Test
	void theLongMethodAsTheScanPricesIt() {
		MethodSplit big = MethodSplit.at("A.java", SOURCE, 5);
		assertNotNull(big);
		// the injection split cannot cut the loop; the combined split keeps it in one function
		assertFalse(big.pricing().officefloor().feasible());
		assertTrue(big.pricing().combined().feasible());
		assertTrue(big.pricing().combined().extractionOnly());
		assertEquals(big.pricing().conventional().proposal().cost() + 1, big.pricing().combined().sum());
	}

	@Test
	void noMethodOnTheLineOrNoParse() {
		assertNull(MethodSplit.at("A.java", SOURCE, 3));
		assertNull(MethodSplit.at("A.java", "class {", 1));
	}
}
