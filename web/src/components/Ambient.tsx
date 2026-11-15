// Dashboard-closed HUD: compass (GameGradientCompass.kt), glance strip + load halo (AmbientHud.kt),
// alert banner, gesture chip and mic meter (SpatialHudScreen.kt).
"use client";
import { useEffect, useState } from "react";
import { ACTION_LABEL, POSE_LABEL } from "@/lib/gestures";
import type { HudState } from "@/lib/hud";
import { C, Dot, dp, formatMinutes, levelColor } from "./ui";

const label = (d: number) => ({ 0: "N", 45: "NE", 90: "E", 135: "SE", 180: "S", 225: "SW", 270: "W", 315: "NW" })[d] ?? String(d).padStart(3, "0");

export function Compass({ s }: { s: HudState }) {
  const [time, setTime] = useState("");
  useEffect(() => {
    const f = () => setTime(new Date().toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit" }));
    f();
    const id = setInterval(f, 1000);
    return () => clearInterval(id);
  }, []);
  const W = 300, H = 34, half = 38, ppd = W / (half * 2), cx = W / 2, lift = 3.5, arcY = 8;
  const h = ((s.headingDegrees % 360) + 360) % 360;
  const curve = (x: number) => -lift * Math.min(1, Math.abs((x - cx) / cx)) ** 2;
  const ticks = [];
  for (let t = Math.floor((h - half) / 5) * 5; t <= Math.floor((h - half) / 5) * 5 + 85; t += 5) {
    const delta = t - h, x = cx + delta * ppd;
    if (x < 0 || x > W) continue;
    const fade = Math.max(0, 1 - (Math.abs(delta) / half) ** 2);
    if (fade < 0.04) continue;
    const w = ((t % 360) + 360) % 360, major = w % 15 === 0, card = w % 45 === 0, top = arcY + curve(x) + 2.5;
    ticks.push(
      <g key={t}>
        <line x1={x} x2={x} y1={top} y2={top + (major ? 5.5 : 3)} stroke="#fff" strokeOpacity={(major ? 0.74 : 0.36) * fade} strokeWidth={major ? 1.05 : 0.7} strokeLinecap="round" />
        {major ? <text x={x} y={top + 15} textAnchor="middle" fill="#fff" fillOpacity={(card ? 0.94 : 0.72) * fade} fontSize={card ? 10 : 8.5} fontWeight={card ? 600 : 500}>{label(w)}</text> : null}
      </g>,
    );
  }
  const crew = s.crew.flatMap((m) => {
    if (m.bearingDeg == null) return [];
    let d = (m.bearingDeg - h) % 360;
    if (d > 180) d -= 360;
    if (d < -180) d += 360;
    const c = m.emergency ? C.red : "#fff";
    if (Math.abs(d) <= half) {
      const x = cx + d * ppd;
      return [<circle key={m.id} cx={x} cy={arcY + curve(x) - 4.5} r={3} fill={c} />];
    }
    const x = d < 0 ? 3 : W - 3, dir = d < 0 ? 1 : -1, y = arcY - 2;
    return [<path key={m.id} d={`M ${x + dir * 4} ${y - 3} L ${x} ${y} L ${x + dir * 4} ${y + 3}`} stroke={c} strokeWidth={1.4} fill="none" strokeLinecap="round" />];
  });
  const bat = s.batteryPercent;
  return (
    <div data-testid="game_compass" style={{ width: `max(${dp(210)}, 32vw)`, maxWidth: dp(360), display: "flex", flexDirection: "column", alignItems: "center" }}>
      <svg viewBox={`0 0 ${W} ${H}`} style={{ width: "100%", height: "auto", overflow: "visible" }}>
        <defs>
          <linearGradient id="arcg" x1="0" x2="1">
            <stop offset="0" stopColor="#fff" stopOpacity="0" />
            <stop offset="0.18" stopColor="#fff" stopOpacity="0.22" />
            <stop offset="0.5" stopColor="#fff" stopOpacity="0.56" />
            <stop offset="0.82" stopColor="#fff" stopOpacity="0.22" />
            <stop offset="1" stopColor="#fff" stopOpacity="0" />
          </linearGradient>
        </defs>
        <path d={Array.from({ length: 41 }, (_, i) => { const x = (W * i) / 40; return `${i ? "L" : "M"} ${x} ${arcY + curve(x)}`; }).join(" ")} stroke="url(#arcg)" strokeWidth={0.75} fill="none" />
        <path d={`M ${cx - 2.8} 2 L ${cx} 4.8 L ${cx + 2.8} 2`} stroke="#fff" strokeOpacity={0.88} strokeWidth={1.15} fill="none" strokeLinecap="round" strokeLinejoin="round" />
        {ticks}
        {crew}
      </svg>
      <div style={{ display: "flex", gap: dp(6), marginTop: dp(2) }}>
        <MicroPill testId="compass_time_text">{time}</MicroPill>
        <MicroPill testId="compass_heading_text">{String(Math.round(h) % 360).padStart(3, "0")}°{s.hasHeadTracking ? "" : " · no compass"}</MicroPill>
        {bat != null ? <MicroPill testId="compass_battery_text">{bat}%</MicroPill> : null}
      </div>
    </div>
  );
}

function MicroPill({ children, testId }: { children: React.ReactNode; testId?: string }) {
  return (
    <span data-testid={testId} style={{ fontSize: dp(10), fontWeight: 500, padding: `${dp(2)} ${dp(8)}`, borderRadius: 99, background: "rgba(255,255,255,0.13)", border: "0.5px solid rgba(255,255,255,0.28)", WebkitBackdropFilter: "blur(10px)", backdropFilter: "blur(10px)", whiteSpace: "nowrap" }}>
      {children}
    </span>
  );
}

export function LoadHalo({ s, alpha }: { s: HudState; alpha: number }) {
  const lvl = s.cognitive.level;
  const color = s.radiation.status === "STORM" ? C.red : levelColor(lvl);
  const frac = Math.max(0.06, Math.min(1, s.cognitive.index / 100));
  const anim = lvl === "NOMINAL" ? undefined : `pulse ${lvl === "EMERGENCY" ? 0.42 : 1.8}s ease-in-out infinite alternate`;
  const line = (side: "left" | "right") => (
    <div style={{ position: "absolute", [side]: dp(2), top: `${50 - frac * 50}%`, height: `${frac * 100}%`, width: dp(10), display: "flex", justifyContent: "center", opacity: lvl === "NOMINAL" ? 0.5 : 1, animation: anim, transition: "top .6s, height .6s" }}>
      <div style={{ position: "absolute", inset: 0, borderRadius: 99, background: `linear-gradient(180deg, transparent, ${color}52, transparent)` }} />
      <div style={{ width: dp(2), borderRadius: 99, background: `linear-gradient(180deg, transparent, ${color}, transparent)` }} />
    </div>
  );
  return (
    <div data-testid="load_halo" style={{ position: "absolute", inset: 0, pointerEvents: "none", opacity: alpha, transition: "opacity .26s" }}>
      {line("left")}
      {line("right")}
    </div>
  );
}

export function GlanceStrip({ s }: { s: HudState }) {
  const lost = s.isBiometricSensorLost;
  const radColor = s.radiation.status === "STORM" ? C.red : s.radiation.status === "ELEVATED" ? C.yellow : C.text2;
  const help = s.crew.find((m) => m.emergency);
  const nearest = help ?? s.crew[0];
  const rows: [string, string, string, string][] = [
    ["HR", lost ? "--" : String(s.heartRateBpm), C.text2, "vitals_bpm_text"],
    ["O₂", formatMinutes(s.consumables.oxygenMinutesLeft), s.suit.isOxygenLow ? C.red : C.text2, "glance_o2_text"],
    ["RAD", Math.round(s.radiation.doseRateUsvPerHour).toString(), radColor, "glance_rad_text"],
  ];
  if (s.crewLink.active) {
    rows.push([
      help ? `SOS ${help.name.replace(/^Crew-/, "")}` : `CREW ${s.crew.length}`,
      !nearest ? "searching" : nearest.distanceMeters != null ? `${Math.round(nearest.distanceMeters)} m` : "no GPS",
      help ? C.red : C.text2,
      "glance_crew_text",
    ]);
  }
  const lc = levelColor(s.cognitive.level);
  return (
    <div data-testid="glance_strip" style={{ borderRadius: dp(16), background: "rgba(0,0,0,0.42)", border: "0.5px solid rgba(255,255,255,0.18)", padding: `${dp(10)} ${dp(10)}`, display: "flex", flexDirection: "column" }}>
      {rows.map(([icon, val, col, id], i) => (
        <div key={id}>
          {i > 0 ? <div style={{ margin: `${dp(6)} 0`, width: dp(18), height: dp(0.6), background: "rgba(255,255,255,0.22)" }} /> : null}
          <div style={{ fontSize: dp(6.5), color: col, fontWeight: 600, letterSpacing: dp(0.4) }}>{icon}</div>
          <div data-testid={id} style={{ fontSize: dp(10.5) }}>{val}</div>
        </div>
      ))}
      <div style={{ margin: `${dp(6)} 0`, width: dp(18), height: dp(0.6), background: "rgba(255,255,255,0.22)" }} />
      <div style={{ display: "flex", alignItems: "center", gap: dp(5) }}>
        <svg viewBox="0 0 12 12" style={{ width: dp(12), height: dp(12) }}>
          <circle cx="6" cy="6" r="4.6" stroke={lc} strokeOpacity="0.25" strokeWidth="1.8" fill="none" />
          <circle cx="6" cy="6" r="4.6" stroke={lc} strokeWidth="1.8" fill="none" strokeDasharray={`${(s.cognitive.index / 100) * 28.9} 29`} transform="rotate(-90 6 6)" strokeLinecap="round" />
        </svg>
        <span data-testid="glance_load_text" style={{ fontSize: dp(10.5), color: lc }}>{s.cognitive.index}</span>
      </div>
    </div>
  );
}

export function AlertBanner({ s }: { s: HudState }) {
  const cog = s.cognitive, rad = s.radiation;
  const help = s.crew.find((m) => m.emergency);
  let text: string | null = null, color = C.green;
  if (help) {
    text = `Crew ${help.name} needs help` + (help.distanceMeters != null ? ` · ${Math.round(help.distanceMeters)} m` : "") + (help.bearingDeg != null ? ` · ${String(Math.round(help.bearingDeg)).padStart(3, "0")}°` : "");
    color = C.red;
  } else if (cog.level === "EMERGENCY") {
    text = `Emergency · load ${cog.index} · pulse ${s.isBiometricSensorLost ? "--" : s.heartRateBpm} · ${s.alertAcknowledged ? "acknowledged" : "thumbs up to acknowledge"}`;
    color = C.red;
  } else if (cog.level === "WARNING") { text = `Warning · load ${cog.index} · ${cog.topDriver.toLowerCase()}`; color = C.red; }
  else if (rad.status === "STORM") { text = "Solar particle event · seek shelter"; color = C.red; }
  else if (cog.level === "CAUTION") { text = `Caution · load ${cog.index} · ${cog.topDriver.toLowerCase()}`; color = C.yellow; }
  else if (s.activeDrill.number !== 0) { text = `Drill ${s.activeDrill.code} · ${s.activeDrill.title}`; color = "#fff"; }
  if (!text) return null;
  const emergency = cog.level === "EMERGENCY" && !s.alertAcknowledged;
  return (
    <div data-testid="alert_banner" style={{ marginTop: dp(4), display: "flex", alignItems: "center", gap: dp(8), background: "#000", borderRadius: 99, padding: `${dp(6)} ${dp(14)}`, border: `${dp(emergency ? 1.5 : 0.5)} solid ${color}${emergency ? "" : "80"}`, maxWidth: "52vw" }}>
      <Dot color={color} className={emergency ? "pulse" : undefined} />
      <span style={{ fontSize: dp(11), whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>{text}</span>
    </div>
  );
}

export function GestureChip({ s }: { s: HudState }) {
  const g = s.gesture;
  const [flash, setFlash] = useState(false);
  useEffect(() => {
    if (g.lastActionId === 0) return;
    setFlash(true);
    const t = setTimeout(() => setFlash(false), 900);
    return () => clearTimeout(t);
  }, [g.lastActionId]);
  const pending = g.pendingAction != null && g.holdProgress > 0.02;
  const showFlash = flash && g.lastAction != null;
  if (!pending && !showFlash && g.activePose === "NONE") return null;
  const p = showFlash && !pending ? 1 : g.holdProgress;
  const done = showFlash && !pending;
  const text = pending ? `${POSE_LABEL[g.activePose]} → ${ACTION_LABEL[g.pendingAction!]}` : showFlash ? ACTION_LABEL[g.lastAction!] : POSE_LABEL[g.activePose];
  return (
    <div data-testid="gesture_chip" style={{ display: "flex", alignItems: "center", gap: dp(6), background: "rgba(0,0,0,0.85)", borderRadius: 99, padding: `${dp(4)} ${dp(10)}` }}>
      <svg viewBox="0 0 16 16" style={{ width: dp(16), height: dp(16) }}>
        <circle cx="8" cy="8" r="7" stroke="#fff" strokeOpacity="0.2" strokeWidth="2" fill="none" />
        <circle cx="8" cy="8" r="7" stroke={done ? C.green : "#fff"} strokeWidth="2" fill="none" strokeLinecap="round" strokeDasharray={`${p * 44} 44`} transform="rotate(-90 8 8)" />
      </svg>
      <span style={{ fontSize: dp(9), color: done ? C.green : "rgba(255,255,255,0.94)" }}>{text}</span>
    </div>
  );
}

export function MicMeter({ s }: { s: HudState }) {
  const m = s.mic;
  if (!m.available && !m.error) return null;
  const norm = Math.max(0.02, Math.min(1, (m.levelDb + 80) / 70));
  const status = m.error ? m.error : m.loadingPct != null ? `Voice loading ${m.loadingPct}%` : m.listening ? `Mic · S${m.sensitivity}${m.engine === "webspeech" ? " · browser" : ""}` : "Mic";
  return (
    <div data-testid="mic_meter" style={{ display: "flex", alignItems: "center", gap: dp(5), fontSize: dp(7), color: "rgba(255,255,255,0.66)", fontWeight: 500 }}>
      <span>{status}</span>
      <div style={{ width: dp(70), height: dp(4), borderRadius: 99, background: "rgba(255,255,255,0.14)", overflow: "hidden" }}>
        <div style={{ width: `${norm * 100}%`, height: "100%", borderRadius: 99, background: m.speaking ? C.green : "rgba(255,255,255,0.55)" }} />
      </div>
      {m.lastHeard ? <span style={{ maxWidth: dp(140), overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>“{m.lastHeard}”</span> : null}
    </div>
  );
}
