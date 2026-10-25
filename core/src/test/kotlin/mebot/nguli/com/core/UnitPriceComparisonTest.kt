package mebot.nguli.com.core

import org.junit.Assert.*
import org.junit.Test

class UnitPriceComparisonTest {
    @Test fun comparesDifferentPackSizesExactly() {
        val result=UnitPriceComparison.compare("2500","1","6000","3")!!
        assertEquals(1,result.cheaper)
        assertEquals("2500",UnitPriceComparison.display(result.firstPerUnit))
        assertEquals("2000",UnitPriceComparison.display(result.secondPerUnit))
        assertEquals("20",UnitPriceComparison.display(result.savingPercent))
    }
    @Test fun equivalentUnitPricesAreTies() {
        assertEquals(0,UnitPriceComparison.compare("1","3","2","6")!!.cheaper)
    }
    @Test fun rejectsZeroNegativeMissingAmbiguousAndUnboundedInput() {
        listOf("","0","-3","1,000","NaN","1e5","1234567890123","1.1234567").forEach { bad ->
            assertNull(UnitPriceComparison.compare(bad,"1","5","2"))
            assertNull(UnitPriceComparison.compare("5",bad,"5","2"))
        }
    }
    @Test fun acceptsDecimalsAndComparesBeforeRounding() {
        assertEquals(-1,UnitPriceComparison.compare("0.000001","999999999999","0.000002","999999999999")!!.cheaper)
        assertEquals(-1,UnitPriceComparison.compare(" 2.5 ","0.5","12","2")!!.cheaper)
    }
}
