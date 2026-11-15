package com.example.suit

import com.example.mental.EmergencyDrill
import kotlin.math.sin

/*
 * Spacesuit life-support telemetry modelled on the NASA SUITS challenge Telemetry Stream Server
 * (github.com/SUITS-Techteam/TSS2026, src/lib/simulation/config/eva1.json). Field names, units and
 * the metabolic formulas follow that EVA model:
 *   oxy_consumption = 0.1 + 0.0001 * heart_rate   (psi/min)
 *   co2_production  = 0.11 + 0.0002 * heart_rate  (psi/min)
 *   oxy_*_pressure  = oxy_*_storage * 30          (psi, 3000 psi full tank)
 *   helmet_pressure_co2 = suit_pressure_co2 * 1.5
 *   suit_pressure_total = oxy + co2 + other
 * Nominal operating suit pressure uses the NASA EMU/xEMU value of 4.3 psid. Depletion rates are
 * slowed to an ~8 h EVA so the values are meaningful in real time.
 */

data class SuitTelemetry(
  val primaryBatteryPct: Float = 92f,
  val secondaryBatteryPct: Float = 100f,
  val batteryTimeLeftSec: Int = 0,
  val oxyPriStoragePct: Float = 88f,
  val oxySecStoragePct: Float = 100f,
  val oxyPriPressurePsi: Float = 88f * 30f,
  val oxySecPressurePsi: Float = 3000f,
  val oxyTimeLeftSec: Int = 0,
  val oxyConsumptionPsiMin: Float = 0.1086f,
  val co2ProductionPsiMin: Float = 0.1272f,
  val suitPressureOxyPsi: Float = 4.26f,
  val suitPressureCo2Psi: Float = 0.023f,
  val suitPressureOtherPsi: Float = 0.01f,
  val suitPressureTotalPsi: Float = 4.3f,
  val helmetPressureCo2Psi: Float = 0.0348f,
  val fanPriRpm: Float = 30000f,
  val fanSecRpm: Float = 30000f,
  val scrubberACo2StoragePct: Float = 12f,
  val scrubberBCo2StoragePct: Float = 0f,
  val activeScrubber: Char = 'A',
  val suitTempC: Float = 21.1f,
  val coolantStoragePct: Float = 96f,
  val coolantLiquidPressurePsi: Float = 500f,
) {
  val helmetCo2MmHg: Float
    get() = helmetPressureCo2Psi * PSI_TO_MMHG

  val isLowPower: Boolean
    get() = primaryBatteryPct < 20f

  val isPressureNominal: Boolean
    get() = suitPressureTotalPsi in 3.8f..4.6f

  val isOxygenLow: Boolean
    get() = oxyPriStoragePct < 25f

  val isFanFault: Boolean
    get() = fanPriRpm < 20000f

  companion object {
    const val PSI_TO_MMHG = 51.715f
  }
}

class SuitTelemetryModel {
  private var batPri = 92.0
  private var batSec = 100.0
  private var oxyPri = 88.0
  private var oxySec = 100.0
  private var scrubA = 12.0
  private var scrubB = 0.0
  private var activeA = true
  private var coolant = 96.0

  fun step(dtSec: Double, heartRateBpm: Double, drill: EmergencyDrill, tSec: Double): SuitTelemetry {
    val dt = dtSec.coerceIn(0.0, 5.0)
    val hr = heartRateBpm.coerceIn(40.0, 200.0)

    val oxyConsumption = 0.1 + 0.0001 * hr
    val co2Production = 0.11 + 0.0002 * hr
    val metabolicScale = oxyConsumption / 0.1086

    // Batteries: primary then secondary, ~8 h total.
    val batDrain = BATTERY_DRAIN_PCT_PER_SEC * dt
    if (batPri > 5.0) batPri -= batDrain else batSec = (batSec - batDrain).coerceAtLeast(0.0)
    // Oxygen: primary tank, scaled by metabolic demand.
    val oxyDrain = OXY_DRAIN_PCT_PER_SEC * metabolicScale * dt
    if (oxyPri > 2.0) oxyPri -= oxyDrain else oxySec = (oxySec - oxyDrain).coerceAtLeast(0.0)
    // CO2 scrubbers: active bed loads, swap at 95%.
    val scrubLoad = SCRUB_LOAD_PCT_PER_SEC * (co2Production / 0.1272) * dt
    if (activeA) scrubA = (scrubA + scrubLoad).coerceAtMost(100.0) else scrubB = (scrubB + scrubLoad).coerceAtMost(100.0)
    if (activeA && scrubA >= 95.0 && scrubB < 95.0) activeA = false
    if (!activeA && scrubB >= 95.0 && scrubA < 95.0) activeA = true
    coolant = (coolant - COOLANT_DRAIN_PCT_PER_SEC * dt).coerceAtLeast(0.0)

    val activeBed = if (activeA) scrubA else scrubB
    val scrubberEfficiency = if (activeBed >= 90.0) 1.0 + (activeBed - 90.0) * 0.25 else 1.0

    val fanPri =
      drill.fanPriRpmOverride ?: (30000f + (150.0 * sin(tSec * 0.7)).toFloat())
    val fanSec = 30000f + (120.0 * sin(tSec * 0.5 + 1.0)).toFloat()
    val fanPenalty = if (fanPri < 20000f) 30000.0 / fanPri.coerceAtLeast(3000f) else 1.0

    val helmetCo2Psi =
      drill.ppCo2OverrideMmHg?.let { it / SuitTelemetry.PSI_TO_MMHG }
        ?: (BASE_HELMET_CO2_PSI * (co2Production / 0.1272) * scrubberEfficiency * fanPenalty).toFloat()
    val suitCo2 = helmetCo2Psi / 1.5f
    val other = 0.01f
    val total = drill.suitPressureOverridePsi ?: NOMINAL_SUIT_PSI
    val suitOxy = (total - suitCo2 - other).coerceAtLeast(0f)

    val priBattery = drill.suitPowerPercentOverride?.toFloat() ?: batPri.toFloat()
    val secBattery = if (drill.suitPowerPercentOverride != null) 0f else batSec.toFloat()
    val batteryLeft =
      drill.suitPowerHoursOverride?.let { (it * 3600).toInt() }
        ?: ((priBattery + secBattery) / BATTERY_DRAIN_PCT_PER_SEC).toInt()
    val oxyPriPct = drill.oxygenPercentOverride?.toFloat() ?: oxyPri.toFloat()
    val oxyLeft =
      drill.oxygenHoursOverride?.let { (it * 3600).toInt() }
        ?: (oxyPriPct / (OXY_DRAIN_PCT_PER_SEC * metabolicScale)).toInt()

    return SuitTelemetry(
      primaryBatteryPct = priBattery,
      secondaryBatteryPct = secBattery,
      batteryTimeLeftSec = batteryLeft,
      oxyPriStoragePct = oxyPriPct,
      oxySecStoragePct = oxySec.toFloat(),
      oxyPriPressurePsi = oxyPriPct * 30f,
      oxySecPressurePsi = oxySec.toFloat() * 30f,
      oxyTimeLeftSec = oxyLeft,
      oxyConsumptionPsiMin = oxyConsumption.toFloat(),
      co2ProductionPsiMin = co2Production.toFloat(),
      suitPressureOxyPsi = suitOxy,
      suitPressureCo2Psi = suitCo2,
      suitPressureOtherPsi = other,
      suitPressureTotalPsi = total,
      helmetPressureCo2Psi = helmetCo2Psi,
      fanPriRpm = fanPri,
      fanSecRpm = fanSec,
      scrubberACo2StoragePct = scrubA.toFloat(),
      scrubberBCo2StoragePct = scrubB.toFloat(),
      activeScrubber = if (activeA) 'A' else 'B',
      suitTempC = (21.1 + 1.5 * sin(tSec * 0.018) + (hr - 86.0) * 0.04 + drill.coreTempOffsetC * 2.0).toFloat(),
      coolantStoragePct = coolant.toFloat(),
      coolantLiquidPressurePsi = (500.0 + 2.0 * sin(tSec * 0.015)).toFloat(),
    )
  }

  companion object {
    const val NOMINAL_SUIT_PSI = 4.3f
    private const val BATTERY_DRAIN_PCT_PER_SEC = 100.0 / (8.0 * 3600.0) / 2.0
    private const val OXY_DRAIN_PCT_PER_SEC = 100.0 / (8.0 * 3600.0)
    private const val SCRUB_LOAD_PCT_PER_SEC = 0.012
    private const val COOLANT_DRAIN_PCT_PER_SEC = 0.0015
    private const val BASE_HELMET_CO2_PSI = 1.8 / 51.715
  }
}
