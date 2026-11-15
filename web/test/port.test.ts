// Ports of the Android unit tests (HandGestureClassifierTest, CrewLinkTest, MentalHealthModelsTest
// subsets) so the browser build behaves like the app.
import { describe, expect, it } from "vitest";
import { CrewRoster, Geo, type CrewStatus } from "../src/lib/crew";
import { HandGestureClassifier, isClosedFist, resolvePose, type DashboardMode, type HandObservation, type HandPose, type Pt } from "../src/lib/gestures";
import { HudEngine, crewSummary } from "../src/lib/hud";
import { CognitiveLoadModel, TrendForecaster, levelFor } from "../src/lib/mental";
import { mars24, oneWayLightTimeSec, flareRank, evaluateSpaceWeather } from "../src/lib/nasa";
import { angleDelta, focusedPage, project, TILE_SPACING_DEG } from "../src/lib/spatial";
import { parseVoiceCommand } from "../src/lib/voiceCommands";

const P = (pts: [number, number][], dx = 0, dy = 0): Pt[] => pts.map(([x, y]) => ({ x: x + dx, y: y + dy, z: 0 }));
const PALM: [number, number][] = [
  [0.5, 0.7], [0.44, 0.65], [0.4, 0.58], [0.36, 0.52], [0.33, 0.46],
  [0.44, 0.52], [0.43, 0.42], [0.42, 0.35], [0.42, 0.28],
  [0.5, 0.5], [0.5, 0.39], [0.5, 0.31], [0.5, 0.24],
  [0.56, 0.52], [0.57, 0.41], [0.58, 0.34], [0.59, 0.27],
  [0.62, 0.56], [0.64, 0.47], [0.66, 0.41], [0.67, 0.35],
];
const FIST: [number, number][] = [
  [0.5, 0.7], [0.45, 0.65], [0.42, 0.59], [0.45, 0.55], [0.49, 0.55],
  [0.44, 0.52], [0.44, 0.48], [0.45, 0.54], [0.46, 0.57],
  [0.5, 0.5], [0.5, 0.46], [0.5, 0.53], [0.5, 0.56],
  [0.56, 0.52], [0.56, 0.48], [0.55, 0.54], [0.55, 0.57],
  [0.61, 0.56], [0.61, 0.52], [0.6, 0.57], [0.59, 0.59],
];
const openPalm = (dx = 0, dy = 0) => P(PALM, dx, dy);
const closedFist = (dx = 0, dy = 0) => P(FIST, dx, dy);
const pointing = () => closedFist().map((p, i) => (i === 6 ? { x: 0.43, y: 0.4, z: 0 } : i === 7 ? { x: 0.425, y: 0.32, z: 0 } : i === 8 ? { x: 0.42, y: 0.24, z: 0 } : p));
const pinched = (dx = 0, dy = 0, scale = 1, z = 0) =>
  openPalm().map((pt, i) => {
    const [rx, ry] = i === 3 ? [0.41, 0.45] : i === 4 ? [0.43, 0.39] : i === 7 ? [0.43, 0.4] : i === 8 ? [0.435, 0.392] : [pt.x, pt.y];
    return { x: 0.5 + (rx - 0.5) * scale + dx, y: 0.6 + (ry - 0.6) * scale + dy, z };
  });
const obs = (l: Pt[], pose: HandPose = "NONE", score = 0.9): HandObservation => ({ landmarks: l, pose, poseScore: pose === "NONE" ? 0 : score });

function hold(c: HandGestureClassifier, hands: HandObservation[], from: number, to: number, mode: DashboardMode) {
  let last = c.process(hands, from, mode, "ISOLATION");
  for (let t = from + 33; t <= to; t += 33) last = c.process(hands, t, mode, "ISOLATION");
  if ((to - from) % 33 !== 0) last = c.process(hands, to, mode, "ISOLATION");
  return last;
}

function engine() {
  let t = 1000;
  const e = new HudEngine(() => t, () => Date.UTC(2026, 10, 15));
  return { e, setT: (v: number) => { t = v; } };
}

describe("gestures (HandGestureClassifierTest)", () => {
  it("palm hold opens, fist hold closes, pose must be released", () => {
    const c = new HandGestureClassifier();
    const palm = [obs(openPalm(), "OPEN_PALM")];
    expect(c.process(palm, 1000, "CLOSED", "ISOLATION").pendingAction).toBe("OPEN_DASHBOARD");
    expect(c.process(palm, 1300, "CLOSED", "ISOLATION").holdProgress).toBeCloseTo(0.5, 1);
    expect(c.process(palm, 1600, "CLOSED", "ISOLATION").action).toBe("OPEN_DASHBOARD");
    expect(c.process(palm, 2400, "CLOSED", "ISOLATION").action).toBeNull();
    const fist = [obs(closedFist(), "CLOSED_FIST")];
    hold(c, fist, 4000, 4800, "PERMANENT");
    expect(c.process(fist, 4920, "PERMANENT", "ISOLATION").action).toBe("CLOSE_DASHBOARD");
  });

  it("pointing is never a fist", () => {
    expect(isClosedFist(pointing())).toBe(false);
    expect(resolvePose(obs(pointing()))).toBe("POINTING_UP");
  });

  it("heuristic fallback recognises palm and fist without ML category", () => {
    expect(resolvePose(obs(openPalm()))).toBe("OPEN_PALM");
    expect(resolvePose(obs(closedFist()))).toBe("CLOSED_FIST");
    expect(resolvePose({ landmarks: openPalm(), pose: "THUMB_UP", poseScore: 0.2 })).toBe("OPEN_PALM");
  });

  it("named poses map to actions", () => {
    const fire = (pose: HandPose, mode: DashboardMode, ms: number) => {
      const c = new HandGestureClassifier();
      const h = [obs(closedFist(), pose)];
      hold(c, h, 0, ms - 40, mode);
      return c.process(h, ms, mode, "ISOLATION").action;
    };
    expect(fire("THUMB_UP", "CLOSED", 420)).toBe("ACKNOWLEDGE");
    expect(fire("POINTING_UP", "PERMANENT", 720)).toBe("TOGGLE_CENTER_LOCK");
    expect(fire("VICTORY", "PERMANENT", 620)).toBe("TOGGLE_EYE_PREVIEW");
    expect(fire("THUMB_DOWN", "PERMANENT", 920)).toBe("CLEAR_DRILL");
    expect(fire("POINTING_UP", "CLOSED", 2000)).toBeNull();
  });

  it("swipe moves around the ring and wraps", () => {
    const { e } = engine();
    e.onVoiceCommand({ kind: "OPEN_PERMANENT" });
    expect(e.getState().currentPage).toBe("ISOLATION");
    let t = 1000;
    const swipe = (a: number, b: number) => {
      e.onHands([obs(openPalm(a), "OPEN_PALM")], t); t += 120;
      e.onHands([obs(openPalm(b), "OPEN_PALM")], t); t += 800;
    };
    swipe(-0.08, 0.08); expect(e.getState().currentPage).toBe("DISTANCE");
    swipe(-0.08, 0.08); swipe(-0.08, 0.08); expect(e.getState().currentPage).toBe("ENVIRONMENT");
    swipe(-0.08, 0.08); expect(e.getState().currentPage).toBe("RADIATION");
    swipe(0.08, -0.08); expect(e.getState().currentPage).toBe("ENVIRONMENT");
    expect(e.getState().swipeCueId).toBe(5);
  });

  it("map pinch zooms, tilts and pans only on the map page", () => {
    const { e } = engine();
    e.onVoiceCommand({ kind: "OPEN_PERMANENT" });
    e.onVoiceCommand({ kind: "NAVIGATE", page: "ENVIRONMENT" });
    e.onHands([obs(pinched())], 1000);
    expect(e.getState().isMapPinching).toBe(true);
    e.onHands([obs(pinched(0.1, -0.05, 1.24, -0.06))], 1120);
    expect(e.getState().mapZoom).toBeGreaterThan(1.5);
    expect(e.getState().mapTiltDegrees).toBeGreaterThan(8);
    expect(e.getState().mapPanEastDeg).toBeGreaterThan(0.1);
  });
});

describe("spatial layout", () => {
  it("focus follows head turn, looking down shows drills, center lock drags window", () => {
    expect(focusedPage(100, 0, 100, 0)).toBe("RADIATION");
    expect(focusedPage(141, 0, 100, 0)).toBe("ISOLATION");
    expect(focusedPage(262, 0, 100, 0)).toBe("ENVIRONMENT");
    expect(focusedPage(150, -30, 100, 0)).toBe("DRILLS");
    expect(angleDelta(350, 10)).toBeCloseTo(-20);
    const side = project(90, 0, 50, 0);
    expect(side.xDeg).toBeLessThan(40);
    expect(side.rotationY).toBeLessThan(0);

    const { e } = engine();
    e.onHeading(200);
    e.onVoiceCommand({ kind: "OPEN_PERMANENT" });
    e.onHeading(281);
    expect(e.getState().currentPage).toBe("GRAVITY");
    e.onPitch(-30);
    expect(e.getState().currentPage).toBe("DRILLS");
    e.onPitch(0);
    for (let t = 0; t <= 720; t += 40) e.onHands([obs(closedFist(), "POINTING_UP")], t);
    expect(e.getState().centerLocked).toBe(true);
    expect(e.getState().anchorYawDegrees).toBeCloseTo(281 - 3 * TILE_SPACING_DEG);
    e.onHeading(330);
    expect(e.getState().currentPage).toBe("GRAVITY");
  });
});

describe("voice", () => {
  it("parses the vocabulary", () => {
    expect(parseVoiceCommand("emergency seven")).toEqual({ kind: "RUN_EMERGENCY", number: 7 });
    expect(parseVoiceCommand("end all emergencies")).toEqual({ kind: "END_ALL_EMERGENCIES" });
    expect(parseVoiceCommand("open dashboard")).toEqual({ kind: "OPEN_PERMANENT" });
    expect(parseVoiceCommand("dashboard")).toEqual({ kind: "TOGGLE_QUICK" });
    expect(parseVoiceCommand("show me the map")).toEqual({ kind: "NAVIGATE", page: "ENVIRONMENT" });
    expect(parseVoiceCommand("center map")).toEqual({ kind: "MAP_RECENTER" });
    expect(parseVoiceCommand("center lock")).toEqual({ kind: "CENTER_LOCK" });
    expect(parseVoiceCommand("clear drill")).toEqual({ kind: "RUN_EMERGENCY", number: 0 });
    expect(parseVoiceCommand("banana")).toBeNull();
  });
});

describe("models", () => {
  it("cognitive load levels and peak pull", () => {
    expect(levelFor(69)).toBe("NOMINAL");
    expect(levelFor(70)).toBe("CAUTION");
    expect(levelFor(95)).toBe("EMERGENCY");
    const m = new CognitiveLoadModel();
    const calm = m.evaluate(0, { eye: null, heartRateBpm: 86, expectedHrBpm: 86, stressPercent: 12, helmetCo2MmHg: 1.8, coreTempC: 37.1, sleepQualityPercent: 88 });
    expect(calm.level).toBe("NOMINAL");
    const co2 = m.evaluate(1000, { eye: null, heartRateBpm: 86, expectedHrBpm: 86, stressPercent: 12, helmetCo2MmHg: 8.4, coreTempC: 37.1, sleepQualityPercent: 88 });
    expect(co2.topDriver).toBe("HELMET CO2");
    expect(co2.index).toBeGreaterThan(calm.index);
  });

  it("trend forecaster projects a rising series", () => {
    const f = new TrendForecaster(600000, 10);
    for (let i = 0; i < 20; i++) f.add(i * 6000, 40 + i);
    const fc = f.forecast(10, 70, true)!;
    expect(fc.slopePerMin).toBeCloseTo(10, 3);
    expect(fc.minutesToThreshold).toBeGreaterThan(0);
  });

  it("drill 1 raises heart rate and reaches the crew", () => {
    const { e } = engine();
    e.onVoiceCommand({ kind: "RUN_EMERGENCY", number: 1 });
    expect(e.getState().activeDrill.code).toBe("E-01");
    expect(e.getState().heartRateBpm).toBe(86 + 38);
    expect(e.crewOutbound().emergency).toBe(true);
    e.onVoiceCommand({ kind: "END_ALL_EMERGENCIES" });
    expect(e.getState().activeDrill.number).toBe(0);
    expect(e.crewOutbound().emergency).toBe(false);
  });

  it("Mars clock and light time are sane", () => {
    const t = Date.UTC(2026, 10, 15);
    const m = mars24(t);
    expect(m.marsSolDate).toBeGreaterThan(53000);
    expect(m.localTrueSolarTimeHours).toBeGreaterThanOrEqual(0);
    const delay = oneWayLightTimeSec(t) / 60;
    expect(delay).toBeGreaterThan(3);
    expect(delay).toBeLessThan(23);
  });

  it("space weather heuristic", () => {
    expect(flareRank("X1.0")).toBe(10);
    expect(flareRank("M2.5")).toBe(2.5);
    const now = Date.UTC(2026, 10, 15);
    expect(evaluateSpaceWeather([{ type: "SEP", timeMs: now - 3600000, classType: null }], now, now).level).toBe("SEP");
  });
});

describe("crew (CrewLinkTest)", () => {
  const status = (id: string, lat: number | null, lon: number | null, emergency = false, t = 1000): CrewStatus => ({
    id, name: `Crew-${id}`, lat, lon, headingDeg: 90, heartRateBpm: 120, cognitiveLoad: 40, emergency, reason: emergency ? "Pressure Leak" : "", sentAtMs: t,
  });

  it("geo distance and bearing", () => {
    const [e1, n1] = Geo.offsetMeters(18.38, 77.58, 18.381, 77.58);
    expect(Geo.distanceMeters(e1, n1)).toBeCloseTo(111.2, 0);
    expect(Geo.bearingDeg(e1, n1)).toBeCloseTo(0, 1);
    const [lat, lon] = Geo.displace(18.38, 77.58, 30, 40);
    const [e2, n2] = Geo.offsetMeters(18.38, 77.58, lat, lon);
    expect(e2).toBeCloseTo(30, 1);
    expect(n2).toBeCloseTo(40, 1);
  });

  it("roster ignores self, sorts emergency first, drops stale peers", () => {
    const r = new CrewRoster("ME", 20000);
    r.onPacket(status("ME", 1, 1), 0);
    r.onPacket(status("A", 18.381, 77.58), 0);
    r.onPacket(status("B", 18.385, 77.58, true), 5000);
    const m = r.members(10000, 18.38, 77.58);
    expect(m.map((x) => x.id)).toEqual(["B", "A"]);
    expect(m[0].distanceMeters!).toBeCloseTo(556, -1);
    expect(r.members(21000, 18.38, 77.58).map((x) => x.id)).toEqual(["B"]);
    expect(r.members(21000, null, null)[0].sentPosition).toBe(true);
  });

  it("status text says which device lacks GPS", () => {
    const { e } = engine();
    e.setCrewIdentity("ME", "Crew-ME");
    e.setLocationPermission(true);
    e.setCrewLink({ active: true });
    e.onCrewPacket(status("B", null, null, false, Date.UTC(2026, 10, 15)));
    expect(crewSummary(e.getState())).toBe("Crew 1 linked · no GPS on either device");
    e.onAbsoluteFix(18.38, 77.58, 120);
    expect(crewSummary(e.getState())).toBe("Crew 1 linked · no GPS from Crew-B");
    e.onCrewPacket(status("B", 18.381, 77.58, false, Date.UTC(2026, 10, 15)));
    expect(crewSummary(e.getState())).toBe("Crew 1 · nearest 111 m");
  });
});
