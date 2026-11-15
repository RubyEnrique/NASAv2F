// Entry: a one-tap start screen (browsers only grant camera / mic / motion after a tap), then the HUD.
"use client";
import { useEffect, useMemo, useRef, useState, useSyncExternalStore } from "react";
import { HudEngine, isOpen } from "@/lib/hud";
import { drillByNumber } from "@/lib/mental";
import { parseDonkiTime, type SpaceWeatherEvent } from "@/lib/nasa";
import { openCamera, VisionLoop, type CameraInfo } from "@/runtime/vision";
import { requestMotionPermission, startBattery, startGps, startOrientation, startSteps } from "@/runtime/sensors";
import { Announcer, VoiceControl } from "@/runtime/voice";
import { randomCrewId, SimulatedCrew, TabRoomTransport } from "@/runtime/crewLink";
import { AlertBanner, Compass, GestureChip, GlanceStrip, LoadHalo, MicMeter } from "./Ambient";
import { SpatialDashboard } from "./Dashboard";
import { Controls } from "./Controls";
import { Tour } from "./Tour";
import { dp } from "./ui";

type Phase = "start" | "loading" | "running";

function getRoom(): string {
  const u = new URL(window.location.href);
  let r = u.searchParams.get("room");
  if (!r) {
    r = randomCrewId();
    u.searchParams.set("room", r);
    window.history.replaceState(null, "", u.toString());
  }
  return r.toUpperCase().slice(0, 12);
}

function crewIdentity(): [string, string] {
  // sessionStorage: each tab is its own crew member, so a 2nd tab can join the same room.
  let id: string | null = null;
  try { id = sessionStorage.getItem("crew-id"); } catch { /* storage blocked */ }
  if (!id) {
    id = randomCrewId();
    try { sessionStorage.setItem("crew-id", id); } catch { /* ignore */ }
  }
  return [id, `Crew-${id}`];
}

export default function App() {
  const engine = useMemo(() => new HudEngine(), []);
  const s = useSyncExternalStore(engine.subscribe, engine.getState, engine.getState);
  const [phase, setPhase] = useState<Phase>("start");
  const [progress, setProgress] = useState("");
  const [err, setErr] = useState<string | null>(null);
  const [cam, setCam] = useState<CameraInfo | null>(null);
  const [tour, setTour] = useState(true);
  const [tourKey, setTourKey] = useState(0);
  const [demoPos, setDemoPos] = useState(false);
  const [room, setRoom] = useState("");
  const videoRef = useRef<HTMLVideoElement>(null);
  const cleanups = useRef<(() => void)[]>([]);
  const sim = useRef<SimulatedCrew | null>(null);
  const voice = useRef<VoiceControl | null>(null);

  useEffect(() => () => cleanups.current.forEach((f) => f()), []);

  async function start(withCamera: boolean) {
    setErr(null);
    setPhase("loading");
    const motionOk = await requestMotionPermission(); // must run inside the tap on iOS
    let stream: MediaStream | null = null;
    let info: CameraInfo | null = null;
    if (withCamera) {
      setProgress("Asking for the camera…");
      try {
        info = await openCamera();
        stream = info.stream;
        setCam(info);
        engine.setCameraPermission(true);
      } catch {
        setErr("Camera blocked or not available. The HUD still works with the on-screen controls.");
      }
    }
    // Microphone (separate so a refusal does not block the camera).
    voice.current = new VoiceControl({
      onCommand: (c) => engine.onVoiceCommand(c),
      onStatus: (m) => engine.setMic(m),
    });
    voice.current.start().catch(() => {});
    cleanups.current.push(() => voice.current?.stop());

    const announcer = new Announcer((speaking) => voice.current?.setMuted(speaking));
    cleanups.current.push(engine.onEvent((ev) => {
      if (ev.kind === "speak") announcer.speak(ev.text);
      else if (ev.kind === "stopSpeaking") announcer.stop();
      else if (ev.kind === "sensitivity") voice.current?.adjustSensitivity(ev.delta);
    }));

    // Sensors.
    if (motionOk) {
      cleanups.current.push(startOrientation({ onHeading: (d) => headingQ.current = d, onPitch: (d) => pitchQ.current = d, onAvailable: () => engine.setHeadTracking(true) }));
      cleanups.current.push(startSteps(() => engine.onStep()));
    }
    cleanups.current.push(startGps({
      onSample: (de, dn, sp, acc) => engine.onLocationSample(de, dn, sp, acc),
      onAbsoluteFix: (lat, lon, acc) => { realFix.current = true; engine.onAbsoluteFix(lat, lon, acc); },
      onPermission: (g) => engine.setLocationPermission(g),
    }));
    startBattery((p) => engine.setBattery(p)).then((f) => cleanups.current.push(f));

    // Crew: this browser's room (other tabs) + simulated crewmates.
    const [id, name] = crewIdentity();
    engine.setCrewIdentity(id, name);
    const r = getRoom();
    setRoom(r);
    const tabs = new TabRoomTransport(r, (st) => { engine.onCrewPacket(st); heard.current++; });
    tabs.start();
    sim.current = new SimulatedCrew(() => {
      const [lat, lon] = engine.selfPosition();
      return lat != null && lon != null ? [lat, lon] : null;
    }, (st, simulated) => engine.onCrewPacket(st, simulated));
    engine.setCrewLink({ active: true, mode: "tab room + demo crew", room: r });
    let sent = 0;
    const crewTimer = setInterval(() => {
      tabs.send(engine.crewOutbound());
      sent++;
      sim.current?.tick();
      engine.setCrewLink({ sent, heardOthers: heard.current });
    }, 1000);
    cleanups.current.push(() => { clearInterval(crewTimer); tabs.stop(); });

    // Demo position: indoors / laptops often have no GPS; place the wearer at Jezero so the
    // compass and map can show crewmates. Replaced by the real fix as soon as one arrives.
    const demoTimer = setTimeout(() => {
      if (!realFix.current) { engine.onAbsoluteFix(18.38, 77.58, 0); setDemoPos(true); }
    }, 6000);
    cleanups.current.push(() => clearTimeout(demoTimer));

    // NASA DONKI space weather via our server route (keeps the API key off the client).
    fetch("/api/space-weather").then((res) => res.ok ? res.json() : null).then((j: { events: { type: "FLR" | "SEP"; time: string; classType: string | null }[]; fetchedAt: number } | null) => {
      if (!j) return;
      const ev: SpaceWeatherEvent[] = j.events.flatMap((x) => { const t = parseDonkiTime(x.time); return t ? [{ type: x.type, timeMs: t, classType: x.classType }] : []; });
      engine.onSpaceWeather(ev, j.fetchedAt);
    }).catch(() => {});

    // Simulation clock.
    const tick = setInterval(() => engine.tick(), 1000);
    cleanups.current.push(() => clearInterval(tick));

    // Hand + eye tracking.
    if (stream && info && videoRef.current) {
      const v = videoRef.current;
      v.srcObject = stream;
      await v.play().catch(() => {});
      const wantEyes = info.facing === "user";
      const loop = new VisionLoop(v, info.mirrored, wantEyes, {
        onHands: (h, t) => engine.onHands(h, t),
        onEye: (smp) => engine.onEyeSample(smp),
        onEyeStatus: (st) => engine.setEyeTrackingStatus(st),
      });
      try {
        await loop.init(setProgress);
        loop.start();
        cleanups.current.push(() => loop.stop());
      } catch {
        setErr("Hand tracking could not start on this device. Use the on-screen controls or keyboard.");
      }
    }
    setPhase("running");
  }

  // Orientation events can fire at 60+ Hz; apply them once per animation frame.
  const headingQ = useRef<number | null>(null);
  const pitchQ = useRef<number | null>(null);
  const realFix = useRef(false);
  const heard = useRef(0);
  useEffect(() => {
    let raf = 0;
    const f = () => {
      if (headingQ.current != null) { engine.onHeading(headingQ.current); headingQ.current = null; }
      if (pitchQ.current != null) { engine.onPitch(pitchQ.current); pitchQ.current = null; }
      raf = requestAnimationFrame(f);
    };
    f();
    return () => cancelAnimationFrame(raf);
  }, [engine]);

  // Keyboard fallback.
  useEffect(() => {
    if (phase !== "running") return;
    const k = (ev: KeyboardEvent) => {
      if ((ev.target as HTMLElement)?.tagName === "INPUT") return;
      const st = engine.getState();
      if (ev.key === " ") { ev.preventDefault(); engine.performAction(isOpen(st) ? "CLOSE_DASHBOARD" : "OPEN_DASHBOARD"); }
      else if (ev.key === "ArrowRight") { if (!isOpen(st)) engine.openDashboard("PERMANENT"); engine.performAction("NEXT_TILE"); }
      else if (ev.key === "ArrowLeft") { if (!isOpen(st)) engine.openDashboard("PERMANENT"); engine.performAction("PREVIOUS_TILE"); }
      else if (ev.key === "ArrowDown") { if (!isOpen(st)) engine.openDashboard("PERMANENT"); engine.selectPage("DRILLS"); }
      else if (ev.key === "ArrowUp") { if (isOpen(st) && st.currentPage === "DRILLS") engine.selectPage("ISOLATION"); }
      else if (ev.key === "a" || ev.key === "A") engine.performAction("ACKNOWLEDGE");
      else if (ev.key === "l" || ev.key === "L") engine.performAction("TOGGLE_CENTER_LOCK");
      else if (ev.key === "x" || ev.key === "X" || ev.key === "Escape") engine.endAllEmergencies();
      else if (/^[0-9]$/.test(ev.key)) { const d = drillByNumber(ev.key === "0" ? 10 : Number(ev.key)); if (d) engine.setDrill(d); }
    };
    window.addEventListener("keydown", k);
    return () => window.removeEventListener("keydown", k);
  }, [phase, engine]);

  // Drag to look around when there is no compass (laptops).
  const drag = useRef<{ x: number; y: number; h: number; p: number } | null>(null);
  const onDown = (ev: React.PointerEvent) => {
    if (s.hasHeadTracking) return;
    drag.current = { x: ev.clientX, y: ev.clientY, h: s.headingDegrees, p: s.pitchDegrees };
  };
  const onMove = (ev: React.PointerEvent) => {
    const d = drag.current;
    if (!d) return;
    const degPerPx = 66 / window.innerWidth;
    engine.onHeading(d.h - (ev.clientX - d.x) * degPerPx);
    engine.onPitch(Math.max(-60, Math.min(40, d.p + (ev.clientY - d.y) * degPerPx)));
  };
  const onUp = () => { drag.current = null; };

  const openSecondTab = () => window.open(window.location.href, "_blank");

  return (
    <>
      <div className="hud" onPointerDown={onDown} onPointerMove={onMove} onPointerUp={onUp} onPointerCancel={onUp}>
        <video ref={videoRef} className={`cam ${cam?.mirrored ? "mirror" : ""} ${isOpen(s) ? "blur" : ""}`} playsInline muted autoPlay />
        <div className="vignette" />
        {phase === "running" ? (
          <div className="pad">
            <LoadHalo s={s} alpha={isOpen(s) ? 0.35 : 1} />
            <div style={{ position: "absolute", left: dp(14), top: "50%", transform: "translateY(-50%)", opacity: isOpen(s) ? 0 : 1, transition: "opacity .26s", pointerEvents: "none" }}><GlanceStrip s={s} /></div>
            <SpatialDashboard s={s} eyeVideo={cam?.facing === "user" ? cam.stream : null} />
            <div style={{ position: "absolute", top: dp(6), left: "50%", transform: "translateX(-50%)", pointerEvents: "none" }}><Compass s={s} /></div>
            <div style={{ position: "absolute", top: dp(8), right: dp(12), display: "flex", flexDirection: "column", alignItems: "flex-end", gap: dp(6) }}>
              {tour ? <Tour key={tourKey} s={s} onClose={() => setTour(false)} /> : <div style={{ height: dp(56) }} />}
              <div style={{ pointerEvents: "none" }}><AlertBanner s={s} /></div>
            </div>
            <div style={{ position: "absolute", right: dp(14), bottom: dp(12), display: "flex", flexDirection: "column", alignItems: "flex-end", gap: dp(4), pointerEvents: "none" }}>
              <GestureChip s={s} />
              <MicMeter s={s} />
              {demoPos ? <span style={{ fontSize: dp(7), color: "rgba(255,255,255,0.45)" }}>Demo position (no GPS)</span> : null}
            </div>
            <Controls e={engine} s={s} room={room} onSimSos={() => sim.current?.triggerEmergency()} onOpenCrewTab={openSecondTab} onRestartTour={() => { setTour(true); setTourKey((k) => k + 1); }} />
            {err ? <div style={{ position: "absolute", left: "50%", bottom: dp(40), transform: "translateX(-50%)", background: "rgba(0,0,0,0.8)", border: "1px solid rgba(255,69,58,0.6)", borderRadius: 12, padding: "8px 12px", fontSize: 13, maxWidth: "80vw" }} onClick={() => setErr(null)}>{err}</div> : null}
          </div>
        ) : null}
      </div>
      {phase !== "running" ? <Start phase={phase} progress={progress} err={err} onStart={start} /> : null}
      <div className={`rotate ${phase === "running" ? "show" : ""}`}>
        <div>
          <div style={{ fontSize: 40 }}>⟲</div>
          <p style={{ fontSize: 16 }}>Turn your phone sideways (landscape).<br />The HUD is built for a head mount.</p>
          <button className="btn secondary" onClick={(e) => ((e.currentTarget.closest(".rotate") as HTMLElement).style.display = "none")}>Continue in portrait</button>
        </div>
      </div>
    </>
  );
}

function Start({ phase, progress, err, onStart }: { phase: Phase; progress: string; err: string | null; onStart: (cam: boolean) => void }) {
  const loading = phase === "loading";
  return (
    <div className="start" data-testid="start_screen">
      <div className="start-card">
        <h1>Spatial HUD</h1>
        <p>A heads-up display for a Mars EVA. Your camera is the visor; you control it with your hands and voice. Everything is simulated for training.</p>
        <ul className="checks">
          <li><span className="dot" /> <span><b>Camera</b> — the view and hand gestures</span></li>
          <li><span className="dot" /> <span><b>Microphone</b> — voice commands (processed on this device)</span></li>
          <li><span className="dot" /> <span><b>Location & motion</b> — optional: map, distance, head-turn pages</span></li>
        </ul>
        <div className="row">
          <button className="btn" disabled={loading} onClick={() => onStart(true)} data-testid="start_button">{loading ? "Starting…" : "Start"}</button>
          <button className="btn secondary" disabled={loading} onClick={() => onStart(false)} data-testid="start_no_camera">Start without camera</button>
        </div>
        {loading ? <div className="note">{progress || "Loading…"}</div> : null}
        {err ? <div className="err">{err}</div> : null}
        <p className="note">Video and audio never leave this device. First start downloads ~35 MB of models (voice adds ~42 MB in the background); later visits load from cache. Best in Chrome, Edge or Safari. On a phone, turn it sideways.</p>
      </div>
    </div>
  );
}
