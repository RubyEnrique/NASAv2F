// Port of SpatialDashboard (SpatialHudScreen.kt) and VisionWindow.kt: pages hang in space around the
// wearer; the focused one is a glass window with a mind sidebar, tab ornament and gesture ornament.
"use client";
import { useEffect, useRef, useState } from "react";
import type { HudState } from "@/lib/hud";
import { isOpen } from "@/lib/hud";
import { formatHours } from "@/lib/nasa";
import { PAGE_INFO, project, RING, tileYaw, tilePitch, type DashboardPage } from "@/lib/spatial";
import { pageHeadline, pageStatus, PageBody } from "./Pages";
import { Badge, C, cap, dp, Gauge, levelColor, Pill } from "./ui";

const VIEW_HFOV = 66;

/** Smoothly follows a target angle (anchor glide, like animateFloatAsState). */
function useGlide(target: number, ms = 380) {
  const [v, setV] = useState(target);
  const cur = useRef(target);
  useEffect(() => {
    let raf = 0;
    const from = cur.current, t0 = performance.now();
    const step = () => {
      const k = Math.min(1, (performance.now() - t0) / ms);
      const e = 1 - (1 - k) ** 3;
      cur.current = from + (target - from) * e;
      setV(cur.current);
      if (k < 1) raf = requestAnimationFrame(step);
    };
    step();
    return () => cancelAnimationFrame(raf);
  }, [target, ms]);
  return v;
}

export function SpatialDashboard({ s, eyeVideo }: { s: HudState; eyeVideo: MediaStream | null }) {
  const open = isOpen(s);
  const anchorYaw = useGlide(s.anchorYawDegrees);
  const anchorPitch = useGlide(s.anchorPitchDegrees);
  const [cue, setCue] = useState(false);
  useEffect(() => {
    if (!s.swipeCueId) return;
    setCue(true);
    const t = setTimeout(() => setCue(false), 420);
    return () => clearTimeout(t);
  }, [s.swipeCueId]);
  const wrap = useRef<HTMLDivElement>(null);
  const [size, setSize] = useState({ w: 800, h: 400 });
  useEffect(() => {
    const el = wrap.current;
    if (!el) return;
    const ro = new ResizeObserver(() => setSize({ w: el.clientWidth, h: el.clientHeight }));
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  const pxPerDeg = size.w / VIEW_HFOV;
  // Same formula as --dp in globals.css (CSS variables are not resolved to px by getComputedStyle).
  const dpPx = Math.min(2, Math.max(1, Math.min(window.innerWidth / 760, window.innerHeight / 360)));
  const topReserve = 92 * dpPx, bottomReserve = 44 * dpPx;
  const usableH = Math.max(150 * dpPx, size.h - topReserve - bottomReserve);
  const centerY = topReserve + usableH / 2;
  const winW = Math.min(size.w * 0.62, 470 * dpPx), winH = Math.min(usableH, 214 * dpPx);
  const pages: DashboardPage[] = [...RING, "DRILLS"];
  const proj = pages.map((p) => [p, project(tileYaw(p, anchorYaw, s.headingDegrees), tilePitch(p, anchorPitch), s.headingDegrees, s.pitchDegrees)] as const);
  const focus = proj.find(([p]) => p === s.currentPage)?.[1];

  return (
    <div ref={wrap} style={{ position: "absolute", inset: 0, pointerEvents: open ? "auto" : "none", opacity: open ? 1 : 0, transition: "opacity .25s", background: `rgba(0,0,0,${open ? 0.12 : 0})` }}>
      {open
        ? proj.map(([p, pr]) =>
            !pr.visible || p === s.currentPage ? null : (
              <div key={p} style={{ position: "absolute", left: size.w / 2 + pr.xDeg * pxPerDeg - 75 * dpPx, top: centerY - pr.yDeg * pxPerDeg - 20 * dpPx, width: 150 * dpPx, opacity: 0.55 * pr.alpha, transform: `perspective(${600 * dpPx}px) rotateY(${pr.rotationY}deg)` }}>
                <Peek page={p} s={s} />
              </div>
            ),
          )
        : null}
      {open && focus && focus.visible ? (
        <>
          <div
            data-testid={`spatial_tile_${s.currentPage.toLowerCase()}`}
            style={{
              position: "absolute",
              left: size.w / 2 + focus.xDeg * pxPerDeg - winW / 2,
              top: centerY - focus.yDeg * pxPerDeg - winH / 2,
              width: winW,
              height: winH,
              opacity: focus.alpha,
              transform: `perspective(${600 * dpPx}px) rotateY(${focus.rotationY}deg) rotateX(${focus.rotationX}deg) scale(${focus.scale})`,
            }}
          >
            <VisionWindow page={s.currentPage} s={s} eyeVideo={eyeVideo} />
            <div style={{ position: "absolute", left: "50%", top: winH + 8 * dpPx, transform: "translateX(-50%)" }}><Tabs current={s.currentPage} /></div>
            <div style={{ position: "absolute", left: -44 * dpPx, top: "50%", transform: "translateY(-50%)" }}><GestureOrnament s={s} /></div>
          </div>
        </>
      ) : null}
      {cue && s.lastSwipeDirection ? (
        <div style={{ position: "absolute", top: 0, bottom: 0, [s.lastSwipeDirection === "PREVIOUS" ? "left" : "right"]: 0, width: dp(40), background: `linear-gradient(${s.lastSwipeDirection === "PREVIOUS" ? "90deg" : "270deg"}, rgba(255,255,255,0.36), transparent)`, pointerEvents: "none" }} />
      ) : null}
    </div>
  );
}

function Peek({ page, s }: { page: DashboardPage; s: HudState }) {
  const [status, color] = pageStatus(page, s);
  return (
    <div className="glass" style={{ borderRadius: dp(16), padding: `${dp(7)} ${dp(9)}`, display: "flex", alignItems: "center", gap: dp(6) }}>
      <Badge hazard={PAGE_INFO[page].hazard} size={16} />
      <div className="col" style={{ minWidth: 0, flex: 1 }}>
        <span style={{ fontSize: dp(9), fontWeight: 600, whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>{PAGE_INFO[page].tab}</span>
        <span style={{ fontSize: dp(7.5), color: C.text2, whiteSpace: "nowrap" }}>{pageHeadline(page, s)}</span>
      </div>
      <span style={{ width: dp(6), height: dp(6), borderRadius: 99, background: color, flex: "none" }} title={status} />
    </div>
  );
}

function VisionWindow({ page, s, eyeVideo }: { page: DashboardPage; s: HudState; eyeVideo: MediaStream | null }) {
  const [status, color] = pageStatus(page, s);
  const info = PAGE_INFO[page];
  return (
    <div className="glass" style={{ width: "100%", height: "100%", borderRadius: dp(30), display: "flex", overflow: "hidden" }}>
      <div className="col" style={{ flex: 1, padding: `${dp(12)} ${dp(10)} ${dp(12)} ${dp(14)}` }}>
        <div style={{ display: "flex", alignItems: "center", gap: dp(8) }}>
          <Badge hazard={info.hazard} />
          <div className="col" style={{ flex: 1 }}>
            <span style={{ fontSize: dp(13), fontWeight: 700, whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>{info.title}</span>
            <span style={{ fontSize: dp(8), color: C.text2 }}>{info.focus}</span>
          </div>
          <Pill text={status} color={color} testId={page === "ISOLATION" ? "cognitive_level_pill" : `${page.toLowerCase()}_status_pill`} />
        </div>
        <div style={{ flex: 1, minHeight: 0, marginTop: dp(8) }}>
          <PageBody page={page} s={s} eyeVideo={eyeVideo} />
        </div>
      </div>
      <MindSidebar s={s} />
    </div>
  );
}

function MindSidebar({ s }: { s: HudState }) {
  const cog = s.cognitive, color = levelColor(cog.level);
  const chip = (label: string, on: boolean, yellow = false) => (
    <span style={{ flex: 1, height: dp(22), borderRadius: dp(9), display: "grid", placeItems: "center", fontSize: dp(7.5), fontWeight: 500, background: on ? (yellow ? C.yellow : "rgba(255,255,255,0.88)") : "rgba(255,255,255,0.10)", color: on ? "#000" : C.text2 }}>{label}</span>
  );
  return (
    <div className="col" style={{ width: dp(112), background: "rgba(255,255,255,0.07)", padding: `${dp(10)} ${dp(10)}`, alignItems: "center", gap: dp(5) }}>
      <span style={{ fontSize: dp(17), fontWeight: 300 }}>{formatHours(s.marsTime.localMeanSolarTimeHours)}</span>
      <span data-testid="mars_time_text" style={{ fontSize: dp(7.5), color: C.text2 }}>Sol {Math.trunc(s.marsTime.marsSolDate)} · Jezero</span>
      <span style={{ width: dp(26), height: dp(1), background: "rgba(255,255,255,0.35)" }} />
      <div style={{ position: "relative", width: dp(70), height: dp(70), display: "grid", placeItems: "center" }}>
        <Gauge value={cog.index} color={color} forecast={s.cognitiveForecast.projectedIn10Min} />
        <div className="col" style={{ alignItems: "center" }}>
          <span data-testid="cognitive_load_text" style={{ fontSize: dp(22), fontWeight: 300, lineHeight: 1 }}>{cog.index}</span>
          <span style={{ fontSize: dp(7), color: C.text2 }}>load</span>
        </div>
      </div>
      <span data-testid="cognitive_driver_text" style={{ fontSize: dp(8.5), color, whiteSpace: "nowrap" }}>{cap(cog.topDriver)}</span>
      {cog.level !== "NOMINAL" ? <BreathingPacer color={color} /> : null}
      <span style={{ flex: 1 }} />
      <div style={{ display: "flex", gap: dp(5), width: "100%" }}>{chip("Eyes", s.eyeTrackingStatus === "ACTIVE")}{chip("Lock", s.centerLocked)}</div>
      <div style={{ display: "flex", gap: dp(5), width: "100%" }}>{chip("Breathe", cog.level !== "NOMINAL", true)}{chip("Ack", s.alertAcknowledged)}</div>
    </div>
  );
}

function BreathingPacer({ color }: { color: string }) {
  const [t, setT] = useState(0);
  useEffect(() => {
    const t0 = performance.now();
    let raf = 0;
    const f = () => { setT(((performance.now() - t0) / 4000) % 4); raf = requestAnimationFrame(f); };
    f();
    return () => cancelAnimationFrame(raf);
  }, []);
  const phase = Math.min(3, Math.floor(t)), frac = t - phase;
  const r = phase === 0 ? 0.35 + 0.65 * frac : phase === 1 ? 1 : phase === 2 ? 1 - 0.65 * frac : 0.35;
  return (
    <div data-testid="breathing_pacer" style={{ display: "flex", alignItems: "center", gap: dp(5) }}>
      <svg viewBox="0 0 14 14" style={{ width: dp(14), height: dp(14) }}><circle cx="7" cy="7" r="7" fill={color} fillOpacity="0.22" /><circle cx="7" cy="7" r={7 * r} fill={color} /></svg>
      <span style={{ fontSize: dp(8.5), color }}>{["Breathe in", "Hold", "Breathe out", "Hold"][phase]}</span>
    </div>
  );
}

function Tabs({ current }: { current: DashboardPage }) {
  return (
    <div data-testid="dashboard_page_indicator" className="glass" style={{ display: "flex", alignItems: "center", borderRadius: 99, padding: dp(3) }}>
      {RING.map((p) => (
        <span key={p} style={{ borderRadius: 99, padding: `${dp(4)} ${dp(9)}`, fontSize: dp(8.5), fontWeight: p === current ? 600 : 500, background: p === current ? "rgba(255,255,255,0.85)" : "transparent", color: p === current ? "#000" : C.text2, whiteSpace: "nowrap" }}>{PAGE_INFO[p].tab}</span>
      ))}
      <span style={{ margin: `0 ${dp(4)}`, width: dp(0.8), height: dp(14), background: "rgba(255,255,255,0.3)" }} />
      <span style={{ width: dp(22), height: dp(22), borderRadius: 99, display: "grid", placeItems: "center", background: current === "DRILLS" ? C.red : "#000", fontSize: dp(13), fontWeight: 300 }}>+</span>
    </div>
  );
}

function GestureOrnament({ s }: { s: HudState }) {
  const a = s.gesture.activePose;
  const items: [string, string, boolean][] = [
    ["✋", "Palm", a === "OPEN_PALM"], ["↔", "Swipe", false], ["☝", "Lock", a === "POINTING_UP"],
    ["👍", "Thumb", a === "THUMB_UP"], ["✌", "V", a === "VICTORY"], ["✊", "Fist", a === "CLOSED_FIST"],
  ];
  return (
    <div data-testid="gesture_ornament" className="glass" style={{ borderRadius: 99, padding: `${dp(8)} ${dp(5)}`, display: "flex", flexDirection: "column", gap: dp(6), alignItems: "center" }}>
      {items.map(([g, l, on]) => (
        <div key={l} className="col" style={{ alignItems: "center" }}>
          <span style={{ width: dp(22), height: dp(22), borderRadius: 99, display: "grid", placeItems: "center", fontSize: dp(11), background: on ? "#fff" : "rgba(255,255,255,0.12)", filter: on ? "none" : "grayscale(1)" }}>{g}</span>
          <span style={{ fontSize: dp(6), color: on ? "#fff" : C.text3 }}>{l}</span>
        </div>
      ))}
    </div>
  );
}

