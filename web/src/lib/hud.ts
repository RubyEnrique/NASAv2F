// Port of hud/HudViewModel.kt: one state object, advanced once per second and by sensor events.
import { CrewRoster, type CrewMember, type CrewStatus } from "./crew";
import { BlinkAnalyzer, emptyEyeMetrics, type EyeMetrics, type EyeSample, type EyeTrackingStatus } from "./eye";
import { HandGestureClassifier, type DashboardMode, type GestureAction, type HandObservation, type HandPose } from "./gestures";
import {
  CAUTION_AT, CognitiveLoadModel, LEVEL_ORDER, MentalVitals, NO_DRILL, TrendForecaster, drillByNumber,
  type CognitiveState, type Drill, type MentalState,
} from "./mental";
import { EMPTY_SPACE_WEATHER, evaluateSpaceWeather, isNearSolarConjunction, mars24, oneWayLightTimeSec, earthMarsDistanceAu, type MarsTime, type SpaceWeatherEvent, type SpaceWeatherState } from "./nasa";
import { anchorYawFor, angleDelta, DRILLS_PITCH_OFFSET_DEG, focusedPage, HOME, ringNext, ringPrevious, type DashboardPage } from "./spatial";
import { Axemu, gravityLoad, jezeroWeather, noiseLabel, RadiationModel, SuitTelemetryModel, type BodyState, type MarsOutside, type RadiationState, type SuitTelemetry } from "./suit";
import type { VoiceCommand } from "./voiceCommands";

const clamp = (v: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, v));

export const MAP = { MIN_ZOOM: 1, MAX_ZOOM: 5, MAX_PAN_EAST_DEG: 8.5, MAX_PAN_NORTH_DEG: 7, MAX_TILT: 42 } as const;
export const tiltForZoom = (z: number) => clamp(((z - MAP.MIN_ZOOM) / Math.max(1, MAP.MAX_ZOOM - MAP.MIN_ZOOM)) * MAP.MAX_TILT, 0, MAP.MAX_TILT);

const BASE_HR = 86;
const INITIAL_EVA_SECONDS = 3 * 60 + 42;
const ALERT_CYCLE_MS = 20000;
const ACK_SNOOZE_MS = 60000;
const CREW_ALERT_REPEAT_MS = 60000;
const WIND_CYCLE = [6.4, 6.5, 6.4, 6.3, 6.4, 6.6, 6.5, 6.4];

export interface GestureFeedback { activePose: HandPose; pendingAction: GestureAction | null; holdProgress: number; lastAction: GestureAction | null; lastActionId: number }
export interface MicStatus { available: boolean; listening: boolean; speaking: boolean; levelDb: number; lastHeard: string; sensitivity: number; engine: "vosk" | "webspeech" | "none"; loadingPct: number | null; error: string | null }
export interface CrewLinkStats { active: boolean; mode: string; room: string; sent: number; heardOthers: number; error: string | null }

export interface HudState {
  hasCameraPermission: boolean;
  hasLocationPermission: boolean;
  hasGpsFix: boolean;
  gpsAccuracyMeters: number | null;
  headingDegrees: number;
  pitchDegrees: number;
  hasHeadTracking: boolean;
  batteryPercent: number | null;
  distanceMeters: number;
  speedMps: number;
  distanceGoalMeters: number;
  heartRateBpm: number;
  systolicBp: number;
  diastolicBp: number;
  spo2Percent: number;
  coreTempCelsius: number;
  hydrationPercent: number;
  isBiometricSensorLost: boolean;
  suit: SuitTelemetry;
  radiation: RadiationState;
  windSpeedMps: number;
  evaSeconds: number;
  track: { east: number; north: number }[];
  mapZoom: number;
  mapTiltDegrees: number;
  mapPanEastDeg: number;
  mapPanNorthDeg: number;
  isMapPinching: boolean;
  mental: MentalState;
  cognitive: CognitiveState;
  eye: EyeMetrics;
  eyeTrackingStatus: EyeTrackingStatus;
  eyePreviewEnabled: boolean;
  alertAcknowledged: boolean;
  marsTime: MarsTime;
  earthLink: { oneWayDelaySec: number; distanceAu: number; solarConjunction: boolean };
  spaceWeather: SpaceWeatherState;
  cognitiveForecast: { projectedIn10Min: number | null; slopePerMin: number; minutesToCaution: number | null; history: number[] };
  consumables: { oxygenMinutesLeft: number; batteryMinutesLeft: number; ratedEvaMinutesLeft: number; turnBackInMinutes: number; limitingFactor: string; usesObservedTrend: boolean };
  body: BodyState;
  habitability: { suitTempC: number; suitPressurePsi: number; helmetCo2MmHg: number; lightLux: number | null; helmetLightsAdvised: boolean; noiseDbfs: number | null; noiseLabel: string; outside: MarsOutside; coldSoakMinutes: number };
  motorSteadiness: number | null;
  activeDrill: Drill;
  mic: MicStatus;
  gesture: GestureFeedback;
  dashboardMode: DashboardMode;
  currentPage: DashboardPage;
  anchorYawDegrees: number;
  anchorPitchDegrees: number;
  centerLocked: boolean;
  crew: CrewMember[];
  crewLink: CrewLinkStats;
  crewSelfName: string;
  crewFixAccuracyMeters: number | null;
  lastSwipeDirection: "NEXT" | "PREVIOUS" | null;
  swipeCueId: number;
}

export type HudEvent = { kind: "speak"; text: string } | { kind: "stopSpeaking" } | { kind: "recalibrateMic" } | { kind: "sensitivity"; delta: number };

export const isOpen = (s: HudState) => s.dashboardMode !== "CLOSED";

export class HudEngine {
  private state: HudState;
  private listeners = new Set<() => void>();
  private eventListeners = new Set<(e: HudEvent) => void>();

  private classifier = new HandGestureClassifier();
  private mentalVitals = new MentalVitals();
  private cognitiveModel = new CognitiveLoadModel();
  private suitModel = new SuitTelemetryModel();
  private radiationModel = new RadiationModel();
  private blink = new BlinkAnalyzer();

  private lastLocationSampleMs = 0;
  private totalDistance = 0;
  private smoothedSpeed = 0;
  private averagedHr = BASE_HR;
  private baseWind = 6.4;
  private baseSpo2 = 98;
  private baseEvaSeconds = INITIAL_EVA_SECONDS;
  private lastEyePublishMs = 0;
  private tickCount = 0;

  private lastAlertLevel = "NOMINAL" as CognitiveState["level"];
  private lastEmergencySpokenMs: number | null = null;
  private acknowledgedUntilMs = 0;
  private lastRadiationStatus = "NOMINAL";

  private cognitiveTrend = new TrendForecaster(5 * 60000, 10);
  private oxygenTrend = new TrendForecaster(10 * 60000, 30);
  private batteryTrend = new TrendForecaster(10 * 60000, 30);
  private cognitiveHistory: number[] = [];
  private spaceWeatherEvents: SpaceWeatherEvent[] = [];
  private spaceWeatherFetchedAtMs = 0;
  private steps = 0;
  private exerciseSeconds = 0;
  private lightLux: number | null = null;
  private coldSoakMinutes = 0;
  private handTrack: [number, number][] = [];
  private motorSmoothed: number | null = null;

  private crewSelfId = "self";
  private roster = new CrewRoster("self");
  private selfLat: number | null = null;
  private selfLon: number | null = null;
  private crewAnnounced = new Map<string, number>();

  constructor(private now: () => number = () => (typeof performance !== "undefined" ? performance.now() : Date.now()), private wall: () => number = () => Date.now()) {
    const d = NO_DRILL;
    const t0 = this.now();
    const suit = this.suitModel.step(0, BASE_HR, d, INITIAL_EVA_SECONDS);
    const radiation = this.radiationModel.step(0, 0, 0, 0, 1, INITIAL_EVA_SECONDS);
    const mental = this.mentalVitals.evaluate(t0, BASE_HR, 0.09, suit.helmetCo2MmHg, d, INITIAL_EVA_SECONDS);
    const mars = mars24(this.wall());
    this.state = {
      hasCameraPermission: false, hasLocationPermission: false, hasGpsFix: false, gpsAccuracyMeters: null,
      headingDegrees: 0, pitchDegrees: 0, hasHeadTracking: false, batteryPercent: null,
      distanceMeters: 0, speedMps: 0, distanceGoalMeters: 5000,
      heartRateBpm: BASE_HR, systolicBp: 118, diastolicBp: 76, spo2Percent: 98, coreTempCelsius: 37.1, hydrationPercent: 74, isBiometricSensorLost: false,
      suit, radiation, windSpeedMps: 6.4, evaSeconds: INITIAL_EVA_SECONDS,
      track: [{ east: 0, north: 0 }], mapZoom: 1, mapTiltDegrees: 0, mapPanEastDeg: 0, mapPanNorthDeg: 0, isMapPinching: false,
      mental,
      cognitive: this.cognitiveModel.evaluate(t0, { eye: null, heartRateBpm: BASE_HR, expectedHrBpm: mental.expectedHr, stressPercent: mental.stressPercent, helmetCo2MmHg: suit.helmetCo2MmHg, coreTempC: 37.1, sleepQualityPercent: mental.sleep.qualityPercent }),
      eye: emptyEyeMetrics(), eyeTrackingStatus: "OFF", eyePreviewEnabled: true, alertAcknowledged: false,
      marsTime: mars,
      earthLink: { oneWayDelaySec: oneWayLightTimeSec(this.wall()), distanceAu: earthMarsDistanceAu(this.wall()), solarConjunction: isNearSolarConjunction(this.wall()) },
      spaceWeather: EMPTY_SPACE_WEATHER,
      cognitiveForecast: { projectedIn10Min: null, slopePerMin: 0, minutesToCaution: null, history: [] },
      consumables: { oxygenMinutesLeft: suit.oxyTimeLeftSec / 60, batteryMinutesLeft: suit.batteryTimeLeftSec / 60, ratedEvaMinutesLeft: 8 * 60, turnBackInMinutes: 0, limitingFactor: "O₂", usesObservedTrend: false },
      body: gravityLoad(0, 0),
      habitability: { suitTempC: suit.suitTempC, suitPressurePsi: suit.suitPressureTotalPsi, helmetCo2MmHg: suit.helmetCo2MmHg, lightLux: null, helmetLightsAdvised: false, noiseDbfs: null, noiseLabel: "—", outside: jezeroWeather(mars.localTrueSolarTimeHours), coldSoakMinutes: 0 },
      motorSteadiness: null,
      activeDrill: d,
      mic: { available: false, listening: false, speaking: false, levelDb: -90, lastHeard: "", sensitivity: 3, engine: "none", loadingPct: null, error: null },
      gesture: { activePose: "NONE", pendingAction: null, holdProgress: 0, lastAction: null, lastActionId: 0 },
      dashboardMode: "CLOSED", currentPage: HOME, anchorYawDegrees: 0, anchorPitchDegrees: 0, centerLocked: false,
      crew: [], crewLink: { active: false, mode: "off", room: "", sent: 0, heardOthers: 0, error: null }, crewSelfName: "", crewFixAccuracyMeters: null,
      lastSwipeDirection: null, swipeCueId: 0,
    };
    this.state = this.applyPredictions(this.state, t0, 0);
  }

  // ------------------------------------------------------------------ store plumbing

  getState = () => this.state;
  subscribe = (l: () => void) => { this.listeners.add(l); return () => { this.listeners.delete(l); }; };
  onEvent(l: (e: HudEvent) => void) { this.eventListeners.add(l); return () => { this.eventListeners.delete(l); }; }
  private emit(e: HudEvent) { for (const l of this.eventListeners) l(e); }
  private set(next: HudState) {
    if (next === this.state) return;
    this.state = next;
    for (const l of this.listeners) l();
  }
  private update(f: (s: HudState) => HudState) { this.set(f(this.state)); }

  // ------------------------------------------------------------------ simulation tick

  /** One simulated second: EVA clock, consumables, dose, load and alerts. */
  tick() {
    const nowMs = this.now();
    this.tickCount++;
    if (this.tickCount % 3 === 0) this.refreshLocationDerived(nowMs);
    this.baseEvaSeconds++;
    if (this.tickCount % 3 === 0) this.baseWind = WIND_CYCLE[Math.trunc(this.tickCount / 3) % WIND_CYCLE.length];
    this.baseSpo2 = this.tickCount % 12 === 6 ? 99 : 98;
    this.update((s) => this.applyPredictions(this.applyTelemetry(s, nowMs, 1), nowMs, 1));
    this.evaluateAlerts(nowMs);
    this.refreshCrew(this.wall());
  }

  private applyTelemetry(s: HudState, nowMs: number, dtSec: number): HudState {
    const d = s.activeDrill;
    const lost = d.sensorDropout;
    const hr = clamp(Math.round(this.averagedHr + d.hrOffsetBpm), 50, 195);
    const ex = Math.max(0, hr - BASE_HR);
    const sys = clamp(Math.round(118 + ex * 0.45), 110, 162);
    const dia = clamp(Math.round(76 + ex * 0.18), 70, 96);
    const spo2 = lost ? 0 : [1, 4, 10].includes(d.number) ? 91 : d.number === 2 ? 88 : this.baseSpo2;
    const temp = clamp(37.1 + d.coreTempOffsetC, 35.5, 40.5);
    const pos = s.track[s.track.length - 1] ?? { east: 0, north: 0 };
    const suit = this.suitModel.step(dtSec, hr, d, this.baseEvaSeconds);
    const radiation = this.radiationModel.step(dtSec, pos.east, pos.north, this.smoothedSpeed, d.doseRateMultiplier, this.baseEvaSeconds);
    const workload = clamp(0.09 + (Math.max(0, this.smoothedSpeed) / 10) * 0.08 + (d.number === 7 || d.number === 10 ? 0.03 : 0), 0.05, 0.3);
    const eye = this.blink.metrics();
    const mental = this.mentalVitals.evaluate(nowMs, lost ? null : hr, workload, suit.helmetCo2MmHg, d, this.baseEvaSeconds);
    const cognitive = this.cognitiveModel.evaluate(nowMs, {
      eye: s.eyeTrackingStatus === "ACTIVE" ? eye : null,
      heartRateBpm: mental.heartRate,
      expectedHrBpm: mental.expectedHr,
      stressPercent: mental.stressLevel === "UNKNOWN" ? null : mental.stressPercent,
      helmetCo2MmHg: suit.helmetCo2MmHg,
      coreTempC: temp,
      sleepQualityPercent: mental.sleep.qualityPercent,
    });
    return {
      ...s,
      heartRateBpm: hr, systolicBp: sys, diastolicBp: dia, spo2Percent: spo2, coreTempCelsius: temp,
      hydrationPercent: d.hydrationPercentOverride ?? 74, isBiometricSensorLost: lost,
      suit, radiation, windSpeedMps: d.windSpeedOverrideMps ?? this.baseWind, evaSeconds: this.baseEvaSeconds,
      mental, cognitive, eye, alertAcknowledged: nowMs < this.acknowledgedUntilMs,
    };
  }

  private applyPredictions(s: HudState, nowMs: number, dtSec: number): HudState {
    const wallMs = this.wall();
    const mars = mars24(wallMs);
    const outside = jezeroWeather(mars.localTrueSolarTimeHours);
    this.cognitiveTrend.add(nowMs, s.cognitive.index);
    if (dtSec > 0 && (this.tickCount % 5 === 0 || this.cognitiveHistory.length === 0)) {
      this.cognitiveHistory.push(s.cognitive.index);
      while (this.cognitiveHistory.length > 120) this.cognitiveHistory.shift();
    }
    const cf = this.cognitiveTrend.forecast(10, CAUTION_AT, true);
    this.oxygenTrend.add(nowMs, s.suit.oxyPriStoragePct);
    this.batteryTrend.add(nowMs, s.suit.primaryBatteryPct + s.suit.secondaryBatteryPct);
    const o2Obs = this.oxygenTrend.forecast(0, 5, false)?.minutesToThreshold ?? null;
    const batObs = this.batteryTrend.forecast(0, 0, false)?.minutesToThreshold ?? null;
    const o2Min = o2Obs ?? s.suit.oxyTimeLeftSec / 60;
    const batMin = batObs ?? s.suit.batteryTimeLeftSec / 60;
    const ratedMin = Math.max(0, Axemu.RATED_EVA_HOURS * 3600 - s.evaSeconds) / 60;
    const limiting = ([["O₂", o2Min], ["Battery", batMin], ["AxEMU 8 h rating", ratedMin]] as const).reduce((a, b) => (b[1] < a[1] ? b : a));
    if (dtSec > 0 && !s.isBiometricSensorLost && s.heartRateBpm >= 105) this.exerciseSeconds += dtSec;
    if (dtSec > 0 && outside.airTempC < Axemu.COLD_SOAK_BELOW_C) this.coldSoakMinutes += dtSec / 60;
    const noise = s.mic.listening && s.mic.levelDb > -89 ? s.mic.levelDb : null;
    return {
      ...s,
      marsTime: mars,
      earthLink: { oneWayDelaySec: oneWayLightTimeSec(wallMs), distanceAu: earthMarsDistanceAu(wallMs), solarConjunction: isNearSolarConjunction(wallMs) },
      spaceWeather: evaluateSpaceWeather(this.spaceWeatherEvents, wallMs, this.spaceWeatherFetchedAtMs),
      cognitiveForecast: {
        projectedIn10Min: cf ? clamp(Math.round(cf.projected), 0, 100) : null,
        slopePerMin: cf?.slopePerMin ?? 0,
        minutesToCaution: cf?.minutesToThreshold ?? null,
        history: [...this.cognitiveHistory],
      },
      consumables: {
        oxygenMinutesLeft: o2Min,
        batteryMinutesLeft: batMin,
        ratedEvaMinutesLeft: ratedMin,
        turnBackInMinutes: limiting[1] - s.radiation.etaToBaseSec / 60 - Axemu.RETURN_RESERVE_MIN,
        limitingFactor: limiting[0],
        usesObservedTrend: o2Obs != null || batObs != null,
      },
      body: gravityLoad(this.steps, this.exerciseSeconds),
      habitability: {
        suitTempC: s.suit.suitTempC,
        suitPressurePsi: s.suit.suitPressureTotalPsi,
        helmetCo2MmHg: s.suit.helmetCo2MmHg,
        lightLux: this.lightLux,
        helmetLightsAdvised: (this.lightLux ?? 1000) < Axemu.HELMET_LIGHT_BELOW_LUX || !outside.isDaylight,
        noiseDbfs: noise,
        noiseLabel: noiseLabel(noise),
        outside,
        coldSoakMinutes: this.coldSoakMinutes,
      },
    };
  }

  // ------------------------------------------------------------------ alerts

  private evaluateAlerts(nowMs: number) {
    const s = this.state;
    const level = s.cognitive.level;
    const acked = nowMs < this.acknowledgedUntilMs;
    const pulse = s.isBiometricSensorLost ? "Pulse signal lost" : `Pulse ${s.heartRateBpm} beats per minute`;
    if (level === "EMERGENCY") {
      const last = this.lastEmergencySpokenMs;
      if (!acked && (last == null || nowMs - last >= ALERT_CYCLE_MS)) {
        this.lastEmergencySpokenMs = nowMs;
        this.emit({ kind: "speak", text: `Emergency. Cognitive load ${s.cognitive.index}. ${pulse}. ${s.cognitive.topDriver.toLowerCase()}. Thumbs up to acknowledge.` });
      }
    } else if (level === "WARNING") {
      this.lastEmergencySpokenMs = null;
      if (!acked && LEVEL_ORDER.indexOf(this.lastAlertLevel) < LEVEL_ORDER.indexOf("WARNING")) {
        this.emit({ kind: "speak", text: `Warning. Cognitive load ${s.cognitive.index}. ${pulse}. Pause and slow your breathing.` });
      }
    } else this.lastEmergencySpokenMs = null;
    this.lastAlertLevel = level;
    const r = s.radiation;
    if (r.status === "STORM" && this.lastRadiationStatus !== "STORM") {
      this.emit({
        kind: "speak",
        text: r.shelter === "SHELTERED" ? "Solar particle event. Stay inside the base." : `Solar particle event. Return to base, bearing ${Math.round(r.bearingToBaseDeg)}, ${Math.round(r.distanceToBaseMeters)} meters.`,
      });
    }
    this.lastRadiationStatus = r.status;
  }

  acknowledgeAlerts() {
    this.acknowledgedUntilMs = this.now() + ACK_SNOOZE_MS;
    this.emit({ kind: "stopSpeaking" });
    this.update((s) => ({ ...s, alertAcknowledged: true }));
  }

  // ------------------------------------------------------------------ dashboard navigation

  openDashboard(mode: DashboardMode) {
    this.update((s) => ({
      ...s,
      dashboardMode: mode,
      anchorYawDegrees: anchorYawFor(s.currentPage, s.headingDegrees),
      anchorPitchDegrees: s.currentPage === "DRILLS" ? s.pitchDegrees - DRILLS_PITCH_OFFSET_DEG : s.pitchDegrees,
      isMapPinching: false,
    }));
  }

  closeDashboard() {
    this.update((s) => ({ ...s, dashboardMode: "CLOSED", isMapPinching: false }));
  }

  selectPage(page: DashboardPage) {
    this.update((s) => ({
      ...s,
      currentPage: page,
      anchorYawDegrees: page === "DRILLS" ? s.anchorYawDegrees : anchorYawFor(page, s.headingDegrees),
      anchorPitchDegrees: page === "DRILLS" ? s.pitchDegrees - DRILLS_PITCH_OFFSET_DEG : s.pitchDegrees,
    }));
  }

  stepPage(dir: "NEXT" | "PREVIOUS") {
    const cur = this.state.currentPage;
    this.selectPage(dir === "NEXT" ? ringNext(cur) : ringPrevious(cur));
    this.update((s) => ({ ...s, lastSwipeDirection: dir, swipeCueId: s.swipeCueId + 1 }));
  }

  toggleCenterLock() {
    this.update((s) => ({ ...s, centerLocked: !s.centerLocked }));
    this.selectPage(this.state.currentPage);
  }

  recenterView() { this.selectPage(this.state.currentPage); }

  toggleEyePreview() { this.update((s) => ({ ...s, eyePreviewEnabled: !s.eyePreviewEnabled })); }

  setDrill(d: Drill) {
    const t = this.now();
    this.update((s) => this.applyTelemetry({ ...s, activeDrill: d }, t, 0));
    this.evaluateAlerts(t);
  }

  endAllEmergencies() {
    this.setDrill(NO_DRILL);
    this.acknowledgeAlerts();
  }

  adjustMapZoom(delta: number) {
    this.update((s) => {
      const z = clamp(s.mapZoom + delta, MAP.MIN_ZOOM, MAP.MAX_ZOOM);
      return { ...s, mapZoom: z, mapTiltDegrees: tiltForZoom(z) };
    });
  }

  panMap(dEastDeg: number, dNorthDeg: number) {
    this.update((s) => ({
      ...s,
      mapPanEastDeg: clamp(s.mapPanEastDeg + dEastDeg, -MAP.MAX_PAN_EAST_DEG, MAP.MAX_PAN_EAST_DEG),
      mapPanNorthDeg: clamp(s.mapPanNorthDeg + dNorthDeg, -MAP.MAX_PAN_NORTH_DEG, MAP.MAX_PAN_NORTH_DEG),
    }));
  }

  recenterMap() { this.update((s) => ({ ...s, mapZoom: MAP.MIN_ZOOM, mapTiltDegrees: 0, mapPanEastDeg: 0, mapPanNorthDeg: 0 })); }

  // ------------------------------------------------------------------ voice

  onVoiceCommand(c: VoiceCommand) {
    const s = this.state;
    const open = isOpen(s);
    switch (c.kind) {
      case "TOGGLE_QUICK":
        this.classifier.reset();
        if (s.dashboardMode === "CLOSED") this.openDashboard("QUICK_VOICE");
        else if (s.dashboardMode === "QUICK_VOICE") this.closeDashboard();
        return;
      case "OPEN_PERMANENT": this.classifier.reset(); this.openDashboard("PERMANENT"); return;
      case "CLOSE_PERMANENT": this.classifier.reset(); this.closeDashboard(); return;
      case "NAVIGATE": if (open) this.selectPage(c.page); return;
      case "RUN_EMERGENCY": { const d = drillByNumber(c.number); if (d) this.setDrill(d); return; }
      case "MAP_ZOOM_IN": if (open && s.currentPage === "ENVIRONMENT") this.adjustMapZoom(0.85); return;
      case "MAP_ZOOM_OUT": if (open && s.currentPage === "ENVIRONMENT") this.adjustMapZoom(-0.85); return;
      case "MAP_RECENTER": if (open && s.currentPage === "ENVIRONMENT") this.recenterMap(); return;
      case "RECENTER_VIEW": if (open) this.recenterView(); return;
      case "CENTER_LOCK": this.toggleCenterLock(); return;
      case "END_ALL_EMERGENCIES": this.endAllEmergencies(); return;
      case "ACKNOWLEDGE": this.acknowledgeAlerts(); return;
      case "TOGGLE_EYE_PREVIEW": this.toggleEyePreview(); return;
      case "CALIBRATE_MIC": this.emit({ kind: "recalibrateMic" }); return;
      case "SENSITIVITY_UP": this.emit({ kind: "sensitivity", delta: 1 }); return;
      case "SENSITIVITY_DOWN": this.emit({ kind: "sensitivity", delta: -1 }); return;
    }
  }

  /** Same as a gesture firing, for on-screen fallback buttons. */
  performAction(a: GestureAction) {
    switch (a) {
      case "OPEN_DASHBOARD": this.openDashboard("PERMANENT"); break;
      case "CLOSE_DASHBOARD": this.closeDashboard(); break;
      case "NEXT_TILE": this.stepPage("NEXT"); break;
      case "PREVIOUS_TILE": this.stepPage("PREVIOUS"); break;
      case "TOGGLE_CENTER_LOCK": this.toggleCenterLock(); break;
      case "ACKNOWLEDGE": this.acknowledgeAlerts(); break;
      case "TOGGLE_EYE_PREVIEW": this.toggleEyePreview(); break;
      case "CLEAR_DRILL": this.setDrill(NO_DRILL); break;
    }
    this.update((s) => ({ ...s, gesture: { ...s.gesture, lastAction: a, lastActionId: s.gesture.lastActionId + 1 } }));
  }

  // ------------------------------------------------------------------ gestures

  onHands(hands: HandObservation[], t: number) {
    const cur = this.state;
    const u = this.classifier.process(hands, t, cur.dashboardMode, cur.currentPage);
    this.updateMotorSteadiness(hands);
    if (u.action) {
      switch (u.action) {
        case "OPEN_DASHBOARD": this.openDashboard("PERMANENT"); break;
        case "CLOSE_DASHBOARD": this.closeDashboard(); break;
        case "NEXT_TILE": this.stepPage("NEXT"); break;
        case "PREVIOUS_TILE": this.stepPage("PREVIOUS"); break;
        case "TOGGLE_CENTER_LOCK": this.toggleCenterLock(); break;
        case "ACKNOWLEDGE": this.acknowledgeAlerts(); break;
        case "TOGGLE_EYE_PREVIEW": this.toggleEyePreview(); break;
        case "CLEAR_DRILL": this.setDrill(NO_DRILL); break;
      }
    }
    this.update((s) => {
      const fb: GestureFeedback = {
        activePose: u.activePose,
        pendingAction: u.pendingAction,
        holdProgress: u.holdProgress,
        lastAction: u.action ?? s.gesture.lastAction,
        lastActionId: u.action ? s.gesture.lastActionId + 1 : s.gesture.lastActionId,
      };
      const same = fb.activePose === s.gesture.activePose && fb.pendingAction === s.gesture.pendingAction && Math.abs(fb.holdProgress - s.gesture.holdProgress) < 0.01 && fb.lastActionId === s.gesture.lastActionId;
      if (u.mapPinchActive && isOpen(s) && s.currentPage === "ENVIRONMENT") {
        const z = clamp(s.mapZoom + u.mapPinchForwardDelta * 3.2, MAP.MIN_ZOOM, MAP.MAX_ZOOM);
        const sens = 16 / z;
        return {
          ...s,
          gesture: fb,
          isMapPinching: true,
          mapZoom: z,
          mapTiltDegrees: tiltForZoom(z),
          mapPanEastDeg: clamp(s.mapPanEastDeg + u.mapPinchPanDeltaX * sens, -MAP.MAX_PAN_EAST_DEG, MAP.MAX_PAN_EAST_DEG),
          mapPanNorthDeg: clamp(s.mapPanNorthDeg - u.mapPinchPanDeltaY * sens, -MAP.MAX_PAN_NORTH_DEG, MAP.MAX_PAN_NORTH_DEG),
        };
      }
      if (same && !s.isMapPinching) return s;
      return { ...s, gesture: fb, isMapPinching: false };
    });
  }

  private updateMotorSteadiness(hands: HandObservation[]) {
    const lm = hands.find((h) => h.landmarks.length >= 21)?.landmarks;
    if (!lm) { this.handTrack = []; return; }
    this.handTrack.push([(lm[0].x + lm[9].x) / 2, (lm[0].y + lm[9].y) / 2]);
    while (this.handTrack.length > 24) this.handTrack.shift();
    if (this.handTrack.length < 12) return;
    let sum = 0;
    for (let i = 2; i < this.handTrack.length; i++) {
      const jx = this.handTrack[i][0] - 2 * this.handTrack[i - 1][0] + this.handTrack[i - 2][0];
      const jy = this.handTrack[i][1] - 2 * this.handTrack[i - 1][1] + this.handTrack[i - 2][1];
      sum += jx * jx + jy * jy;
    }
    const score = clamp(100 - Math.sqrt(sum / (this.handTrack.length - 2)) * 6000, 0, 100);
    this.motorSmoothed = this.motorSmoothed == null ? score : this.motorSmoothed + 0.1 * (score - this.motorSmoothed);
    const v = Math.round(this.motorSmoothed);
    if (v !== this.state.motorSteadiness) this.update((s) => ({ ...s, motorSteadiness: v }));
  }

  // ------------------------------------------------------------------ eyes

  setEyeTrackingStatus(st: EyeTrackingStatus) { if (this.state.eyeTrackingStatus !== st) this.update((s) => ({ ...s, eyeTrackingStatus: st })); }

  onEyeSample(sample: EyeSample) {
    this.blink.process(sample);
    if (sample.timestampMs - this.lastEyePublishMs < 250) return;
    this.lastEyePublishMs = sample.timestampMs;
    const m = this.blink.metrics();
    this.update((s) => ({ ...s, eye: m, eyeTrackingStatus: s.eyeTrackingStatus === "STARTING" ? "ACTIVE" : s.eyeTrackingStatus }));
  }

  // ------------------------------------------------------------------ sensors

  setCameraPermission(granted: boolean) {
    if (!granted) this.classifier.reset();
    this.update((s) => ({ ...s, hasCameraPermission: granted }));
  }

  setLocationPermission(granted: boolean) { this.update((s) => ({ ...s, hasLocationPermission: granted, hasGpsFix: granted ? s.hasGpsFix : false })); }

  setBattery(pct: number | null) { if (pct !== this.state.batteryPercent) this.update((s) => ({ ...s, batteryPercent: pct })); }

  setHeadTracking(on: boolean) { if (on !== this.state.hasHeadTracking) this.update((s) => ({ ...s, hasHeadTracking: on })); }

  setMic(m: Partial<MicStatus>) { this.update((s) => ({ ...s, mic: { ...s.mic, ...m } })); }

  onLightLux(lux: number) { this.lightLux = lux; }

  onStep() { this.steps++; }

  onHeading(deg: number) {
    const d = ((deg % 360) + 360) % 360;
    this.update((s) => (Math.abs(angleDelta(s.headingDegrees, d)) < 0.25 ? s : this.withFocusFromHead({ ...s, headingDegrees: d })));
  }

  onPitch(deg: number) {
    this.update((s) => (Math.abs(s.pitchDegrees - deg) < 0.25 ? s : this.withFocusFromHead({ ...s, pitchDegrees: deg })));
  }

  private withFocusFromHead(s: HudState): HudState {
    if (!isOpen(s) || s.isMapPinching) return s;
    if (s.centerLocked) {
      return {
        ...s,
        anchorYawDegrees: s.currentPage === "DRILLS" ? s.anchorYawDegrees : anchorYawFor(s.currentPage, s.headingDegrees),
        anchorPitchDegrees: s.currentPage === "DRILLS" ? s.pitchDegrees - DRILLS_PITCH_OFFSET_DEG : s.pitchDegrees,
      };
    }
    const f = focusedPage(s.headingDegrees, s.pitchDegrees, s.anchorYawDegrees, s.anchorPitchDegrees);
    return f === s.currentPage ? s : { ...s, currentPage: f };
  }

  /** GPS sample: east/north delta from the previous accepted fix plus measured speed. */
  onLocationSample(deltaEast: number, deltaNorth: number, speedMps: number, accuracy: number) {
    const t = this.now();
    const delta = Math.hypot(deltaEast, deltaNorth);
    if (delta > 0) this.totalDistance += delta;
    this.lastLocationSampleMs = t;
    const sp = clamp(speedMps, 0, 35);
    this.smoothedSpeed += 0.45 * (sp - this.smoothedSpeed);
    if (sp === 0 && this.smoothedSpeed < 0.05) this.smoothedSpeed = 0;
    this.averagedHr += 0.3 * (this.targetHr(this.smoothedSpeed) - this.averagedHr);
    this.update((s) => {
      const last = s.track[s.track.length - 1] ?? { east: 0, north: 0 };
      const track = delta > 0 ? [...s.track, { east: last.east + deltaEast, north: last.north + deltaNorth }].slice(-200) : s.track;
      return this.applyTelemetry({ ...s, hasGpsFix: true, gpsAccuracyMeters: Math.round(accuracy), distanceMeters: this.totalDistance, speedMps: this.smoothedSpeed, track }, t, 0);
    });
  }

  private targetHr(speed: number) { return clamp(BASE_HR + Math.max(0, speed) * 14, BASE_HR, 165); }

  private refreshLocationDerived(nowMs: number) {
    const stale = this.lastLocationSampleMs === 0 || nowMs - this.lastLocationSampleMs > 4500;
    if (stale) {
      this.smoothedSpeed += 0.45 * (0 - this.smoothedSpeed);
      if (this.smoothedSpeed < 0.05) this.smoothedSpeed = 0;
    }
    this.averagedHr += 0.3 * (this.targetHr(this.smoothedSpeed) - this.averagedHr);
    this.update((s) => (s.speedMps === this.smoothedSpeed ? s : { ...s, speedMps: this.smoothedSpeed }));
  }

  onSpaceWeather(events: SpaceWeatherEvent[], fetchedAtMs: number) {
    this.spaceWeatherEvents = events;
    this.spaceWeatherFetchedAtMs = fetchedAtMs;
    this.update((s) => ({ ...s, spaceWeather: evaluateSpaceWeather(events, this.wall(), fetchedAtMs) }));
  }

  // ------------------------------------------------------------------ crew

  setCrewIdentity(id: string, name: string) {
    this.crewSelfId = id;
    this.roster = new CrewRoster(id);
    this.update((s) => ({ ...s, crewSelfName: name }));
  }

  selfPosition(): [number | null, number | null] { return [this.selfLat, this.selfLon]; }

  onAbsoluteFix(lat: number, lon: number, accuracy: number) {
    this.selfLat = lat;
    this.selfLon = lon;
    const acc = Math.round(accuracy);
    if (this.state.crewFixAccuracyMeters !== acc) this.update((s) => ({ ...s, crewFixAccuracyMeters: acc }));
  }

  setCrewLink(stats: Partial<CrewLinkStats>) { this.update((s) => ({ ...s, crewLink: { ...s.crewLink, ...stats } })); }

  onCrewPacket(status: CrewStatus, simulated = false) {
    this.roster.onPacket(status, this.wall(), simulated);
    this.refreshCrew(this.wall());
  }

  private selfEmergency(s: HudState): [boolean, string] {
    if (s.activeDrill.number !== 0) return [true, `${s.activeDrill.code} ${s.activeDrill.title}`];
    if (s.cognitive.level === "EMERGENCY" && !s.alertAcknowledged) return [true, `Cognitive load ${s.cognitive.index}`];
    return [false, ""];
  }

  crewOutbound(): CrewStatus {
    const s = this.state;
    const [emergency, reason] = this.selfEmergency(s);
    return {
      id: this.crewSelfId,
      name: s.crewSelfName || this.crewSelfId,
      lat: this.selfLat,
      lon: this.selfLon,
      headingDeg: s.headingDegrees,
      heartRateBpm: s.isBiometricSensorLost ? null : s.heartRateBpm,
      cognitiveLoad: s.cognitive.index,
      emergency,
      reason,
      sentAtMs: this.wall(),
    };
  }

  refreshCrew(nowMs: number) {
    const members = this.roster.members(nowMs, this.selfLat, this.selfLon);
    this.update((s) => (s.crew.length === 0 && members.length === 0 ? s : { ...s, crew: members }));
    for (const m of members.filter((x) => x.emergency)) {
      const last = this.crewAnnounced.get(m.id);
      if (last == null || nowMs - last >= CREW_ALERT_REPEAT_MS) {
        this.crewAnnounced.set(m.id, nowMs);
        const where = m.distanceMeters != null && m.bearingDeg != null ? `, ${Math.round(m.distanceMeters)} meters, bearing ${Math.round(m.bearingDeg)}` : ", location unknown";
        this.emit({ kind: "speak", text: `Crew ${m.name} needs help${where}. ${m.reason}.` });
      }
    }
    const ids = new Set(members.filter((m) => m.emergency).map((m) => m.id));
    for (const k of [...this.crewAnnounced.keys()]) if (!ids.has(k)) this.crewAnnounced.delete(k);
  }
}

// ------------------------------------------------------------------ text helpers shared by the UI

export function crewPositionGap(s: HudState): string {
  const peersWithout = s.crew.filter((m) => !m.sentPosition);
  const selfFix = s.crewFixAccuracyMeters != null;
  if (!s.hasLocationPermission) return "allow location on this device";
  if (!selfFix && peersWithout.length) return "no GPS on either device";
  if (!selfFix) return "no GPS on this device";
  if (peersWithout.length) return "no GPS from " + peersWithout.map((m) => m.name).join(", ");
  return "waiting for GPS";
}

export function crewSummary(s: HudState): string {
  if (!s.crewLink.active) return "Crew link off" + (s.crewLink.error ? ` · ${s.crewLink.error}` : "");
  const nearest = s.crew.find((m) => m.distanceMeters != null);
  const help = s.crew.find((m) => m.emergency);
  if (help) return `${help.name} needs help` + (help.distanceMeters != null ? ` · ${Math.round(help.distanceMeters)} m` : " · no GPS");
  if (!s.crew.length) return "Crew: searching";
  if (nearest) return `Crew ${s.crew.length} · nearest ${Math.round(nearest.distanceMeters!)} m`;
  return `Crew ${s.crew.length} linked · ` + crewPositionGap(s);
}
