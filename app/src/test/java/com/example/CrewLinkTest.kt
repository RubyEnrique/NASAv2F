package com.example

import com.example.crew.CrewPacket
import com.example.crew.CrewRoster
import com.example.crew.CrewStatus
import com.example.crew.Geo
import com.example.hud.HudEvent
import com.example.hud.HudViewModel
import com.example.mental.EmergencyDrill
import com.example.voice.VoiceCommand
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CrewLinkTest {
  private fun status(id: String, lat: Double?, lon: Double?, emergency: Boolean = false, t: Long = 1_000L) =
    CrewStatus(id, "Crew-$id", lat, lon, 90f, 120, 40, emergency, if (emergency) "Pressure Leak" else "", t)

  @Test
  fun packet_roundTrips_andRejectsGarbage() {
    val s = status("AB12", 18.380001, 77.580002, emergency = true)
    assertEquals(s, CrewPacket.decode(CrewPacket.encode(s)))
    assertEquals(null, CrewPacket.decode(CrewPacket.encode(s.copy(lat = null, lon = null)))?.lat)
    assertNull(CrewPacket.decode("hello"))
    assertNull(CrewPacket.decode("C9|a|b|c|d|e|f|g|h|i|j"))
  }

  @Test
  fun geo_distanceAndBearing() {
    // 0.001 deg of latitude ~ 111 m due north; 0.001 deg longitude at 18.38 N ~ 105.5 m east.
    val (e1, n1) = Geo.offsetMeters(18.38, 77.58, 18.381, 77.58)
    assertEquals(111.2, Geo.distanceMeters(e1, n1), 0.5)
    assertEquals(0.0, Geo.bearingDeg(e1, n1), 0.1)
    val (e2, n2) = Geo.offsetMeters(18.38, 77.58, 18.38, 77.581)
    assertEquals(105.5, Geo.distanceMeters(e2, n2), 0.5)
    assertEquals(90.0, Geo.bearingDeg(e2, n2), 0.1)
  }

  @Test
  fun roster_ignoresSelf_sortsEmergencyFirst_andDropsStalePeers() {
    val r = CrewRoster("ME", staleMs = 20_000L)
    r.onPacket(status("ME", 1.0, 1.0), 0L)
    r.onPacket(status("A", 18.381, 77.58), 0L)
    r.onPacket(status("B", 18.385, 77.58, emergency = true), 5_000L)
    val m = r.members(10_000L, 18.38, 77.58)
    assertEquals(listOf("B", "A"), m.map { it.id })
    assertEquals(555.0f, m[0].distanceMeters!!, 2f)
    assertEquals(listOf("B"), r.members(21_000L, 18.38, 77.58).map { it.id })
    assertNull(r.members(21_000L, null, null)[0].distanceMeters)
  }

  @Test
  fun viewModel_announcesCrewmateInNeed_andEndAllEmergenciesClearsOwnBeacon() = runTest {
    val vm = HudViewModel(enablePeriodicDecay = false)
    val spoken = mutableListOf<String>()
    val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { if (it is HudEvent.Speak) spoken.add(it.text) } }
    vm.setCrewIdentity("ME", "Crew-ME")
    vm.onAbsoluteFix(18.38, 77.58)
    vm.onCrewPacket(status("B", 18.381, 77.58, emergency = true), 1_000L)
    assertTrue("spoke: $spoken", spoken.any { it.startsWith("Crew Crew-B needs help, 111 meters, bearing 0") })
    assertEquals(true, vm.uiState.value.crew.first().emergency)
    vm.onCrewPacket(status("B", 18.381, 77.58, emergency = true, t = 2_000L), 2_000L)
    assertEquals("not repeated within a minute", 1, spoken.count { it.startsWith("Crew Crew-B") })

    vm.onVoiceCommand(VoiceCommand.RunEmergency(1))
    assertTrue(vm.crewOutbound(3_000L).emergency)
    assertEquals("Pressure Leak", vm.crewOutbound(3_000L).reason)
    vm.onVoiceCommand(VoiceCommand.END_ALL_EMERGENCIES)
    assertEquals(EmergencyDrill.NONE, vm.uiState.value.activeDrill)
    assertFalse(vm.crewOutbound(4_000L).emergency)
    assertTrue(vm.uiState.value.alertAcknowledged)
    job.cancel()
  }
}
