package app.autobrief.car

import app.autobrief.car.domain.AutoBriefCore
import app.autobrief.car.domain.AutoBriefCore.Space
import app.autobrief.car.domain.Vins
import app.autobrief.car.domain.VehicleEvent
import app.autobrief.car.model.AppSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The VIN as the Pleos SDK delivers it: a JSON-quoted string ("\"KMH…\""), and on the emulator image the VHAL's
 * placeholder "\"invalid\"" (CI run 40280862531: the fleet console showed the car as "invalid").
 */
class VinTest {
    private val hook = "http://10.0.2.2:8787/hook"
    private val vin = "KMHL341DBNA000001"

    private fun car(body: String) = Json.parseToJsonElement(body).jsonObject.getValue("car").jsonObject

    @Test fun onlyAWellFormedVinIsAVin() {
        assertEquals(vin, Vins.normalize("\"$vin\""))
        assertEquals(vin, Vins.normalize(" ${vin.lowercase()} "))
        listOf("\"invalid\"", "invalid", "", "\"\"", null, "KMHL341DBNA00000", "KMHL341DBNA0000012", "KMHL341DBNA00000O", "42dot")
            .forEach { assertNull(it, Vins.normalize(it)) }
    }

    @Test fun thePlaceholderVinOfTheEmulatorIsNotSentAsTheCar() {
        val h = Harness(AppSettings(plateNumber = "00가 0000", archiveWebhookUrl = hook))
        h.core.onVehicle(VehicleEvent.Vin("\"invalid\""))
        assertNull(h.core.vehicle.value.vin)
        h.drive(100.0, 12.0, null, null)
        val car = car(h.core.wireBody(h.core.outbox.value.first()))
        assertEquals(JsonNull, car.getValue("vin"))
        assertEquals("00가 0000", car.getValue("plate").jsonPrimitive.content)
    }

    @Test fun aVinThatArrivesAfterTheFirstEntriesStillReachesThem() {
        val h = Harness(AppSettings(plateNumber = "00가 0000", archiveWebhookUrl = hook))
        h.drive(100.0, 12.0, null, null) // queued before the SDK delivered the VIN
        val item = h.core.outbox.value.first()
        assertFalse("the stored payload holds no car block of its own", Json.parseToJsonElement(item.payloadJson).jsonObject.containsKey("car"))
        h.core.onVehicle(VehicleEvent.Vin("\"$vin\""))
        assertEquals(vin, car(h.core.wireBody(item)).getValue("vin").jsonPrimitive.content)
        assertEquals(vin, h.store.data.getValue(Space.LIVE).settings.vin)
    }

    @Test fun theVinSeenOnceIsKeptForTheNextStart() {
        val h = Harness(AppSettings(plateNumber = "00가 0000", archiveWebhookUrl = hook))
        h.core.onVehicle(VehicleEvent.Vin("\"$vin\""))
        val again = AutoBriefCore(h.store, clock = { h.now }) // restarted; the SDK has not answered yet
        again.onVehicle(VehicleEvent.Odometer(100.0)); again.onVehicle(VehicleEvent.GearChanged(app.autobrief.car.model.Gear.PARK))
        h.advance(60_000); again.onVehicle(VehicleEvent.GearChanged(app.autobrief.car.model.Gear.DRIVE))
        h.advance(60_000); again.onVehicle(VehicleEvent.Odometer(105.0)); again.onVehicle(VehicleEvent.GearChanged(app.autobrief.car.model.Gear.PARK))
        val item = again.outbox.value.first()
        assertEquals(vin, car(again.wireBody(item)).getValue("vin").jsonPrimitive.content)
        assertEquals("the trip body carries it too", vin, Json.parseToJsonElement(again.ledgerEntries.first().body).jsonObject.getValue("vin").jsonPrimitive.content)
        // an ordinary settings save from the screen does not drop it
        again.saveSettings(again.settings.value.copy(entityName = "가상예시 주식회사"), null, null)
        assertEquals(vin, h.store.data.getValue(Space.LIVE).settings.vin)
    }
}
