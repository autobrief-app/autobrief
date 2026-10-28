package app.autobrief.car

import app.autobrief.car.domain.FuelReimbursement
import app.autobrief.car.domain.FuelReimbursement.Missing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 유류비 정산 of one calendar month: business km at the car's average economy and the entered fuel price. */
class FuelReimbursementTest {
    @Test fun theCardExampleIs42580Won() {
        val r = FuelReimbursement.compute(312.4, 8.26, 1_650)
        assertEquals(25.80424, r.liters!!, 1e-9)
        assertEquals(42_580L, r.amountKrw) // 42,577 rounded half-up to 10 won
        assertEquals(12.1, FuelReimbursement.kmPerLiter(r.litersPer100Km!!), 0.05)
        assertNull(r.missing)
    }

    @Test fun anEconomyEnteredInKmPerLiterGivesTheSameAmount() {
        val l100 = FuelReimbursement.litersPer100Km(12.1)!!
        assertEquals(42_580L, FuelReimbursement.compute(312.4, l100, 1_650).amountKrw)
        assertNull(FuelReimbursement.litersPer100Km(0.0))
        assertNull(FuelReimbursement.litersPer100Km(-3.0))
        assertNull(FuelReimbursement.litersPer100Km(Double.NaN))
    }

    @Test fun noBusinessDistanceIsZeroWonNotAMissingValue() {
        val r = FuelReimbursement.compute(0.0, 8.26, 1_650)
        assertEquals(0L, r.amountKrw)
        assertNull(r.missing)
    }

    @Test fun withoutAPriceThereIsNoAmount() {
        listOf(null, 0, -1).forEach { price ->
            val r = FuelReimbursement.compute(312.4, 8.26, price)
            assertNull(r.amountKrw)
            assertEquals(Missing.PRICE, r.missing)
        }
    }

    @Test fun anEconomyOutsideZeroToFiftyIsNoReadingAndNothingIsSubstituted() {
        listOf(null, 0.0, -2.0, 50.0, 73.5, Double.NaN, Double.POSITIVE_INFINITY).forEach { v ->
            val r = FuelReimbursement.compute(312.4, v, 1_650)
            assertNull("$v", r.amountKrw)
            assertNull("$v", r.litersPer100Km)
            assertEquals("$v", Missing.ECONOMY, r.missing)
        }
        assertEquals(true, FuelReimbursement.validEconomy(49.9))
        assertEquals(false, FuelReimbursement.validEconomy(50.0))
    }

    @Test fun theAmountIsRoundedHalfUpToTenWon() {
        assertEquals(8_260L, FuelReimbursement.compute(100.0, 5.0, 1_651).amountKrw) // 8,255
        assertEquals(8_250L, FuelReimbursement.compute(200.0, 1.0, 4_127).amountKrw) // 8,254
        assertEquals(8_250L, FuelReimbursement.compute(100.0, 5.0, 1_649).amountKrw) // 8,245
    }

    @Test fun theEconomyIsTakenToTwoDecimalsAsTheSheetPrintsIt() {
        // 8.2645 L/100km reads 8.26 on the sheet; the amount follows the printed value, so the row reconciles
        val r = FuelReimbursement.compute(312.4, 8.2645, 1_650)
        assertEquals(8.26, r.litersPer100Km!!, 0.0)
        assertEquals(42_580L, r.amountKrw)
    }
}
