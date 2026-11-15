// Port of eye/BlinkAnalyzer.kt plus the per-frame maths from camera/FaceEyeAnalyzer.kt.
import type { EyeMetrics as LoadEyeMetrics } from "./mental";

export interface EyeSample { timestampMs: number; faceDetected: boolean; closureLeft: number; closureRight: number; gazeX: number; gazeY: number }
export type EyeTrackingStatus = "OFF" | "STARTING" | "ACTIVE" | "UNSUPPORTED_DEVICE" | "MODEL_ERROR";

export interface EyeMetrics extends LoadEyeMetrics {
  faceDetected: boolean;
  totalBlinks: number;
  baselineBlinkRate: number | null;
  longBlinkCount: number;
  gazeDispersion: number | null;
  faceCoverage: number;
  eyeOpenness: number;
  calibrationProgress: number;
}

const POPULATION_BASELINE = 17;
const CLOSE_T = 0.5, OPEN_T = 0.35, PERCLOS_CLOSURE = 0.8;
const MIN_BLINK = 50, MAX_BLINK = 1500, LONG_BLINK = 400, MAX_GAP = 500, MIN_OBS_RATE = 15000, MIN_FRAMES = 30;

export const emptyEyeMetrics = (): EyeMetrics => ({
  faceDetected: false, totalBlinks: 0, blinkRatePerMin: null, baselineBlinkRate: null, meanBlinkDurationMs: null,
  longBlinkCount: 0, perclos: null, gazeDispersion: null, faceCoverage: 0, eyeOpenness: 1, calibrationProgress: 0,
  effectiveBaseline: POPULATION_BASELINE, isReliable: false,
});

interface Frame { ts: number; face: boolean; closed80: boolean; gx: number; gy: number }

export class BlinkAnalyzer {
  private frames: Frame[] = [];
  private blinks: { endTs: number; durationMs: number }[] = [];
  private total = 0;
  private blinkStart: number | null = null;
  private lastTs: number | null = null;
  private lastOpen = 1;
  private lastFace = false;
  private calibMs = 0;
  private calibBlinks = 0;
  private baseline: number | null = null;
  constructor(private windowMs = 60000, private calibrationMs = 120000) {}

  process(s: EyeSample) {
    if (this.lastTs !== null && s.timestampMs <= this.lastTs) return;
    const dt = this.lastTs === null ? 0 : Math.min(s.timestampMs - this.lastTs, MAX_GAP);
    this.lastTs = s.timestampMs;
    this.lastFace = s.faceDetected;
    if (!s.faceDetected) {
      this.blinkStart = null;
      this.frames.push({ ts: s.timestampMs, face: false, closed80: false, gx: 0, gy: 0 });
      this.trim(s.timestampMs);
      return;
    }
    const closure = Math.min(1, Math.max(0, (s.closureLeft + s.closureRight) * 0.5));
    this.lastOpen = 1 - closure;
    if (this.baseline === null) this.calibMs += dt;
    if (this.blinkStart === null) {
      if (closure >= CLOSE_T) this.blinkStart = s.timestampMs;
    } else if (closure <= OPEN_T) {
      const d = s.timestampMs - this.blinkStart;
      this.blinkStart = null;
      if (d >= MIN_BLINK && d <= MAX_BLINK) {
        this.blinks.push({ endTs: s.timestampMs, durationMs: d });
        this.total++;
        if (this.baseline === null) this.calibBlinks++;
      }
    }
    if (this.baseline === null && this.calibMs >= this.calibrationMs) {
      this.baseline = Math.min(32, Math.max(6, this.calibBlinks / (this.calibMs / 60000)));
    }
    this.frames.push({ ts: s.timestampMs, face: true, closed80: closure >= PERCLOS_CLOSURE, gx: s.gazeX, gy: s.gazeY });
    this.trim(s.timestampMs);
  }

  private trim(now: number) {
    while (this.frames.length && now - this.frames[0].ts > this.windowMs) this.frames.shift();
    while (this.blinks.length && now - this.blinks[0].endTs > this.windowMs) this.blinks.shift();
  }

  private progress() {
    return this.baseline !== null ? 1 : Math.min(1, this.calibMs / this.calibrationMs);
  }

  metrics(): EyeMetrics {
    const base = this.baseline ?? POPULATION_BASELINE;
    if (!this.frames.length) return { ...emptyEyeMetrics(), baselineBlinkRate: this.baseline, calibrationProgress: this.progress(), totalBlinks: this.total, effectiveBaseline: base };
    let face = 0, closed = 0, obs = 0, sx = 0, sy = 0, sxx = 0, syy = 0;
    let prev: Frame | null = null;
    for (const f of this.frames) {
      if (f.face) {
        face++;
        if (f.closed80) closed++;
        sx += f.gx; sy += f.gy; sxx += f.gx * f.gx; syy += f.gy * f.gy;
        if (prev && prev.face) obs += Math.min(f.ts - prev.ts, MAX_GAP);
      }
      prev = f;
    }
    const coverage = face / this.frames.length;
    const rate = obs >= MIN_OBS_RATE ? this.blinks.length / (obs / 60000) : null;
    const meanDur = this.blinks.length ? this.blinks.reduce((a, b) => a + b.durationMs, 0) / this.blinks.length : null;
    const perclos = face >= MIN_FRAMES ? closed / face : null;
    let disp: number | null = null;
    if (face >= MIN_FRAMES) {
      const vx = Math.max(0, sxx / face - (sx / face) ** 2), vy = Math.max(0, syy / face - (sy / face) ** 2);
      disp = Math.sqrt(vx + vy);
    }
    return {
      faceDetected: this.lastFace,
      totalBlinks: this.total,
      blinkRatePerMin: rate,
      baselineBlinkRate: this.baseline,
      meanBlinkDurationMs: meanDur,
      longBlinkCount: this.blinks.filter((b) => b.durationMs >= LONG_BLINK).length,
      perclos,
      gazeDispersion: disp,
      faceCoverage: coverage,
      eyeOpenness: this.lastFace ? this.lastOpen : 1,
      calibrationProgress: this.progress(),
      effectiveBaseline: base,
      isReliable: rate !== null && coverage >= 0.35,
    };
  }
}

/** Iris offset inside the eye opening, as in FaceEyeAnalyzer (landmarks in image coords). */
export function gazeFromLandmarks(lm: { x: number; y: number }[]): [number, number] {
  if (lm.length < 478) return [0, 0];
  const g = (iris: number, outer: number, inner: number, upper: number, lower: number): [number, number] => {
    const w = Math.max(1e-4, Math.abs(lm[outer].x - lm[inner].x));
    const cx = (lm[outer].x + lm[inner].x) * 0.5, cy = (lm[upper].y + lm[lower].y) * 0.5;
    return [(lm[iris].x - cx) / (w * 0.5), (lm[iris].y - cy) / (w * 0.5)];
  };
  const [lx, ly] = g(468, 33, 133, 159, 145);
  const [rx, ry] = g(473, 263, 362, 386, 374);
  return [(lx + rx) * 0.5, (ly + ry) * 0.5];
}
