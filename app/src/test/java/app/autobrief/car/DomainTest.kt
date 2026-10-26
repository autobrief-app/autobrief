package app.autobrief.car

import app.autobrief.car.domain.AutoClassifier
import app.autobrief.car.domain.DisplayMasking
import app.autobrief.car.domain.Ledger
import app.autobrief.car.domain.LedgerKind
import app.autobrief.car.domain.SampleHistory
import app.autobrief.car.domain.TaxBenefit
import app.autobrief.car.domain.TripLogbook
import app.autobrief.car.model.CarUser
import app.autobrief.car.model.ClassifiedBy
import app.autobrief.car.model.Gear
import app.autobrief.car.model.GeoPoint
import app.autobrief.car.model.Place
import app.autobrief.car.model.PlaceKind
import app.autobrief.car.model.TripPurpose
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.time.LocalDate

class TripLogbookTest {
    private val user = CarUser("김예시", "영업팀")

    @Test fun gearCycleWritesOneRowWithDashboardValues() {
        val lb = TripLogbook()
        lb.onOdometer(45_200.0)
        assertTrue(lb.onGear(Gear.DRIVE, 1_000).isEmpty())
        lb.onOdometer(45_218.0)
        val rows = lb.onGear(Gear.PARK, 2_000)
        assertEquals(1, rows.size)
        assertEquals(45_200.0, rows[0].startOdometerKm, 0.0)
        assertEquals(45_218.0, rows[0].endOdometerKm, 0.0)
        assertEquals(TripPurpose.UNCLASSIFIED, rows[0].purpose)
    }

    @Test fun reverseThenDriveIsOneTripAndAParkNudgeIsNoRow() {
        val lb = TripLogbook()
        lb.onOdometer(100.0)
        lb.onGear(Gear.REVERSE, 1)
        lb.onGear(Gear.DRIVE, 2)
        lb.onOdometer(105.0)
        assertEquals(1, lb.onGear(Gear.PARK, 3).size)
        lb.onGear(Gear.DRIVE, 4)
        assertTrue("no distance, no row", lb.onGear(Gear.PARK, 5).isEmpty())
    }

    @Test fun odometerMovedWhileTheAppWasOffBecomesANonBusinessGapRow() {
        val lb = TripLogbook()
        lb.onOdometer(100.0); lb.onGear(Gear.DRIVE, 1); lb.onOdometer(110.0); lb.onGear(Gear.PARK, 2)
        lb.onOdometer(137.0) // the car moved 27 km without the app
        val added = lb.onGear(Gear.DRIVE, 3)
        assertEquals(TripPurpose.UNLOGGED_GAP, added.single().purpose)
        assertEquals(110.0, added.single().startOdometerKm, 0.0)
        assertEquals(137.0, added.single().endOdometerKm, 0.0)
        val s = TripLogbook.summarize(lb.records)
        assertEquals(0, s.continuityBreaks)
        assertEquals(27.0, s.gapKm, 0.0)
    }

    @Test fun coldStartOpensTheTripAtTheFirstOdometerValue() {
        val lb = TripLogbook()
        lb.onGear(Gear.DRIVE, 1)
        lb.onOdometer(500.0)
        lb.onOdometer(512.0)
        assertEquals(500.0, lb.onGear(Gear.PARK, 2).single().startOdometerKm, 0.0)
    }

    @Test fun changingASetPurposeIsAnAmendmentAndGapRowsCannotBeLabelled() {
        val lb = TripLogbook()
        lb.onOdometer(0.0); lb.onGear(Gear.DRIVE, 1); lb.onOdometer(10.0)
        val id = lb.onGear(Gear.PARK, 2).single().id
        val (before, first) = lb.classify(id, TripPurpose.COMMUTE, user, "출근", ClassifiedBy.AUTO, "집→회사")!!
        assertEquals(TripPurpose.UNCLASSIFIED, before.purpose)
        assertFalse(first.amended)
        val (_, second) = lb.classify(id, TripPurpose.PERSONAL, null, "개인 용무", ClassifiedBy.SCREEN)!!
        assertTrue(second.amended)
        assertEquals("집→회사", second.autoReason)
        assertEquals(user, second.user)
        lb.onOdometer(30.0); val gap = lb.onGear(Gear.DRIVE, 3).single()
        assertNull(lb.classify(gap.id, TripPurpose.BUSINESS, user, "", ClassifiedBy.SCREEN))
    }

    @Test fun ratioCountsOnlyCommuteAndBusiness() {
        val lb = TripLogbook()
        var odo = 0.0
        listOf(TripPurpose.COMMUTE to 10.0, TripPurpose.BUSINESS to 20.0, TripPurpose.PERSONAL to 10.0, null to 10.0).forEachIndexed { i, (p, km) ->
            lb.onOdometer(odo); lb.onGear(Gear.DRIVE, i * 10L); odo += km; lb.onOdometer(odo)
            val id = lb.onGear(Gear.PARK, i * 10L + 5).single().id
            if (p != null) lb.classify(id, p, user, "", ClassifiedBy.SCREEN)
        }
        val s = TripLogbook.summarize(lb.records)
        assertEquals(50.0, s.totalKm, 0.0)
        assertEquals(30.0, s.businessUseKm, 0.0)
        assertEquals(0.6, s.businessUseRatio!!, 1e-9)
        assertEquals(1, s.unclassifiedCount)
    }
}

class LedgerTest {
    @Test fun sha256IsLowerCaseHexOfTheDigest() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Ledger.sha256("abc"))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Ledger.sha256(""))
        assertEquals("000ff0ff7f80", Ledger.hex(byteArrayOf(0x00, 0x0f, 0xf0.toByte(), 0xff.toByte(), 0x7f, 0x80.toByte())))
    }

    @Test fun hashFormulaIsPinned() {
        val l = Ledger()
        val e = l.append(LedgerKind.TRIP, "TRIP-1", buildJsonObject { put("id", "TRIP-1") }, 42)
        val expected = MessageDigest.getInstance("SHA-256")
            .digest("${"0".repeat(64)}|1|42|TRIP|TRIP-1|{\"id\":\"TRIP-1\"}".toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, e.hash)
        assertEquals(Ledger.GENESIS, e.prevHash)
        val e2 = l.append(LedgerKind.CLASSIFY, "TRIP-1", buildJsonObject { put("id", "TRIP-1") }, 43)
        assertEquals(e.hash, e2.prevHash)
        assertEquals(2L, e2.seq)
        assertEquals(e2.hash.take(4).uppercase() + "-" + e2.hash.substring(4, 8).uppercase(), Ledger.code(e2.hash))
    }

    @Test fun sampleChainVerifiesAndEveryKindOfEditIsCaught() {
        val (rows, ledger) = SampleHistory.build(LocalDate.of(2026, 2, 1))
        val ok = Ledger.check(ledger.all, rows)
        assertTrue(ok.problems.toString(), ok.ok)
        assertEquals(ledger.size, ok.entryCount)

        // 1. a stored row edited (personal trip turned into business) without a ledger entry
        val i = rows.indexOfFirst { it.purpose == TripPurpose.COMMUTE }
        val edited = rows.toMutableList().also { it[i] = it[i].copy(purpose = TripPurpose.BUSINESS) }
        assertFalse(Ledger.check(ledger.all, edited).ok)

        // 2. an odometer value rewritten in the ledger body itself: the hash no longer matches
        val entries = ledger.all.toMutableList()
        val j = entries.indexOfFirst { it.kind == LedgerKind.TRIP }
        entries[j] = entries[j].copy(body = entries[j].body.replace("\"endOdometerKm\":", "\"endOdometerKm\":1"))
        assertFalse(Ledger.check(entries, rows).ok)

        // 3. an entry removed from the middle
        val removed = ledger.all.toMutableList().also { it.removeAt(5) }
        assertFalse(Ledger.check(removed, rows).ok)

        // 4. a whole row deleted from the list
        assertFalse(Ledger.check(ledger.all, rows.drop(1)).ok)

        // 5. the remarks column hidden: an amendment flag dropped, a screen label passed off as automatic
        val k = rows.indexOfFirst { it.classifiedBy == ClassifiedBy.SCREEN }
        assertFalse(Ledger.check(ledger.all, rows.toMutableList().also { it[k] = it[k].copy(classifiedBy = ClassifiedBy.AUTO) }).ok)
        assertFalse(Ledger.check(ledger.all, rows.toMutableList().also { it[k] = it[k].copy(amended = true) }).ok)
        assertFalse(Ledger.check(ledger.all, rows.toMutableList().also { it[k] = it[k].copy(routeTollEstimateKrw = 99_000) }).ok)
    }

    @Test fun replayRebuildsAmendments() {
        val l = Ledger()
        val trip = app.autobrief.car.model.TripRecord("T1", 1, 2, 10.0, 20.0, TripPurpose.UNCLASSIFIED, null, "")
        l.append(LedgerKind.TRIP, "T1", Ledger.tripBody(trip, "VIN"), 2)
        val c = trip.copy(purpose = TripPurpose.COMMUTE, user = CarUser("a", "b"), classifiedBy = ClassifiedBy.AUTO, autoReason = "집→회사")
        l.append(LedgerKind.CLASSIFY, "T1", Ledger.classifyBody(c), 3)
        val a = c.copy(purpose = TripPurpose.PERSONAL, classifiedBy = ClassifiedBy.SCREEN, amended = true)
        l.append(LedgerKind.AMEND, "T1", Ledger.amendBody(a, TripPurpose.COMMUTE, "개인 용무"), 4)
        val r = Ledger.replay(l.all).getValue("T1")
        assertEquals(TripPurpose.PERSONAL, r.purpose)
        assertTrue(r.amended)
        assertEquals("집→회사", r.autoReason)
        assertTrue(Ledger.check(l.all, listOf(a)).ok)
    }
}

class AutoClassifierTest {
    private val places = listOf(SampleHistory.HOME, SampleHistory.OFFICE, SampleHistory.CLIENT)

    @Test fun homeOfficeIsCommuteBothWays() {
        val r = AutoClassifier.classify(SampleHistory.HOME.point, SampleHistory.OFFICE.point, places)
        assertEquals(TripPurpose.COMMUTE, r.purpose)
        assertEquals("집→회사", r.reason)
        assertEquals(TripPurpose.COMMUTE, AutoClassifier.classify(SampleHistory.OFFICE.point, SampleHistory.HOME.point, places).purpose)
    }

    @Test fun clientArrivalAndReturnAreBusiness() {
        val toClient = AutoClassifier.classify(SampleHistory.OFFICE.point, SampleHistory.CLIENT.point, places)
        assertEquals(TripPurpose.BUSINESS, toClient.purpose)
        assertEquals("가상상사 방문", toClient.memo)
        assertEquals(TripPurpose.BUSINESS, AutoClassifier.classify(SampleHistory.CLIENT.point, SampleHistory.OFFICE.point, places).purpose)
    }

    @Test fun unknownPlacesAndMissingPositionsAreLeftToTheDriver() {
        val cityHall = GeoPoint(37.5665, 126.9780)
        assertNull(AutoClassifier.classify(SampleHistory.HOME.point, cityHall, places).purpose)
        assertNull(AutoClassifier.classify(null, SampleHistory.OFFICE.point, places).purpose)
        assertNull(AutoClassifier.classify(SampleHistory.OFFICE.point, SampleHistory.OFFICE.point, places).purpose)
        assertNull(AutoClassifier.classify(SampleHistory.CLIENT.point, SampleHistory.HOME.point, places).purpose)
    }

    @Test fun radiusIsAboutThreeHundredMetres() {
        val home = SampleHistory.HOME.point
        // 0.0027 degrees of latitude is about 300 m
        val inside = GeoPoint(home.lat + 0.0026, home.lon)
        val outside = GeoPoint(home.lat + 0.0028, home.lon)
        assertEquals("집", AutoClassifier.nearest(inside, places)?.label)
        assertNull(AutoClassifier.nearest(outside, places))
        // Seoul Forest area to Pangyo station area: about 18 km in a straight line
        assertEquals(17_900.0, AutoClassifier.distanceM(SampleHistory.HOME.point, SampleHistory.OFFICE.point), 600.0)
    }

    @Test fun nearestPlaceWins() {
        val a = Place("A", PlaceKind.CLIENT, GeoPoint(37.0, 127.0))
        val b = Place("B", PlaceKind.CLIENT, GeoPoint(37.0015, 127.0))
        assertEquals("B", AutoClassifier.nearest(GeoPoint(37.0012, 127.0), listOf(a, b))?.label)
    }
}

class TaxBenefitTest {
    @Test fun underFifteenMillionTheLogbookCannotAddDeduction() {
        val r = TaxBenefit.compute(12_000_000, 6_000_000, 0.9, 22.0)!!
        assertEquals(1.0, r.withoutLogbook.ratio, 0.0)
        assertEquals(12_000_000, r.withoutLogbook.deductibleKrw)
        assertEquals(10_800_000, r.withLogbook.deductibleKrw)
        assertTrue(r.extraDeductibleKrw < 0)
    }

    @Test fun sampleCarNumbersByHand() {
        // cost 1,900만, depreciation 1,150만, logbook ratio 94.6%
        val r = TaxBenefit.compute(19_000_000, 11_500_000, 0.946, 22.0)!!
        // without: ratio 15/19 -> business 1,500만; depreciation 1,150*15/19 = 907.9만 -> 107.9만 carried
        assertEquals(15_000_000, r.withoutLogbook.businessCostKrw)
        assertEquals(1_078_947, r.withoutLogbook.depreciationCarriedKrw)
        assertEquals(13_921_053, r.withoutLogbook.deductibleKrw)
        // with: business 1,797.4만; depreciation 1,087.9만 -> 287.9만 carried
        assertEquals(17_974_000, r.withLogbook.businessCostKrw)
        assertEquals(2_879_000, r.withLogbook.depreciationCarriedKrw)
        assertEquals(15_095_000, r.withLogbook.deductibleKrw)
        assertEquals(1_173_947, r.extraDeductibleKrw)
        assertEquals(258_268, r.taxEffectKrw)
    }

    @Test fun noCostOrNoDistanceMeansNoFigure() {
        assertNull(TaxBenefit.compute(0, 0, 0.9, 22.0))
        assertNull(TaxBenefit.compute(19_000_000, 0, null, 22.0))
    }
}

class DisplayMaskingTest {
    @Test fun masksUnitsButNotDistrictsOrLines() {
        assertEquals("한빛마을 3단지 동·호수 비공개", DisplayMasking.mask("우리집: 한빛마을 3단지 101동 1203호"))
        assertEquals("역삼1동 2호선 강남역 3호점", DisplayMasking.mask("역삼1동 2호선 강남역 3호점"))
    }
}
