package app.autobrief.car

import app.autobrief.car.domain.AutoBriefCore
import app.autobrief.car.domain.AutoBriefCore.Space
import app.autobrief.car.domain.FuelReimbursement
import app.autobrief.car.domain.GleoContract
import app.autobrief.car.domain.TaxPeriods
import app.autobrief.car.domain.VehicleEvent
import app.autobrief.car.model.AppSettings
import app.autobrief.car.model.CarUse
import app.autobrief.car.model.CarUser
import app.autobrief.car.model.ClassifiedBy
import app.autobrief.car.model.FuelBasis
import app.autobrief.car.model.TripPurpose
import app.autobrief.car.ui.fuelLine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.time.YearMonth
import java.util.zip.ZipInputStream

/** 내 차 mode: the month's business distance at the car's cumulative fuel economy and the entered price. */
class FuelSettlementTest {
    private val user = CarUser("김예시", "영업팀")
    private val company = AppSettings(entityName = "가상예시 주식회사", plateNumber = "00가 0000", users = listOf(user))
    private val own = company.copy(carUse = CarUse.OWN, fuelPriceKrwPerLiter = 1_650)
    private val october = YearMonth.of(2026, 10)

    /** A drive of [km] given a purpose on the screen. */
    private fun Harness.trip(fromKm: Double, km: Double, purpose: TripPurpose) {
        longDrive(fromKm, km)
        advance(AutoBriefCore.AUTO_WAIT_MS + 1)
        core.tick()
        core.classifyTrip(core.trips.value.last().id, purpose, null, "", ClassifiedBy.SCREEN)
    }

    private fun sheetNames(xlsx: ByteArray): List<String> {
        ZipInputStream(ByteArrayInputStream(xlsx)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.name == "xl/workbook.xml") return Regex("""<sheet name="([^"]+)"""").findAll(z.readBytes().toString(Charsets.UTF_8)).map { it.groupValues[1] }.toList()
            }
        }
        return emptyList()
    }

    @Test fun theMonthIsSettledAtTheCarsOwnEconomyAndTheCardSaysHow() {
        val h = Harness(own)
        h.trip(45_000.0, 312.4, TripPurpose.BUSINESS)
        h.trip(45_312.4, 18.0, TripPurpose.COMMUTE)
        h.core.onVehicle(VehicleEvent.FuelEconomy(8.26))
        assertTrue(h.logs.toString(), h.logs.contains("FUEL_ECONOMY l100=8.26 valid=true"))
        val m = h.core.fuelSettlement(october)!!
        assertEquals(FuelReimbursement.Source.VEHICLE, m.economySource)
        assertEquals(312.4, m.result.businessKm, 1e-9)
        assertEquals(42_580L, m.result.amountKrw)
        assertEquals("이번 달 업무 312.4km, 평균 연비 12.1km/L(차량 누적 연비), 유가 1,650원/L, 정산 42,580원", fuelLine(m, withCommute = false))
        // 출퇴근 포함: the commute's 18 km counts too
        h.core.saveSettings(h.core.settings.value.copy(fuelBasis = FuelBasis.WITH_COMMUTE), null, null)
        assertEquals(330.4, h.core.fuelSettlement(october)!!.result.businessKm, 1e-9)
        // 회사 차: no settlement at all (the tax meter instead)
        h.core.saveSettings(h.core.settings.value.copy(carUse = CarUse.COMPANY), null, null)
        assertNull(h.core.fuelSettlement(october))
    }

    @Test fun invalidReadingsAreLoggedAndIgnoredAndTheOwnersFigureIsLabelledAsSuch() {
        val h = Harness(own)
        h.trip(1_000.0, 312.4, TripPurpose.BUSINESS)
        listOf(null, 0.0, 73.5, Double.NaN).forEach { h.core.onVehicle(VehicleEvent.FuelEconomy(it)) }
        assertEquals(4, h.logs.count { it.startsWith("FUEL_ECONOMY") && it.endsWith("valid=false") })
        val none = h.core.fuelSettlement(october)!!
        assertNull(none.economySource)
        assertEquals(FuelReimbursement.Missing.ECONOMY, none.result.missing)
        assertNull("no constant stands in for the car's value", none.result.amountKrw)
        h.core.saveSettings(h.core.settings.value.copy(manualKmPerLiter = 12.1), null, null)
        val manual = h.core.fuelSettlement(october)!!
        assertEquals(FuelReimbursement.Source.MANUAL, manual.economySource)
        assertEquals(42_580L, manual.result.amountKrw)
        assertEquals("이번 달 업무 312.4km, 평균 연비 12.1km/L(직접 입력), 유가 1,650원/L, 정산 42,580원", fuelLine(manual, withCommute = false))
        // a valid reading from the car wins over the owner's figure, and a later invalid one does not undo it
        h.core.onVehicle(VehicleEvent.FuelEconomy(7.0))
        h.core.onVehicle(VehicleEvent.FuelEconomy(0.0))
        assertEquals(FuelReimbursement.Source.VEHICLE, h.core.fuelSettlement(october)!!.economySource)
        assertEquals(7.0, h.core.fuelSettlement(october)!!.result.litersPer100Km!!, 0.0)
    }

    @Test fun theReadingIsKeptWithItsTimeAcrossARestart() {
        val h = Harness(own)
        h.core.onVehicle(VehicleEvent.FuelEconomy(8.26))
        val stored = h.store.data.getValue(Space.LIVE).settings
        assertEquals(8.26, stored.fuelEconomyL100, 0.0)
        assertEquals(h.now, stored.fuelEconomyAtMs)
        val again = AutoBriefCore(h.store, clock = { h.now })
        assertEquals(FuelReimbursement.Source.VEHICLE, again.fuelSettlement(october)!!.economySource)
    }

    @Test fun anElectricCarIsToldItsSettlementComesInTheNextVersion() {
        val h = Harness(own)
        h.core.onVehicle(VehicleEvent.FuelEconomy(null))
        h.core.onVehicle(VehicleEvent.EvEfficiency(5.1))
        assertTrue(h.logs.toString(), h.logs.contains("EV_EFFICIENCY kmkwh=5.1 valid=true"))
        assertTrue(h.core.electricOnly)
        h.core.onVehicle(VehicleEvent.FuelEconomy(8.26)) // a car that also reports fuel (a plug-in hybrid) is settled on fuel
        assertFalse(h.core.electricOnly)
    }

    @Test fun gleoStatusCarriesTheReimbursementInOwnCarModeOnly() {
        val h = Harness(own)
        h.trip(1_000.0, 312.4, TripPurpose.BUSINESS)
        h.core.onVehicle(VehicleEvent.FuelEconomy(8.26))
        val reply = h.core.gleo(GleoContract.GET_LOGBOOK_STATUS, GleoContract.parseParams("{}"))
        assertTrue(reply.message, reply.message.endsWith("Fuel reimbursement this month: 42,580 won."))
        assertEquals(42_580L, reply.data!!.single().jsonObject.getValue("fuelReimbursementKrw").jsonPrimitive.long)
        val companyCar = Harness(company)
        companyCar.trip(1_000.0, 312.4, TripPurpose.BUSINESS)
        val plain = companyCar.core.gleo(GleoContract.GET_LOGBOOK_STATUS, GleoContract.parseParams("{}"))
        assertFalse(plain.message, "Fuel" in plain.message)
        assertFalse("fuelReimbursementKrw" in plain.data!!.single().jsonObject)
        // no price yet: the reply says so instead of a number
        h.core.saveSettings(h.core.settings.value.copy(fuelPriceKrwPerLiter = 0), null, null)
        val noPrice = h.core.gleo(GleoContract.GET_LOGBOOK_STATUS, GleoContract.parseParams("{}"))
        assertTrue(noPrice.message, noPrice.message.endsWith("The fuel reimbursement needs a fuel price in the settings."))
        assertFalse("fuelReimbursementKrw" in noPrice.data!!.single().jsonObject)
    }

    @Test fun theWorkbookHasTheSettlementSheetInOwnCarModeOnly() {
        val h = Harness(own.copy(archiveWebhookUrl = "https://example.invalid/hook"))
        h.trip(1_000.0, 312.4, TripPurpose.BUSINESS)
        h.core.onVehicle(VehicleEvent.FuelEconomy(8.26))
        h.core.exportNow(TaxPeriods.month(october), businessYear = false)
        val xlsx = h.files.entries.single { it.key.endsWith(".xlsx") }.value
        assertEquals(listOf("업무용승용차 운행기록부 (기간 발췌)", "검증 기록", "유류비 정산"), sheetNames(xlsx))
        val payload = Json.parseToJsonElement(h.core.outbox.value.single { it.kind == "archive" }.payloadJson).jsonObject
        val fuel = payload.getValue("fuelReimbursement").jsonObject
        val month = fuel.getValue("months").jsonArray.single().jsonObject
        assertEquals("2026-10", month.getValue("month").jsonPrimitive.content)
        assertEquals(42_580L, month.getValue("amountKrw").jsonPrimitive.long)
        assertEquals(42_580L, fuel.getValue("totalKrw").jsonPrimitive.long)

        val companyCar = Harness(company.copy(archiveWebhookUrl = "https://example.invalid/hook"))
        companyCar.trip(1_000.0, 312.4, TripPurpose.BUSINESS)
        companyCar.core.exportNow(TaxPeriods.month(october), businessYear = false)
        assertEquals(listOf("업무용승용차 운행기록부 (기간 발췌)", "검증 기록"), sheetNames(companyCar.files.entries.single { it.key.endsWith(".xlsx") }.value))
        assertFalse("fuelReimbursement" in companyCar.core.outbox.value.single { it.kind == "archive" }.payloadJson)
    }

    @Test fun theMonthCloseSaysTheAmount() {
        val h = Harness(own, startAt = Harness.at(LocalDate.of(2026, 10, 30), 9, 0))
        h.trip(1_000.0, 312.4, TripPurpose.BUSINESS)
        h.core.onVehicle(VehicleEvent.FuelEconomy(8.26))
        h.now = Harness.at(LocalDate.of(2026, 11, 2), 8, 0)
        h.core.tick()
        val notice = h.core.notice.value
        assertNotNull(notice)
        assertTrue(notice!!.text, "유류비 정산 42,580원" in notice.text)
    }
}
