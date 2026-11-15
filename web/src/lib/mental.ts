// Port of mental/MentalVitals.kt, mental/CognitiveLoadModel.kt and predict/TrendForecaster.kt.
// Heuristic decision aid for training, not a clinical instrument.

const clamp = (v: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, v));

// ------------------------------------------------------------------------------ Drills

export interface Drill {
  number: number;
  code: string;
  title: string;
  impactSummary: string;
  isCritical: boolean;
  hrOffsetBpm: number;
  coreTempOffsetC: number;
  hydrationPercentOverride: number | null;
  suitPressureOverridePsi: number | null;
  oxygenPercentOverride: number | null;
  oxygenHoursOverride: number | null;
  suitPowerPercentOverride: number | null;
  suitPowerHoursOverride: number | null;
  ppCo2OverrideMmHg: number | null;
  doseRateMultiplier: number;
  windSpeedOverrideMps: number | null;
  stressBoost: number;
  stressDriverLabel: string;
  sensorDropout: boolean;
  fanPriRpmOverride: number | null;
}

const drill = (d: Partial<Drill> & Pick<Drill, "number" | "code" | "title" | "impactSummary">): Drill => ({
  isCritical: true,
  hrOffsetBpm: 0,
  coreTempOffsetC: 0,
  hydrationPercentOverride: null,
  suitPressureOverridePsi: null,
  oxygenPercentOverride: null,
  oxygenHoursOverride: null,
  suitPowerPercentOverride: null,
  suitPowerHoursOverride: null,
  ppCo2OverrideMmHg: null,
  doseRateMultiplier: 1,
  windSpeedOverrideMps: null,
  stressBoost: 0,
  stressDriverLabel: "NOMINAL",
  sensorDropout: false,
  fanPriRpmOverride: null,
  ...d,
});

export const DRILLS: Drill[] = [
  drill({ number: 0, code: "NOMINAL", title: "Nominal EVA", impactSummary: "Baseline telemetry", isCritical: false }),
  drill({ number: 1, code: "E-01", title: "Pressure Leak", impactSummary: "2.9 psi · +38 BPM", hrOffsetBpm: 38, coreTempOffsetC: 0.2, suitPressureOverridePsi: 2.9, oxygenPercentOverride: 72, oxygenHoursOverride: 2.6, stressBoost: 24, stressDriverLabel: "PRESSURE" }),
  drill({ number: 2, code: "E-02", title: "O2 Depletion", impactSummary: "14% O2 · +28 BPM", hrOffsetBpm: 28, coreTempOffsetC: 0.1, oxygenPercentOverride: 14, oxygenHoursOverride: 0.4, stressBoost: 22, stressDriverLabel: "HYPOXIA" }),
  drill({ number: 3, code: "E-03", title: "Solar Rad Storm", impactSummary: "18x dose · +16 BPM", hrOffsetBpm: 16, coreTempOffsetC: 0.3, doseRateMultiplier: 18, windSpeedOverrideMps: 13.8, stressBoost: 16, stressDriverLabel: "RAD LOAD" }),
  drill({ number: 4, code: "E-04", title: "CO2 Scrubber", impactSummary: "8.4 mmHg · +22 BPM", hrOffsetBpm: 22, coreTempOffsetC: 0.2, ppCo2OverrideMmHg: 8.4, stressBoost: 18, stressDriverLabel: "CO2 LOAD", fanPriRpmOverride: 9000 }),
  drill({ number: 5, code: "E-05", title: "Low Suit Power", impactSummary: "14% PWR · +14 BPM", hrOffsetBpm: 14, coreTempOffsetC: -0.3, suitPowerPercentOverride: 14, suitPowerHoursOverride: 0.4, ppCo2OverrideMmHg: 3.2, stressBoost: 14, stressDriverLabel: "POWER FAULT" }),
  drill({ number: 6, code: "E-06", title: "Acute Stress", impactSummary: "+46 BPM · 5.8h sleep", hrOffsetBpm: 46, coreTempOffsetC: 0.4, hydrationPercentOverride: 64, ppCo2OverrideMmHg: 2.6, stressBoost: 32, stressDriverLabel: "ACUTE STRESS" }),
  drill({ number: 7, code: "E-07", title: "Dust Gale Load", impactSummary: "24.8 m/s · 38.6 C", isCritical: false, hrOffsetBpm: 26, coreTempOffsetC: 1.5, hydrationPercentOverride: 34, suitPressureOverridePsi: 4.1, suitPowerPercentOverride: 76, suitPowerHoursOverride: 3.6, ppCo2OverrideMmHg: 3.4, windSpeedOverrideMps: 24.8, stressBoost: 16, stressDriverLabel: "THERMAL" }),
  drill({ number: 8, code: "E-08", title: "Dehydration", impactSummary: "22% H2O · 38.1 C", isCritical: false, hrOffsetBpm: 18, coreTempOffsetC: 1.0, hydrationPercentOverride: 22, stressBoost: 14, stressDriverLabel: "DEHYDRATED" }),
  drill({ number: 9, code: "E-09", title: "Sensor Dropout", impactSummary: "HR/SpO2/BP --", sensorDropout: true, stressDriverLabel: "SENSOR LOSS" }),
  drill({ number: 10, code: "E-10", title: "Compound Decomp", impactSummary: "Multi-fault · +52 BPM", hrOffsetBpm: 52, coreTempOffsetC: 1.1, hydrationPercentOverride: 38, suitPressureOverridePsi: 3.1, oxygenPercentOverride: 19, oxygenHoursOverride: 0.6, suitPowerPercentOverride: 18, suitPowerHoursOverride: 0.5, ppCo2OverrideMmHg: 7.6, doseRateMultiplier: 9, windSpeedOverrideMps: 19.4, stressBoost: 38, stressDriverLabel: "MULTI-FAULT" }),
];
export const NO_DRILL = DRILLS[0];
export const drillByNumber = (n: number) => DRILLS.find((d) => d.number === n) ?? null;

/** Short self-sufficiency prompts for training drills (not official NASA procedures). */
export function checklistFor(d: Drill): string[] {
  switch (d.number) {
    case 1: return ["Stop work, stay calm", "Watch suit pressure trend", "Head to base now"];
    case 2: return ["Slow breathing", "Switch to secondary O₂", "Head to base now"];
    case 3: return ["Follow base arrow", "Use terrain walls", "Shelter until clear"];
    case 4: return ["Rest to cut CO₂", "Check fan & scrubber", "Return if > 6 mmHg"];
    case 5: return ["Shed non-essential load", "Lights low", "Return on reserve"];
    case 6: return ["Pause the task", "Box-breathe 2 min", "Re-check load"];
    case 7: return ["Face away from gusts", "Slow your pace", "Cool down, hydrate"];
    case 8: return ["Drink now", "Reduce exertion", "Re-check core temp"];
    case 9: return ["Re-seat biosensor", "Report status by voice", "Buddy check"];
    case 10: return ["Stop, stabilise", "Secondary O₂", "Return to base now"];
    default: return [];
  }
}

// ------------------------------------------------------------------------------ Readiness

class Readiness {
  private lagged: number | null = null;
  private lastMs = 0;
  private xs: number[] = [];
  private ys: number[] = [];
  private a = 0;
  private b = 0;
  private fitted = false;
  private sinceFit = 0;
  private frozen = false;
  private shown: number | null = null;
  private lowSince = -1;

  update(tMs: number, hr: number | null, workload: number | null, co2: number | null) {
    const gapS = this.lastMs === 0 ? 1 : (tMs - this.lastMs) / 1000;
    this.lastMs = tMs;
    if (hr == null || workload == null) {
      this.shown = null;
      this.lowSince = -1;
      return this.state(null, null, null, 0, 0, tMs);
    }
    const dt = clamp(gapS, 0, 5);
    const prev = this.lagged;
    const x = prev == null || gapS > 30 ? workload : prev + (workload - prev) * Math.min(dt / 30, 1);
    this.lagged = x;
    if (!this.frozen) this.learn(x, hr);
    if (!this.fitted) return this.state(null, null, null, 0, 0, tMs);
    const expected = this.a + this.b * x;
    const residual = hr - expected;
    const off = Math.max(0, Math.max(residual - 8, -residual - 15));
    const hrPenalty = Math.min(off * 3, 60);
    const co2Penalty = clamp((((co2 ?? 4) - 4) / 6) * 50, 0, 50);
    const raw = clamp(100 - hrPenalty - co2Penalty, 0, 100);
    const s = this.shown == null ? raw : this.shown + (raw - this.shown) * Math.min(dt / 5, 1);
    this.shown = s;
    const score = Math.round(s);
    if (score < 50) { if (this.lowSince < 0) this.lowSince = tMs; } else this.lowSince = -1;
    return this.state(score, expected, residual, hrPenalty, co2Penalty, tMs);
  }

  private learn(x: number, hr: number) {
    if (this.fitted && Math.abs(hr - (this.a + this.b * x)) > 12) return;
    this.xs.push(x);
    this.ys.push(hr);
    if (this.xs.length > 300) { this.xs.shift(); this.ys.shift(); }
    if (this.fitted) this.sinceFit++;
    if (this.xs.length >= 120) this.fit();
    if (this.fitted && this.sinceFit >= 60) { this.frozen = true; this.xs = []; this.ys = []; }
  }

  private fit() {
    const n = this.xs.length;
    let sx = 0, sy = 0, sxx = 0, sxy = 0, syy = 0, lo = Infinity, hi = -Infinity;
    for (let k = 0; k < n; k++) {
      const x = this.xs[k], y = this.ys[k];
      sx += x; sy += y; sxx += x * x; sxy += x * y; syy += y * y;
      lo = Math.min(lo, x); hi = Math.max(hi, x);
    }
    const vx = n * sxx - sx * sx, vy = n * syy - sy * sy;
    if (vx <= 1e-12 || vy <= 1e-12) return;
    if (hi - lo < 0.1 * Math.abs(sx / n)) return;
    const cov = n * sxy - sx * sy;
    const slope = cov / vx;
    if ((cov * cov) / (vx * vy) >= 0.3 && slope > 0) {
      this.b = slope;
      this.a = (sy - slope * sx) / n;
      this.fitted = true;
    }
  }

  private state(score: number | null, expected: number | null, residual: number | null, hrPenalty: number, co2Penalty: number, tMs: number) {
    return {
      score,
      calibrated: this.frozen,
      expectedHr: expected,
      residual,
      hrPenalty: Math.round(hrPenalty),
      co2Penalty: Math.round(co2Penalty),
      lowForMs: this.lowSince >= 0 ? tMs - this.lowSince : 0,
    };
  }
}

export type StressLevel = "LOW" | "MODERATE" | "HIGH" | "CRITICAL" | "UNKNOWN";
export type MentalBand = "OK" | "CAUTION" | "WARNING" | "UNKNOWN";

export interface SleepState { durationHours: number; qualityPercent: number; deepMinutes: number; remMinutes: number; sleepStatus: string }

export interface MentalState {
  heartRate: number | null;
  score: number | null;
  band: MentalBand;
  expectedHr: number | null;
  stressPercent: number;
  stressLevel: StressLevel;
  stressDriver: string;
  sleep: SleepState;
}

function sleepFor(d: Drill, evaSeconds: number): SleepState {
  const h = evaSeconds / 3600;
  const dur = clamp(7.4 - h * 0.15, 5.5, 8);
  const q = clamp(88 - Math.trunc(h * 2), 65, 95);
  switch (d.number) {
    case 6: return { durationHours: 5.8, qualityPercent: 62, deepMinutes: 74, remMinutes: 68, sleepStatus: "FATIGUED" };
    case 10: return { durationHours: 4.6, qualityPercent: 48, deepMinutes: 52, remMinutes: 50, sleepStatus: "DEPRIVED" };
    case 4: case 7: case 8: return { durationHours: 6.3, qualityPercent: 71, deepMinutes: 86, remMinutes: 82, sleepStatus: "FATIGUED" };
    default: return { durationHours: dur, qualityPercent: q, deepMinutes: 112, remMinutes: 104, sleepStatus: q >= 78 ? "RESTED" : "FATIGUED" };
  }
}

export class MentalVitals {
  private readiness = new Readiness();

  constructor() {
    // Pre-seed a calm baseline so readiness is calibrated from the first second (as on Android).
    let t = -200_000;
    for (let i = 0; i < 190; i++) {
      const phase = 0.5 + 0.5 * Math.sin((2 * Math.PI * i) / 60);
      const o2 = 0.09 + 0.05 * phase;
      this.readiness.update(t, 84 + 350 * (o2 - 0.09), o2, 1.8);
      t += 1000;
    }
  }

  evaluate(nowMs: number, hr: number | null, workload: number, co2: number, d: Drill, evaSeconds: number): MentalState {
    const r = this.readiness.update(nowMs, hr, workload, co2);
    const sleep = sleepFor(d, evaSeconds);
    if (hr == null || d.sensorDropout) {
      return { heartRate: null, score: null, band: "UNKNOWN", expectedHr: r.expectedHr, stressPercent: 0, stressLevel: "UNKNOWN", stressDriver: "SENSOR LOSS", sleep };
    }
    const excessHr = Math.max(0, hr - 86) * 1.1;
    const co2Load = Math.max(0, co2 - 2) * 6.5;
    const readinessPenalty = (r.hrPenalty + r.co2Penalty) * 0.6;
    const fatigue = (100 - sleep.qualityPercent) * 0.25;
    const stress = clamp(Math.round(8 + Math.max(readinessPenalty, excessHr * 0.72) + co2Load + d.stressBoost + fatigue), 5, 99);
    const level: StressLevel = stress >= 80 ? "CRITICAL" : stress >= 58 ? "HIGH" : stress >= 32 ? "MODERATE" : "LOW";
    const driver = d.number !== 0 ? d.stressDriverLabel : co2 >= 3.5 ? "CO2 LOAD" : hr >= 115 ? "EXERTION" : "NOMINAL";
    const band: MentalBand =
      r.score == null ? "UNKNOWN" : r.score < 50 || level === "CRITICAL" ? "WARNING" : r.score < 70 || level === "HIGH" ? "CAUTION" : "OK";
    return { heartRate: hr, score: r.score, band, expectedHr: r.expectedHr, stressPercent: stress, stressLevel: level, stressDriver: driver, sleep };
  }
}

// ------------------------------------------------------------------------------ Cognitive load

export type CognitiveLevel = "NOMINAL" | "CAUTION" | "WARNING" | "EMERGENCY";
export const LEVEL_ORDER: CognitiveLevel[] = ["NOMINAL", "CAUTION", "WARNING", "EMERGENCY"];

export interface EyeMetrics {
  isReliable: boolean;
  blinkRatePerMin: number | null;
  effectiveBaseline: number;
  perclos: number | null;
  meanBlinkDurationMs: number | null;
}

export interface CognitiveInputs {
  eye: EyeMetrics | null;
  heartRateBpm: number | null;
  expectedHrBpm: number | null;
  stressPercent: number | null;
  helmetCo2MmHg: number;
  coreTempC: number;
  sleepQualityPercent: number;
}

export interface CognitiveState {
  index: number;
  level: CognitiveLevel;
  ocular: number | null;
  physiological: number | null;
  environment: number;
  sleep: number;
  topDriver: string;
  ocularPattern: string;
  recommendation: string;
  usesEyeData: boolean;
}

export const CAUTION_AT = 70, WARNING_AT = 90, EMERGENCY_AT = 95;

export const levelFor = (i: number): CognitiveLevel =>
  i >= EMERGENCY_AT ? "EMERGENCY" : i >= WARNING_AT ? "WARNING" : i >= CAUTION_AT ? "CAUTION" : "NOMINAL";

export const recommendationFor = (l: CognitiveLevel) =>
  ({
    NOMINAL: "Steady. Keep your pace.",
    CAUTION: "Slow down. Box-breathe 4-4-4-4.",
    WARNING: "Pause the task. Check in with CAPCOM.",
    EMERGENCY: "Stop. Stabilise posture. Crew alerted.",
  })[l];

const c01 = (v: number) => clamp(v, 0, 1);

export function ocularScore(eye: EyeMetrics | null): [number | null, string] {
  if (!eye || !eye.isReliable || eye.blinkRatePerMin == null) return [null, "NO EYE DATA"];
  const ratio = eye.blinkRatePerMin / eye.effectiveBaseline;
  const suppression = c01((1 - ratio) / 0.6) * 100;
  const elevated = c01((ratio - 1.3) / 0.7) * 100;
  const perclos = eye.perclos != null ? c01((eye.perclos - 0.04) / 0.11) * 100 : 0;
  const duration = eye.meanBlinkDurationMs != null ? c01((eye.meanBlinkDurationMs - 150) / 250) * 100 : 0;
  const fatigue = Math.max(Math.max(perclos, duration), elevated * 0.7);
  return fatigue > suppression
    ? [fatigue, fatigue >= 25 ? "FATIGUE" : "NORMAL"]
    : [suppression, suppression >= 25 ? "BLINK SUPPRESSION" : "NORMAL"];
}

export class CognitiveLoadModel {
  private smoothed: number | null = null;
  private lastMs: number | null = null;

  evaluate(nowMs: number, i: CognitiveInputs): CognitiveState {
    const [ocular, pattern] = ocularScore(i.eye);
    const physio = i.heartRateBpm == null ? null : 0.55 * clamp(i.stressPercent ?? 0, 0, 100) + 0.45 * c01((i.heartRateBpm - (i.expectedHrBpm ?? 86)) / 40) * 100;
    const env = Math.max(c01((i.helmetCo2MmHg - 3) / 5) * 100, c01((i.coreTempC - 37.5) / 1.5) * 100);
    const sleep = c01((85 - i.sleepQualityPercent) / 40) * 100;
    let w = 0, ws = 0;
    const add = (s: number | null, wt: number) => { if (s != null) { w += s * wt; ws += wt; } };
    add(ocular, 0.35); add(physio, 0.4); add(env, 0.15); add(sleep, 0.1);
    const mean = ws > 0 ? w / ws : 0;
    const comps = [ocular, physio, env, sleep].filter((v): v is number => v != null);
    const worst = comps.length ? Math.max(...comps) : 0;
    const raw = clamp(mean + 0.5 * (worst - mean), 0, 100);
    const prev = this.smoothed, prevMs = this.lastMs;
    const value = prev == null || prevMs == null || raw >= prev ? raw : prev + (raw - prev) * Math.min(Math.max(0, nowMs - prevMs) / 8000, 1);
    this.smoothed = value;
    this.lastMs = nowMs;
    const index = clamp(Math.round(value), 0, 100);
    const level = levelFor(index);
    let driver: string;
    if (worst === 0) driver = "NOMINAL";
    else if (worst === ocular) driver = pattern === "FATIGUE" ? "EYE FATIGUE" : "VISUAL LOAD";
    else if (worst === physio) driver = (i.stressPercent ?? 0) >= 58 ? "STRESS" : "HEART RATE";
    else if (worst === env) driver = i.helmetCo2MmHg >= 3 ? "HELMET CO2" : "THERMAL";
    else driver = "SLEEP DEBT";
    return {
      index,
      level,
      ocular: ocular == null ? null : Math.round(ocular),
      physiological: physio == null ? null : Math.round(physio),
      environment: Math.round(env),
      sleep: Math.round(sleep),
      topDriver: level === "NOMINAL" && worst < 30 ? "NOMINAL" : driver,
      ocularPattern: pattern,
      recommendation: recommendationFor(level),
      usesEyeData: ocular != null,
    };
  }
}

// ------------------------------------------------------------------------------ Trend forecaster

export interface Forecast { slopePerMin: number; projected: number; horizonMin: number; minutesToThreshold: number | null }

export class TrendForecaster {
  private ts: number[] = [];
  private vs: number[] = [];
  constructor(private windowMs: number, private minSamples = 10) {}

  add(t: number, v: number) {
    if (this.ts.length && t <= this.ts[this.ts.length - 1]) return;
    this.ts.push(t);
    this.vs.push(v);
    while (this.ts.length && t - this.ts[0] > this.windowMs) { this.ts.shift(); this.vs.shift(); }
  }

  forecast(horizonMin: number, threshold: number, rising: boolean): Forecast | null {
    const n = this.ts.length;
    if (n < this.minSamples) return null;
    const t0 = this.ts[0];
    let sx = 0, sy = 0, sxx = 0, sxy = 0;
    for (let i = 0; i < n; i++) {
      const x = (this.ts[i] - t0) / 60000, y = this.vs[i];
      sx += x; sy += y; sxx += x * x; sxy += x * y;
    }
    const den = n * sxx - sx * sx;
    const slope = den <= 1e-12 ? 0 : (n * sxy - sx * sy) / den;
    const intercept = (sy - slope * sx) / n;
    const now = intercept + slope * ((this.ts[n - 1] - t0) / 60000);
    const projected = now + slope * horizonMin;
    let to: number | null = null;
    if (rising && now >= threshold) to = 0;
    else if (!rising && now <= threshold) to = 0;
    else if (rising && slope > 1e-6) to = (threshold - now) / slope;
    else if (!rising && slope < -1e-6) to = (now - threshold) / -slope;
    return { slopePerMin: slope, projected, horizonMin, minutesToThreshold: to };
  }
}
