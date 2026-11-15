package com.example

import com.example.body.GravityLoadModel
import com.example.body.StepDetector
import com.example.hud.HudViewModel
import com.example.mental.EmergencyDrill
import com.example.nasa.Mars24
import com.example.nasa.PlanetEphemeris
import com.example.nasa.SpaceWeatherEvent
import com.example.nasa.SpaceWeatherForecaster
import com.example.nasa.SpaceWeatherLevel
import com.example.predict.TrendForecaster
import com.example.suit.AxemuProfile
import com.example.suit.JezeroWeatherModel
import com.example.voice.VoiceCommand
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NasaDataTest {
  private fun t(iso: String) = Instant.parse(iso).toEpochMilli()

  @Test
  fun jplEphemeris_matchesKnownEarthMarsGeometry() {
    // Mars closest approach 2025-01-12: ~0.642 AU (~5.3 light-minutes).
    assertEquals(0.642, PlanetEphemeris.earthMarsDistanceAu(t("2025-01-12T00:00:00Z")), 0.01)
    assertEquals(5.34, PlanetEphemeris.oneWayLightTimeSec(t("2025-01-12T00:00:00Z")) / 60.0, 0.1)
    // Solar conjunction 2023-11-18: ~2.5 AU and Mars behind the Sun.
    assertTrue(PlanetEphemeris.earthMarsDistanceAu(t("2023-11-18T00:00:00Z")) > 2.45)
    assertTrue(PlanetEphemeris.isNearSolarConjunction(t("2023-11-18T00:00:00Z")))
    assertFalse(PlanetEphemeris.isNearSolarConjunction(t("2025-01-12T00:00:00Z")))
  }

  @Test
  fun mars24_reproducesPerseveranceLandingAtJezero() {
    // Touchdown 2021-02-18 ~20:44 UTC (spacecraft time): early northern spring, mid-afternoon.
    val m = Mars24.compute(t("2021-02-18T20:44:00Z"))
    assertEquals(5.5, m.solarLongitudeDeg, 1.0)
    assertEquals(52304.45, m.marsSolDate, 0.05)
    assertEquals(15.88, m.localMeanSolarTimeHours, 0.15)
    assertEquals("Spring", Mars24.season(m.solarLongitudeDeg))
    assertEquals("15:53", Mars24.formatHours(m.localMeanSolarTimeHours))
  }

  @Test
  fun medaModel_coldBeforeDawn_warmestEarlyAfternoon() {
    val night = JezeroWeatherModel.predict(2.0)
    val noon = JezeroWeatherModel.predict(14.0)
    assertEquals(-25f, noon.airTempC, 0.5f)
    assertEquals(-85f, night.airTempC, 0.5f)
    assertTrue(night.airTempC < AxemuProfile.COLD_SOAK_BELOW_C)
    assertFalse(night.isDaylight)
    assertEquals(718f, JezeroWeatherModel.predict(12.0).pressurePa, 25f)
  }

  @Test
  fun trendForecaster_projectsAndFindsTimeToThreshold() {
    val f = TrendForecaster(windowMs = 300_000L, minSamples = 5)
    for (i in 0..60) f.add(i * 1_000L, 40.0 + i * 0.1) // +6 per minute
    val fc = f.forecast(10.0, 70.0, rising = true)!!
    assertEquals(6.0, fc.slopePerMin, 0.01)
    assertEquals(106.0, fc.projected, 0.5)
    assertEquals(4.0, fc.minutesToThreshold!!, 0.05)
    val flat = TrendForecaster(300_000L, 5).apply { for (i in 0..20) add(i * 1_000L, 50.0) }
    assertNull(flat.forecast(10.0, 70.0, rising = true)!!.minutesToThreshold)
    assertNull(TrendForecaster(300_000L, 5).forecast(1.0, 1.0, true))
  }

  @Test
  fun spaceWeather_flaresRaiseRisk_andStaleDataIsUnknown() {
    val now = t("2026-11-14T00:00:00Z")
    assertEquals(SpaceWeatherLevel.UNKNOWN, SpaceWeatherForecaster.evaluate(emptyList(), now, 0L).level)
    val quiet = SpaceWeatherForecaster.evaluate(listOf(SpaceWeatherEvent("FLR", now - 3_600_000L, "C3.0")), now, now)
    assertEquals(SpaceWeatherLevel.QUIET, quiet.level)
    val active =
      SpaceWeatherForecaster.evaluate(
        listOf(SpaceWeatherEvent("FLR", now - 3_600_000L, "X2.0"), SpaceWeatherEvent("FLR", now - 7_200_000L, "M1.0")),
        now,
        now,
      )
    assertEquals(SpaceWeatherLevel.ACTIVE, active.level)
    assertEquals("X2.0", active.strongestFlare)
    assertTrue((active.sepRiskPercent ?: 0) > (quiet.sepRiskPercent ?: 0))
    assertEquals(20.0, SpaceWeatherForecaster.flareRank("X2.0"), 1e-9)
    assertEquals(0.5, SpaceWeatherForecaster.flareRank("C5.0"), 1e-9)
  }

  @Test
  fun gravityModel_scalesStepsToMarsLoad() {
    val s = GravityLoadModel.evaluate(10_000, 150f * 60f)
    assertEquals(3_800, s.earthEquivalentLoadSteps)
    assertEquals(1f, s.exerciseGoalFraction, 0.001f)
    assertEquals("Moderate", s.atrophyRisk)
    assertEquals("High", GravityLoadModel.evaluate(500, 0f).atrophyRisk)

    val d = StepDetector()
    var steps = 0
    for (i in 0 until 400) {
      val tMs = i * 20L
      val z = 9.81f + if (i % 25 < 3) 3.5f else 0f // a stride bump every 0.5 s
      if (d.onAccel(0f, 0f, z, tMs)) steps++
    }
    assertEquals(16, steps)
  }

  @Test
  fun viewModel_publishesNasaContextAndTurnBackForecast() {
    val vm = HudViewModel(enablePeriodicDecay = false)
    val s0 = vm.uiState.value
    assertNotNull(s0.marsTime)
    assertTrue(s0.earthLink.oneWayDelaySec in 180.0..1_340.0)
    assertTrue(s0.consumables.turnBackInMinutes > 0)
    assertEquals("O₂", s0.consumables.limitingFactor)

    vm.onGpsVectorDelta(300f, 0f)
    vm.onVoiceCommand(VoiceCommand.RunEmergency(2)) // O2 depletion: 0.4 h left
    vm.tick(10_000L)
    val s = vm.uiState.value
    assertEquals(EmergencyDrill.EMERGENCY_2, s.activeDrill)
    assertTrue("turn back now: ${s.consumables.turnBackInMinutes}", s.consumables.turnBackInMinutes <= 0)
  }
}
