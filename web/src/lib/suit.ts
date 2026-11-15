// Port of suit/SuitTelemetryModel.kt, suit/AxemuEnvironment.kt, radiation/RadiationModel.kt and
// body/BodyMaintenance.kt. Modelled values for training (a phone has no radiation detector).
import type { Drill } from "./mental";

const clamp = (v: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, v));

export const PSI_TO_MMHG = 51.715;

export interface SuitTelemetry {
  primaryBatteryPct: number;
  secondaryBatteryPct: number;
  batteryTimeLeftSec: number;
  oxyPriStoragePct: number;
  oxySecStoragePct: number;
  oxyTimeLeftSec: number;
  suitPressureTotalPsi: number;
  helmetCo2MmHg: number;
  fanPriRpm: number;
  activeScrubber: "A" | "B";
  scrubberPct: number;
  suitTempC: number;
  coolantStoragePct: number;
  isLowPower: boolean;
  isPressureNominal: boolean;
  isOxygenLow: boolean;
  isFanFault: boolean;
}

const BAT_DRAIN = 100 / (8 * 3600) / 2;
const OXY_DRAIN = 100 / (8 * 3600);
const SCRUB_LOAD = 0.012;
const COOLANT_DRAIN = 0.0015;
const BASE_HELMET_CO2_PSI = 1.8 / PSI_TO_MMHG;
export const NOMINAL_SUIT_PSI = 4.3;

export class SuitTelemetryModel {
  private batPri = 92;
  private batSec = 100;
  private oxyPri = 88;
  private oxySec = 100;
  private scrubA = 12;
  private scrubB = 0;
  private activeA = true;
  private coolant = 96;

  step(dtSec: number, heartRate: number, d: Drill, tSec: number): SuitTelemetry {
    const dt = clamp(dtSec, 0, 5);
    const hr = clamp(heartRate, 40, 200);
    const oxyUse = 0.1 + 0.0001 * hr;
    const co2Prod = 0.11 + 0.0002 * hr;
    const metabolic = oxyUse / 0.1086;
    const bd = BAT_DRAIN * dt;
    if (this.batPri > 5) this.batPri -= bd; else this.batSec = Math.max(0, this.batSec - bd);
    const od = OXY_DRAIN * metabolic * dt;
    if (this.oxyPri > 2) this.oxyPri -= od; else this.oxySec = Math.max(0, this.oxySec - od);
    const sl = SCRUB_LOAD * (co2Prod / 0.1272) * dt;
    if (this.activeA) this.scrubA = Math.min(100, this.scrubA + sl); else this.scrubB = Math.min(100, this.scrubB + sl);
    if (this.activeA && this.scrubA >= 95 && this.scrubB < 95) this.activeA = false;
    if (!this.activeA && this.scrubB >= 95 && this.scrubA < 95) this.activeA = true;
    this.coolant = Math.max(0, this.coolant - COOLANT_DRAIN * dt);

    const bed = this.activeA ? this.scrubA : this.scrubB;
    const eff = bed >= 90 ? 1 + (bed - 90) * 0.25 : 1;
    const fanPri = d.fanPriRpmOverride ?? 30000 + 150 * Math.sin(tSec * 0.7);
    const fanPenalty = fanPri < 20000 ? 30000 / Math.max(fanPri, 3000) : 1;
    const helmetCo2Psi = d.ppCo2OverrideMmHg != null ? d.ppCo2OverrideMmHg / PSI_TO_MMHG : BASE_HELMET_CO2_PSI * (co2Prod / 0.1272) * eff * fanPenalty;
    const total = d.suitPressureOverridePsi ?? NOMINAL_SUIT_PSI;
    const pri = d.suitPowerPercentOverride ?? this.batPri;
    const sec = d.suitPowerPercentOverride != null ? 0 : this.batSec;
    const batLeft = d.suitPowerHoursOverride != null ? Math.trunc(d.suitPowerHoursOverride * 3600) : Math.trunc((pri + sec) / BAT_DRAIN);
    const oxy = d.oxygenPercentOverride ?? this.oxyPri;
    const oxyLeft = d.oxygenHoursOverride != null ? Math.trunc(d.oxygenHoursOverride * 3600) : Math.trunc(oxy / (OXY_DRAIN * metabolic));
    return {
      primaryBatteryPct: pri,
      secondaryBatteryPct: sec,
      batteryTimeLeftSec: batLeft,
      oxyPriStoragePct: oxy,
      oxySecStoragePct: this.oxySec,
      oxyTimeLeftSec: oxyLeft,
      suitPressureTotalPsi: total,
      helmetCo2MmHg: helmetCo2Psi * PSI_TO_MMHG,
      fanPriRpm: fanPri,
      activeScrubber: this.activeA ? "A" : "B",
      scrubberPct: bed,
      suitTempC: 21.1 + 1.5 * Math.sin(tSec * 0.018) + (hr - 86) * 0.04 + d.coreTempOffsetC * 2,
      coolantStoragePct: this.coolant,
      isLowPower: pri < 20,
      isPressureNominal: total >= 3.8 && total <= 4.6,
      isOxygenLow: oxy < 25,
      isFanFault: fanPri < 20000,
    };
  }
}

// ------------------------------------------------------------------------------ AxEMU / Jezero

export const Axemu = {
  NAME: "AxEMU",
  RATED_EVA_HOURS: 8,
  RATED_COLD_SOAK_MIN: 120,
  RETURN_RESERVE_MIN: 30,
  COLD_SOAK_BELOW_C: -70,
  HELMET_LIGHT_BELOW_LUX: 40,
} as const;

export interface MarsOutside { airTempC: number; pressurePa: number; isDaylight: boolean }

/** Smooth approximation around Perseverance MEDA values at Jezero (not a MEDA reading). */
export function jezeroWeather(ltstHours: number): MarsOutside {
  return {
    airTempC: -55 + 30 * Math.cos((2 * Math.PI * (ltstHours - 14)) / 24),
    pressurePa: 718 * (1 + 0.03 * Math.cos((2 * Math.PI * (ltstHours - 6)) / 24)),
    isDaylight: ltstHours >= 6 && ltstHours <= 18,
  };
}

export const noiseLabel = (dbfs: number | null) => (dbfs == null ? "—" : dbfs < -55 ? "Quiet" : dbfs < -40 ? "Moderate" : "Loud");

// ------------------------------------------------------------------------------ Radiation

export type RadiationStatus = "NOMINAL" | "ELEVATED" | "STORM";
export type ShelterStatus = "EXPOSED" | "SHELTERED";

export interface RadiationState {
  doseRateUsvPerHour: number;
  evaDoseUsv: number;
  careerLimitFraction: number;
  status: RadiationStatus;
  shelter: ShelterStatus;
  distanceToBaseMeters: number;
  bearingToBaseDeg: number;
  etaToBaseSec: number;
  returnDoseUsv: number;
  advice: string;
}

export const GCR_BASELINE_USV_PER_H = 670 / 24;
const BASE_RADIUS_M = 15;

export class RadiationModel {
  private dose = 0;

  step(dtSec: number, east: number, north: number, speed: number, mult: number, tSec: number): RadiationState {
    const distance = Math.hypot(east, north);
    const sheltered = distance <= BASE_RADIUS_M;
    const gcr = GCR_BASELINE_USV_PER_H * (1 + 0.03 * Math.sin((tSec * 2 * Math.PI) / 600));
    const m = Math.max(0, mult);
    const rate = gcr * m * (sheltered ? 0.4 : 1);
    this.dose += rate * (Math.max(0, dtSec) / 3600);
    const bearing = distance < 0.5 ? 0 : ((Math.atan2(-east, -north) * 180) / Math.PI + 360) % 360;
    const pace = speed >= 0.3 ? speed : 1;
    const eta = sheltered ? 0 : Math.trunc(distance / pace);
    const status: RadiationStatus = mult >= 5 ? "STORM" : mult >= 2 ? "ELEVATED" : "NOMINAL";
    const advice =
      status === "STORM" && !sheltered ? "SPE in progress. Return to base now. Use terrain walls en route."
      : status === "STORM" ? "SPE in progress. Stay inside the shielded base."
      : status === "ELEVATED" && !sheltered ? "Dose elevated. Shorten EVA. Prefer cliff-side routes (~10% less dose)."
      : status === "ELEVATED" ? "Dose elevated. Remain near base."
      : "Dose nominal. Shielding by terrain not required.";
    return {
      doseRateUsvPerHour: rate,
      evaDoseUsv: this.dose,
      careerLimitFraction: this.dose / (600 * 1000),
      status,
      shelter: sheltered ? "SHELTERED" : "EXPOSED",
      distanceToBaseMeters: distance,
      bearingToBaseDeg: bearing,
      etaToBaseSec: eta,
      returnDoseUsv: sheltered ? 0 : (gcr * m * eta) / 3600,
      advice,
    };
  }
}

// ------------------------------------------------------------------------------ Gravity / atrophy

export interface BodyState {
  steps: number;
  earthEquivalentLoadSteps: number;
  exerciseMinutes: number;
  loadGoalFraction: number;
  exerciseGoalFraction: number;
  atrophyRisk: string;
  advice: string;
}

export function gravityLoad(steps: number, exerciseSeconds: number): BodyState {
  const load = Math.trunc(steps * 0.38);
  const exMin = exerciseSeconds / 60;
  const lf = clamp(load / 7500, 0, 1);
  const ef = clamp(exMin / 150, 0, 1);
  const c = (lf + ef) / 2;
  const [risk, advice] =
    c >= 0.9 ? ["On track", "Countermeasure goal met for this sol. Keep loading tomorrow."]
    : c >= 0.5 ? ["Moderate", "Add a loaded carry or squats to close today's gap."]
    : steps < 2000 ? ["High", "Low loading today. Plan resistance exercise before sleep."]
    : ["Elevated", "Keep walking at an aerobic pace to protect bone and muscle."];
  return { steps, earthEquivalentLoadSteps: load, exerciseMinutes: exMin, loadGoalFraction: lf, exerciseGoalFraction: ef, atrophyRisk: risk, advice };
}

/** Accelerometer step detector (peak on magnitude with a refractory period). */
export class StepDetector {
  private mean = 9.81;
  private above = false;
  private lastStepMs = -1e12;
  steps = 0;
  onAccel(x: number, y: number, z: number, t: number): boolean {
    const mag = Math.sqrt(x * x + y * y + z * z);
    this.mean += 0.02 * (mag - this.mean);
    const dev = mag - this.mean;
    if (!this.above && dev > 1.15 && t - this.lastStepMs >= 280) {
      this.above = true;
      this.lastStepMs = t;
      this.steps++;
      return true;
    }
    if (this.above && dev < 1.15 * 0.3) this.above = false;
    return false;
  }
}
