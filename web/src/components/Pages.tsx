// Port of DashboardTiles.kt: one body per hazard page plus Drills.
"use client";
import { useEffect, useRef } from "react";
import { crewSummary, type HudState } from "@/lib/hud";
import { checklistFor, DRILLS } from "@/lib/mental";
import { SW_LABEL } from "@/lib/nasa";
import { angleDelta, type DashboardPage } from "@/lib/spatial";
import { Axemu, GCR_BASELINE_USV_PER_H } from "@/lib/suit";
import { MarsMap } from "./MarsMap";
import { Big, C, Card, cap, dp, formatDuration, formatMinutes, LevelBar, levelColor, Ring, Small, TrendChart } from "./ui";

const GAP = dp(6);
const rowS = { display: "flex", gap: GAP, minWidth: 0, minHeight: 0 } as const;
const colS = { display: "flex", flexDirection: "column", gap: GAP, minWidth: 0, minHeight: 0 } as const;

export function pageStatus(page: DashboardPage, s: HudState): [string, string] {
  switch (page) {
    case "RADIATION": {
      const sw = s.spaceWeather.level;
      if (s.radiation.status === "STORM") return ["SPE STORM", C.red];
      if (sw === "SEP") return ["SEP alert", C.red];
      if (s.radiation.status === "ELEVATED" || sw === "ACTIVE") return ["Elevated", C.yellow];
      return ["Nominal", C.green];
    }
    case "ISOLATION": return [cap(s.cognitive.level), levelColor(s.cognitive.level)];
    case "DISTANCE":
      if (s.earthLink.solarConjunction) return ["Comms blackout", C.red];
      if (s.consumables.turnBackInMinutes <= 0) return ["Turn back", C.red];
      return ["Act locally", "#fff"];
    case "GRAVITY": return [s.body.atrophyRisk, s.body.atrophyRisk === "On track" ? C.green : s.body.atrophyRisk === "Moderate" ? C.yellow : C.yellow];
    case "ENVIRONMENT":
      if (!s.suit.isPressureNominal || s.suit.helmetCo2MmHg >= 6 || s.suit.isOxygenLow) return ["Check suit", C.red];
      return s.hasGpsFix ? ["Live", C.green] : ["Acquiring", C.yellow];
    case "DRILLS":
      return s.activeDrill.number === 0 ? ["Standby", C.text2] : [`${s.activeDrill.code} ACTIVE`, s.activeDrill.isCritical ? C.red : C.yellow];
  }
}

export function pageHeadline(page: DashboardPage, s: HudState): string {
  switch (page) {
    case "RADIATION": return `${Math.round(s.radiation.doseRateUsvPerHour)} µSv/h`;
    case "ISOLATION": return `Load ${s.cognitive.index}`;
    case "DISTANCE": return `${Math.round(s.earthLink.oneWayDelaySec / 60)} min to Earth`;
    case "GRAVITY": return `${s.body.earthEquivalentLoadSteps} load steps`;
    case "ENVIRONMENT": return `${Math.round(s.habitability.outside.airTempC)} °C outside`;
    case "DRILLS": return s.activeDrill.number === 0 ? "Standby" : s.activeDrill.title;
  }
}

export function PageBody({ page, s, eyeVideo }: { page: DashboardPage; s: HudState; eyeVideo: MediaStream | null }) {
  switch (page) {
    case "RADIATION": return <Radiation s={s} />;
    case "ISOLATION": return <Isolation s={s} eyeVideo={eyeVideo} />;
    case "DISTANCE": return <Distance s={s} />;
    case "GRAVITY": return <Gravity s={s} />;
    case "ENVIRONMENT": return <Environment s={s} />;
    case "DRILLS": return <Drills s={s} />;
  }
}

function Radiation({ s }: { s: HudState }) {
  const r = s.radiation;
  const [, color] = pageStatus("RADIATION", s);
  const ratio = r.doseRateUsvPerHour / GCR_BASELINE_USV_PER_H;
  const sw = s.spaceWeather;
  const rel = angleDelta(r.bearingToBaseDeg, s.headingDegrees);
  return (
    <div style={{ ...rowS, height: "100%" }} data-testid="dashboard_page_radiation">
      <div style={{ ...colS, flex: 1.25 }}>
        <Card title="Dose rate" tint={color === C.green ? null : color} style={{ flex: 1 }}>
          <Big value={r.doseRateUsvPerHour.toFixed(1)} unit="µSv/h" color={color} size={24} testId="radiation_dose_rate_text" />
          <LevelBar frac={Math.max(0.03, Math.min(1, 0.25 + 0.25 * Math.log2(Math.max(0.25, ratio))))} color={color} />
          <Small>Mars baseline {Math.round(GCR_BASELINE_USV_PER_H)} µSv/h · Curiosity RAD</Small>
        </Card>
        <div style={rowS}>
          <Card title="Sun · NASA DONKI" style={{ flex: 1 }}>
            <Big value={sw.level === "UNKNOWN" ? "Offline" : SW_LABEL[sw.level]} size={13} testId="space_weather_text" />
            <Small>{sw.sepRiskPercent != null ? `SEP risk 48 h · ${sw.sepRiskPercent}%` : "Syncs when online"}</Small>
          </Card>
          <Card title="Hand steadiness" style={{ flex: 1 }}>
            <Big value={s.motorSteadiness != null ? String(s.motorSteadiness) : "Raise a hand"} unit={s.motorSteadiness != null ? "/100" : null} color={(s.motorSteadiness ?? 100) < 60 ? C.yellow : C.label} size={13} testId="motor_steadiness_text" />
            <Small>Motor watch for CNS effects</Small>
          </Card>
        </div>
      </div>
      <div style={{ ...colS, flex: 1 }}>
        <Card title="Shelter route" style={{ flex: 1 }}>
          <div style={{ display: "flex", alignItems: "center", gap: dp(8) }}>
            <div style={{ width: dp(34), height: dp(34), borderRadius: 99, background: "var(--well)", display: "grid", placeItems: "center", flex: "none" }}>
              {r.shelter === "SHELTERED" ? (
                <span style={{ width: dp(10), height: dp(10), borderRadius: 99, background: C.green }} />
              ) : (
                <svg viewBox="-10 -10 20 20" style={{ width: dp(22), height: dp(22), transform: `rotate(${rel}deg)`, transition: "transform .25s" }}>
                  <path d="M 0 -10 L 5.5 7 L 0 3.5 L -5.5 7 Z" fill={color} />
                </svg>
              )}
            </div>
            <div className="col">
              <span data-testid="radiation_base_text" style={{ fontSize: dp(13) }}>
                {r.shelter === "SHELTERED" ? "At base" : `${Math.round(r.distanceToBaseMeters)} m · ${String(Math.round(r.bearingToBaseDeg)).padStart(3, "0")}°`}
              </span>
              <Small>ETA {formatDuration(r.etaToBaseSec)} · +{r.returnDoseUsv.toFixed(1)} µSv</Small>
            </div>
          </div>
          <Small color={color} lines={2}>{r.advice}</Small>
        </Card>
        <div style={rowS}>
          <Card title="EVA dose" style={{ flex: 1 }}><Big value={r.evaDoseUsv.toFixed(1)} unit="µSv" size={14} /></Card>
          <Card title="Career used" style={{ flex: 1 }}><Big value={(r.careerLimitFraction * 100).toFixed(3)} unit="%" size={14} /></Card>
        </div>
      </div>
    </div>
  );
}

export function forecastInsight(s: HudState) {
  const fc = s.cognitiveForecast, slope = fc.slopePerMin, to = fc.minutesToCaution;
  if (fc.projectedIn10Min == null) return "Collecting trend…";
  if (s.cognitive.index >= 70 && slope < -0.3) return `Recovering ${(-slope).toFixed(1)}/min`;
  if (to != null && to >= 0.1 && to <= 30) return `Caution in ~${Math.round(to)} min`;
  if (slope > 0.3) return `Rising ${slope.toFixed(1)}/min`;
  if (slope < -0.3) return `Easing ${(-slope).toFixed(1)}/min`;
  return `Stable · ${fc.projectedIn10Min} expected`;
}

function EyePreview({ s, stream }: { s: HudState; stream: MediaStream | null }) {
  const v = useRef<HTMLVideoElement>(null);
  useEffect(() => { if (v.current && stream && v.current.srcObject !== stream) v.current.srcObject = stream; }, [stream]);
  const st = s.eyeTrackingStatus;
  const show = s.eyePreviewEnabled && stream && st === "ACTIVE";
  const text = st === "UNSUPPORTED_DEVICE" ? "Eye tracking needs a camera facing you (laptop webcam)" : st === "MODEL_ERROR" ? "Eye model unavailable" : st === "OFF" ? "Eyes off" : !s.eyePreviewEnabled ? "Tracking · preview hidden" : !s.eye.faceDetected ? "Finding eyes…" : "Tracking…";
  return (
    <div data-testid="eye_preview_box" style={{ flex: 1, minHeight: 0, borderRadius: dp(12), background: "var(--well)", position: "relative", overflow: "hidden", display: "grid", placeItems: "center", margin: `${dp(2)} 0` }}>
      {show ? <video ref={v} autoPlay muted playsInline style={{ position: "absolute", inset: 0, width: "100%", height: "100%", objectFit: "cover", transform: "scaleX(-1)" }} /> : <Small color={C.text2} lines={3}>{text}</Small>}
      {s.eye.calibrationProgress < 1 && st === "ACTIVE" ? <div style={{ position: "absolute", left: dp(4), right: dp(4), bottom: dp(4) }}><LevelBar frac={s.eye.calibrationProgress} color="#fff" /></div> : null}
    </div>
  );
}

function Isolation({ s, eyeVideo }: { s: HudState; eyeVideo: MediaStream | null }) {
  const cog = s.cognitive, fc = s.cognitiveForecast, color = levelColor(cog.level), ms = s.mental, eye = s.eye;
  return (
    <div style={{ ...rowS, height: "100%" }} data-testid="dashboard_page_mental">
      <div style={{ ...colS, flex: 1.5 }}>
        <Card title="Cognitive load · next 10 min" style={{ flex: 1 }}>
          <span data-testid="cognitive_forecast_text" style={{ fontSize: dp(10), color: (fc.minutesToCaution ?? 99) < 10 ? C.yellow : C.label }}>{forecastInsight(s)}</span>
          <TrendChart history={fc.history.length ? fc.history : [cog.index, cog.index]} projected={fc.projectedIn10Min} color={color} />
        </Card>
        <div style={rowS}>
          <Card title="Stress" style={{ flex: 1 }}><Big value={ms.stressLevel === "UNKNOWN" ? "—" : String(ms.stressPercent)} unit="%" color={ms.stressPercent >= 58 ? C.yellow : C.label} size={16} testId="cognitive_stress_text" /></Card>
          <Card title="Sleep" style={{ flex: 1 }}><Big value={ms.sleep.durationHours.toFixed(1)} unit="h" color={ms.sleep.durationHours <= 5 ? C.yellow : C.label} size={16} testId="cognitive_sleep_text" /></Card>
          <Card title="Heart rate" style={{ flex: 1 }}><Big value={s.isBiometricSensorLost ? "—" : String(s.heartRateBpm)} size={16} /></Card>
        </div>
      </div>
      <Card title="Eyes · front camera" style={{ flex: 1 }}>
        <EyePreview s={s} stream={eyeVideo} />
        <div style={{ display: "flex", justifyContent: "space-between" }}>
          <div className="col"><Small>Blinks</Small><span data-testid="eye_blink_count_text" style={{ fontSize: dp(13) }}>{eye.totalBlinks}</span></div>
          <div className="col" style={{ alignItems: "flex-end" }}><Small>{eye.blinkRatePerMin != null ? `you ${Math.round(eye.effectiveBaseline)}/min` : "learning"}</Small><span style={{ fontSize: dp(13) }}>{eye.blinkRatePerMin != null ? `${Math.round(eye.blinkRatePerMin)}/min` : "—"}</span></div>
        </div>
        <Small color={cog.ocularPattern === "NORMAL" ? C.green : C.yellow}>{cap(cog.ocularPattern)}</Small>
      </Card>
    </div>
  );
}

function Distance({ s }: { s: HudState }) {
  const c = s.consumables, link = s.earthLink;
  const turn = c.turnBackInMinutes <= 0 ? C.red : c.turnBackInMinutes <= 30 ? C.yellow : C.green;
  const l = s.crewLink;
  return (
    <div style={{ ...rowS, height: "100%" }} data-testid="dashboard_page_distance">
      <div style={{ ...colS, flex: 1.2 }}>
        <Card title="Turn back in" tint={turn === C.green ? null : turn} style={{ flex: 1 }}>
          <Big value={c.turnBackInMinutes <= 0 ? "Now" : formatMinutes(c.turnBackInMinutes)} color={turn} size={24} testId="turn_back_text" />
          <Small>{c.limitingFactor} limits · {Axemu.RETURN_RESERVE_MIN} min reserve · {c.usesObservedTrend ? "observed trend" : "suit model"}</Small>
        </Card>
        <div style={rowS}>
          <Card title="Oxygen" style={{ flex: 1 }}><Big value={formatDuration(c.oxygenMinutesLeft * 60)} color={s.suit.isOxygenLow ? C.red : C.label} size={12} /></Card>
          <Card title="Battery" style={{ flex: 1 }}><Big value={formatDuration(c.batteryMinutesLeft * 60)} color={s.suit.isLowPower ? C.red : C.label} size={12} /></Card>
          <Card title="Walk back" style={{ flex: 1 }}><Big value={formatDuration(s.radiation.etaToBaseSec)} size={12} /></Card>
        </div>
      </div>
      <div style={{ ...colS, flex: 1 }}>
        <Card title="Signal to Earth">
          <Big value={(link.oneWayDelaySec / 60).toFixed(1)} unit="min one way" size={18} testId="earth_delay_text" />
          <Small>Round trip {Math.round(link.oneWayDelaySec / 30)} min · {link.distanceAu.toFixed(2)} AU · JPL</Small>
        </Card>
        <Card title={s.activeDrill.number === 0 ? "Self-sufficiency" : `${s.activeDrill.code} · ${s.activeDrill.title}`} tint={s.activeDrill.number === 0 ? null : C.red} style={{ flex: 1 }}>
          {s.activeDrill.number === 0 ? (
            <>
              <Small color={C.text2} lines={2}>Earth can&apos;t answer in time. Decide here, then report.</Small>
              <Small color={s.crew.some((m) => m.emergency) ? C.red : C.label} testId="crew_summary_text">{crewSummary(s)}</Small>
              <Small lines={2} testId="crew_diagnostics_text">
                {s.crewSelfName} · {l.mode}{l.room ? ` ${l.room}` : ""} · sent {l.sent} · peers {l.heardOthers} · {s.crewFixAccuracyMeters != null ? `gps ±${s.crewFixAccuracyMeters} m` : "gps none"}
              </Small>
            </>
          ) : (
            checklistFor(s.activeDrill).map((step, i) => <Small key={i} color={C.label}>{i + 1}. {step}</Small>)
          )}
        </Card>
      </div>
    </div>
  );
}

function Gravity({ s }: { s: HudState }) {
  const b = s.body;
  const distFrac = Math.max(0, Math.min(1, s.distanceMeters / Math.max(100, s.distanceGoalMeters)));
  const [, risk] = pageStatus("GRAVITY", s);
  return (
    <div style={{ ...rowS, height: "100%" }} data-testid="dashboard_page_gravity">
      <Card title="Activity · 0.38 g" style={{ flex: 1.35 }}>
        <div style={{ display: "flex", alignItems: "center", flex: 1, gap: dp(10), minHeight: 0 }}>
          <svg viewBox="0 0 100 100" data-testid="gravity_rings" style={{ width: dp(84), height: dp(84), flex: "none" }}>
            <Ring frac={b.loadGoalFraction} color="#ffffff" inset={0} stroke={10} />
            <Ring frac={b.exerciseGoalFraction} color="#b8b8b8" inset={13} stroke={10} />
            <Ring frac={distFrac} color="#7a7a7a" inset={26} stroke={10} />
          </svg>
          <div className="col" style={{ gap: dp(3) }}>
            <Legend k="Load" v={`${b.earthEquivalentLoadSteps.toLocaleString("en-US")} / 7,500`} testId="gravity_load_text" />
            <Legend k="Exercise" v={`${Math.round(b.exerciseMinutes)} / 150 min`} />
            <Legend k="Distance" v={`${(s.distanceMeters / 1000).toFixed(2)} / ${(s.distanceGoalMeters / 1000).toFixed(1)} km`} />
          </div>
        </div>
        <Small color={risk} lines={2}>{b.advice}</Small>
      </Card>
      <div style={{ ...colS, flex: 1 }}>
        <Card title="Steps on Mars"><Big value={String(b.steps)} unit="steps" size={18} /></Card>
        <Card title="Bone & muscle" style={{ flex: 1 }}>
          <Big value={b.atrophyRisk} color={risk} size={14} />
          <Small lines={3}>Each step loads ~38% of Earth weight. ISS crews exercise ~2.5 h/day and still lose ~1%/month spine bone.</Small>
        </Card>
      </div>
    </div>
  );
}

function Legend({ k, v, testId }: { k: string; v: string; testId?: string }) {
  return <div className="col"><Small>{k}</Small><span data-testid={testId} style={{ fontSize: dp(9.5) }}>{v}</span></div>;
}

function Environment({ s }: { s: HudState }) {
  const h = s.habitability, suit = s.suit, d = Math.max(0, s.distanceMeters);
  const co2c = suit.helmetCo2MmHg >= 6 ? C.red : suit.helmetCo2MmHg >= 3 ? C.yellow : C.label;
  return (
    <div style={{ ...rowS, height: "100%" }} data-testid="dashboard_page_environment">
      <Card title="Live map · Jezero" style={{ flex: 1.3 }}>
        <MarsMap track={s.track} heading={s.headingDegrees} hasGpsFix={s.hasGpsFix} zoom={s.mapZoom} tilt={s.mapTiltDegrees} panEast={s.mapPanEastDeg} panNorth={s.mapPanNorthDeg} pinching={s.isMapPinching} crew={s.crew} />
        <div style={{ display: "flex", alignItems: "center", gap: dp(6) }}>
          <span data-testid="dashboard_distance_text" style={{ fontSize: dp(11) }}>{d >= 1000 ? (d / 1000).toFixed(2) : Math.round(d)}</span>
          <span style={{ fontSize: dp(7.5), color: C.text2 }}>{d >= 1000 ? "KM" : "METERS"}</span>
          <span data-testid="dashboard_speed_text" style={{ fontSize: dp(9) }}>{Math.max(0, s.speedMps).toFixed(1)} m/s</span>
          <span style={{ flex: 1 }} />
          {!s.hasLocationPermission ? <Small color={C.yellow}>GPS off</Small> : s.gpsAccuracyMeters != null ? <Small>±{s.gpsAccuracyMeters} m</Small> : null}
        </div>
        {s.crewLink.active && s.crew.length ? <Small color={s.crew.some((m) => m.emergency) ? C.red : C.text2} testId="map_crew_text">{crewSummary(s)}</Small> : null}
      </Card>
      <div style={{ ...colS, flex: 1 }}>
        <div style={rowS}>
          <Card title="Air °C" style={{ flex: 1 }}><Big value={Math.round(h.outside.airTempC).toString()} size={15} /><Small testId="outside_air_text">{Math.round(h.outside.pressurePa)} Pa</Small></Card>
          <Card title="Suit psi" style={{ flex: 1 }}><Big value={suit.suitPressureTotalPsi.toFixed(1)} color={suit.isPressureNominal ? C.label : C.red} size={15} testId="suit_pressure_text" /><Small>{Math.round(suit.suitTempC)} °C inside</Small></Card>
        </div>
        <div style={rowS}>
          <Card title="CO₂ mmHg" style={{ flex: 1 }}><Big value={suit.helmetCo2MmHg.toFixed(1)} color={co2c} size={15} testId="suit_helmet_co2_text" /></Card>
          <Card title="Cold min" style={{ flex: 1 }}><Big value={Math.round(h.coldSoakMinutes).toString()} unit={`/ ${Axemu.RATED_COLD_SOAK_MIN}`} size={15} /></Card>
        </div>
        <Card title={`Light · noise · ${Axemu.NAME}`} style={{ flex: 1 }}>
          <Small color={C.label} lines={2}>{[h.lightLux != null ? `${Math.round(h.lightLux)} lx` : null, h.helmetLightsAdvised ? "helmet lights on" : null, h.noiseLabel !== "—" ? h.noiseLabel.toLowerCase() : null, h.outside.isDaylight ? "daylight" : "night"].filter(Boolean).join(" · ")}</Small>
        </Card>
      </div>
    </div>
  );
}

function Drills({ s }: { s: HudState }) {
  const list = DRILLS.slice(1);
  const rows = Math.ceil(list.length / 2);
  return (
    <div style={{ ...colS, height: "100%" }} data-testid="dashboard_page_drills">
      <div style={{ ...rowS, flex: 1 }}>
        {[0, 1].map((col) => (
          <div key={col} style={{ ...colS, flex: 1, gap: dp(4) }}>
            {list.slice(col * rows, col * rows + rows).map((d) => {
              const active = d.number === s.activeDrill.number;
              const accent = d.isCritical ? C.red : C.yellow;
              return (
                <div key={d.number} data-testid={`drill_item_${d.number}`} style={{ flex: 1, display: "flex", alignItems: "center", borderRadius: dp(10), padding: `0 ${dp(8)}`, background: active ? `${accent}47` : "var(--card)", minHeight: 0 }}>
                  <span style={{ fontSize: dp(9), color: active ? accent : C.text2, width: dp(24) }}>E{d.number}</span>
                  <span style={{ fontSize: dp(9), flex: 1, whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>{d.title}</span>
                  <span style={{ fontSize: dp(7.5), color: C.text3, whiteSpace: "nowrap" }}>{d.impactSummary}</span>
                </div>
              );
            })}
          </div>
        ))}
      </div>
      <Small>Say “emergency 1-10” to rehearse · thumbs down to clear</Small>
    </div>
  );
}
