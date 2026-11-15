// On-screen fallback for every gesture and voice command, plus demo controls. Judges never get stuck
// if lighting, the mic or the browser gets in the way.
"use client";
import { useState } from "react";
import type { HudEngine, HudState } from "@/lib/hud";
import { isOpen } from "@/lib/hud";
import { DRILLS } from "@/lib/mental";
import { PAGE_INFO, RING } from "@/lib/spatial";
import { parseVoiceCommand } from "@/lib/voiceCommands";

export function Controls({ e, s, onSimSos, onOpenCrewTab, room, onRestartTour }: { e: HudEngine; s: HudState; onSimSos: () => void; onOpenCrewTab: () => void; room: string; onRestartTour: () => void }) {
  const [open, setOpen] = useState(false);
  const [text, setText] = useState("");
  const [msg, setMsg] = useState("");
  const send = () => {
    const c = parseVoiceCommand(text);
    if (c) { e.onVoiceCommand(c); setMsg(`Ran: ${text}`); setText(""); }
    else setMsg("Not a command. Try “emergency one”.");
  };
  const o = isOpen(s);
  return (
    <div className="ctl" onPointerDown={(ev) => ev.stopPropagation()}>
      {open ? (
        <div className="ctl-panel" data-testid="controls_panel">
          <h4>Dashboard</h4>
          <div className="ctl-grid">
            <button className={`chip ${o ? "on" : ""}`} onClick={() => e.performAction(o ? "CLOSE_DASHBOARD" : "OPEN_DASHBOARD")}>{o ? "Close" : "Open"}</button>
            <button className="chip" onClick={() => e.performAction("PREVIOUS_TILE")} disabled={!o}>◀ Prev</button>
            <button className="chip" onClick={() => e.performAction("NEXT_TILE")} disabled={!o}>Next ▶</button>
            <button className={`chip ${s.centerLocked ? "on" : ""}`} onClick={() => e.performAction("TOGGLE_CENTER_LOCK")}>Lock</button>
          </div>
          <div className="ctl-grid five">
            {RING.map((p) => (
              <button key={p} className={`chip ${o && s.currentPage === p ? "on" : ""}`} onClick={() => { if (!o) e.openDashboard("PERMANENT"); e.selectPage(p); }}>{PAGE_INFO[p].tab.slice(0, 5)}</button>
            ))}
          </div>
          <h4>Drills</h4>
          <div className="ctl-grid five">
            {DRILLS.slice(1).map((d) => (
              <button key={d.number} title={d.title} className={`chip ${s.activeDrill.number === d.number ? "on" : ""}`} onClick={() => e.setDrill(d)}>E{d.number}</button>
            ))}
          </div>
          <div className="ctl-grid">
            <button className="chip" onClick={() => e.performAction("ACKNOWLEDGE")}>👍 Ack</button>
            <button className="chip" onClick={() => e.endAllEmergencies()}>All clear</button>
            <button className="chip" onClick={() => e.performAction("TOGGLE_EYE_PREVIEW")}>Eyes</button>
            <button className="chip" onClick={() => { if (!o) e.openDashboard("PERMANENT"); e.selectPage("DRILLS"); }}>List</button>
          </div>
          <h4>Type a voice command</h4>
          <input value={text} placeholder="e.g. emergency three" onChange={(ev) => setText(ev.target.value)} onKeyDown={(ev) => { ev.stopPropagation(); if (ev.key === "Enter") send(); }} data-testid="voice_text_input" />
          {msg ? <div className="kbd">{msg}</div> : null}
          <h4>Crew demo</h4>
          <div className="ctl-grid">
            <button className="chip red" onClick={onSimSos}>Crew SOS</button>
            <button className="chip" onClick={onOpenCrewTab} title="Opens a second HUD in a new tab; both share the crew room">2nd HUD</button>
            <button className="chip" onClick={onRestartTour}>Tour</button>
            <button className="chip" onClick={() => document.documentElement.requestFullscreen?.().catch(() => {})}>Full</button>
          </div>
          <div className="kbd">Room {room}. Keys: Space open/close · ← → pages · ↓ drills · A ack · L lock · 1–9, 0 drills · X all clear · drag to look around.</div>
        </div>
      ) : null}
      <button className="ctl-toggle" onClick={() => setOpen(!open)} data-testid="controls_toggle">{open ? "Hide controls" : "Controls"}</button>
    </div>
  );
}
