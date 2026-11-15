// Port of nasa/MarsClock.kt (Mars24 algorithm, JPL approximate ephemeris) and nasa/SpaceWeather.kt.

const rad = (d: number) => (d * Math.PI) / 180;
const deg = (r: number) => (r * 180) / Math.PI;
export const mod = (v: number, m: number) => ((v % m) + m) % m;

export interface MarsTime {
  marsSolDate: number;
  solarLongitudeDeg: number;
  coordinatedMarsTimeHours: number;
  localMeanSolarTimeHours: number;
  localTrueSolarTimeHours: number;
}

export const JEZERO_EAST_LON_DEG = 77.45;
const TT_MINUS_UTC_SEC = 69.184;
const PBS = [
  [0.0071, 2.2353, 49.409], [0.0057, 2.7543, 168.173], [0.0039, 1.1177, 191.837], [0.0037, 15.7866, 21.736],
  [0.0021, 2.1354, 15.704], [0.002, 2.4694, 95.528], [0.0018, 32.8493, 49.095],
];

export function mars24(utcMs: number, eastLon = JEZERO_EAST_LON_DEG): MarsTime {
  const jdTt = 2440587.5 + utcMs / 86400000 + TT_MINUS_UTC_SEC / 86400;
  const dt = jdTt - 2451545.0;
  const m = rad(19.3871 + 0.52402073 * dt);
  const alphaFms = 270.3871 + 0.524038496 * dt;
  let pbs = 0;
  for (const p of PBS) pbs += p[0] * Math.cos(rad((0.985626 * dt) / p[1] + p[2]));
  const vMinusM =
    (10.691 + 3.0e-7 * dt) * Math.sin(m) + 0.623 * Math.sin(2 * m) + 0.05 * Math.sin(3 * m) + 0.005 * Math.sin(4 * m) + 0.0005 * Math.sin(5 * m) + pbs;
  const ls = mod(alphaFms + vMinusM, 360);
  const lsR = rad(ls);
  const eot = 2.861 * Math.sin(2 * lsR) - 0.071 * Math.sin(4 * lsR) + 0.002 * Math.sin(6 * lsR) - vMinusM;
  const msd = (jdTt - 2451549.5) / 1.0274912517 + 44796.0 - 0.0009626;
  const mtc = mod(24 * msd, 24);
  const lmst = mod(mtc - mod(360 - eastLon, 360) / 15, 24);
  return { marsSolDate: msd, solarLongitudeDeg: ls, coordinatedMarsTimeHours: mtc, localMeanSolarTimeHours: lmst, localTrueSolarTimeHours: mod(lmst + eot / 15, 24) };
}

export function formatHours(h: number): string {
  const t = Math.trunc(h * 60);
  return `${String(Math.trunc(t / 60) % 24).padStart(2, "0")}:${String(t % 60).padStart(2, "0")}`;
}

export function marsSeason(ls: number) {
  const l = mod(ls, 360);
  return l < 90 ? "Spring" : l < 180 ? "Summer" : l < 270 ? "Autumn" : "Winter";
}

type El = [number, number, number, number, number, number, number, number, number, number, number, number];
const EMB: El = [1.00000261, 0.00000562, 0.01671123, -0.00004392, -0.00001531, -0.01294668, 100.46457166, 35999.37244981, 102.93768193, 0.32327364, 0, 0];
const MARS: El = [1.52371034, 0.00001847, 0.0933941, 0.00007882, 1.84969142, -0.00813131, -4.55343205, 19140.30268499, -23.94362959, 0.44441088, 49.55953891, -0.29257343];

function position(el: El, utcMs: number): [number, number, number] {
  const t = (2440587.5 + utcMs / 86400000 - 2451545.0) / 36525;
  const a = el[0] + el[1] * t, e = el[2] + el[3] * t, inc = rad(el[4] + el[5] * t);
  const l = el[6] + el[7] * t, varpi = el[8] + el[9] * t, node = rad(el[10] + el[11] * t);
  const omega = rad(varpi) - node;
  let mDeg = mod(l - varpi, 360);
  if (mDeg > 180) mDeg -= 360;
  const mR = rad(mDeg);
  let E = mR + e * Math.sin(mR);
  for (let i = 0; i < 8; i++) E -= (E - e * Math.sin(E) - mR) / (1 - e * Math.cos(E));
  const xp = a * (Math.cos(E) - e), yp = a * Math.sqrt(1 - e * e) * Math.sin(E);
  const co = Math.cos(omega), so = Math.sin(omega), cn = Math.cos(node), sn = Math.sin(node), ci = Math.cos(inc), si = Math.sin(inc);
  return [
    (co * cn - so * sn * ci) * xp + (-so * cn - co * sn * ci) * yp,
    (co * sn + so * cn * ci) * xp + (-so * sn + co * cn * ci) * yp,
    so * si * xp + co * si * yp,
  ];
}

export function earthMarsDistanceAu(utcMs: number) {
  const e = position(EMB, utcMs), m = position(MARS, utcMs);
  return Math.hypot(m[0] - e[0], m[1] - e[1], m[2] - e[2]);
}
export const oneWayLightTimeSec = (utcMs: number) => (earthMarsDistanceAu(utcMs) * 149597870.7) / 299792.458;
export function isNearSolarConjunction(utcMs: number) {
  const e = position(EMB, utcMs), m = position(MARS, utcMs);
  let d = deg(Math.atan2(m[1] - e[1], m[0] - e[0]) - Math.atan2(-e[1], -e[0])) % 360;
  if (d > 180) d -= 360;
  if (d < -180) d += 360;
  return Math.abs(d) < 5;
}

// ------------------------------------------------------------------------------ Space weather

export type SpaceWeatherLevel = "UNKNOWN" | "QUIET" | "ACTIVE" | "SEP";
export const SW_LABEL: Record<SpaceWeatherLevel, string> = { UNKNOWN: "No data", QUIET: "Quiet", ACTIVE: "Active", SEP: "SEP event" };
export interface SpaceWeatherEvent { type: "FLR" | "SEP"; timeMs: number; classType: string | null }
export interface SpaceWeatherState { level: SpaceWeatherLevel; sepRiskPercent: number | null; flaresLast72h: number; strongestFlare: string | null; sepLast7d: number; fetchedAtMs: number }

export const EMPTY_SPACE_WEATHER: SpaceWeatherState = { level: "UNKNOWN", sepRiskPercent: null, flaresLast72h: 0, strongestFlare: null, sepLast7d: 0, fetchedAtMs: 0 };

export function flareRank(c: string | null): number {
  if (!c) return 0;
  const mag = Number.parseFloat(c.slice(1));
  const m = Number.isFinite(mag) ? mag : 1;
  switch (c[0].toUpperCase()) {
    case "X": return 10 * m;
    case "M": return m;
    case "C": return m / 10;
    default: return 0;
  }
}

/** Heuristic 48 h SEP probability (not a validated operational forecast). */
export function evaluateSpaceWeather(events: SpaceWeatherEvent[], nowMs: number, fetchedAtMs: number): SpaceWeatherState {
  if (fetchedAtMs <= 0) return EMPTY_SPACE_WEATHER;
  const flares = events.filter((e) => e.type === "FLR" && e.timeMs >= nowMs - 72 * 3600000);
  const seps = events.filter((e) => e.type === "SEP" && e.timeMs >= nowMs - 7 * 86400000);
  const recentSep = seps.some((e) => e.timeMs >= nowMs - 48 * 3600000);
  let risk = 3, strongest: string | null = null, sr = -1;
  for (const f of flares) {
    const r = flareRank(f.classType);
    if (r > sr) { sr = r; strongest = f.classType; }
    risk += r >= 10 ? 20 * Math.min(r / 10, 3) : r >= 1 ? 4 : 0.5;
  }
  if (seps.length) risk += 10;
  if (recentSep) risk = Math.max(risk, 90);
  const pct = Math.trunc(Math.min(95, Math.max(1, risk)));
  const level: SpaceWeatherLevel = recentSep ? "SEP" : sr >= 1 || pct >= 20 ? "ACTIVE" : "QUIET";
  return { level, sepRiskPercent: pct, flaresLast72h: flares.length, strongestFlare: strongest, sepLast7d: seps.length, fetchedAtMs };
}

/** DONKI time strings look like "2026-11-14T03:12Z". */
export function parseDonkiTime(s: string | undefined | null): number | null {
  if (!s) return null;
  const t = Date.parse(s.slice(0, 16) + ":00Z");
  return Number.isFinite(t) ? t : null;
}
