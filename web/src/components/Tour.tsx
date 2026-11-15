// Guided first-run tour: each step completes itself when the HUD sees it happen.
"use client";
import { useEffect, useRef, useState } from "react";
import type { HudState } from "@/lib/hud";
import { isOpen } from "@/lib/hud";

interface Step { what: string; how: string; alt: string; done: (s: HudState, start: HudState) => boolean }

const STEPS: Step[] = [
  { what: "Open the dashboard", how: "Hold an open palm up to the camera", alt: "or press Space", done: (s) => isOpen(s) },
  { what: "Look around", how: "Turn your head, or swipe an open hand sideways", alt: "or press → / drag the view", done: (s, a) => isOpen(s) && s.currentPage !== a.currentPage },
  { what: "Start a drill", how: "Say “emergency one”", alt: "or press 1", done: (s) => s.activeDrill.number !== 0 },
  { what: "Acknowledge the alert", how: "Thumbs up", alt: "or press A", done: (s) => s.alertAcknowledged },
  { what: "End the drill", how: "Say “end all emergencies”", alt: "or press X", done: (s) => s.activeDrill.number === 0 },
  { what: "Close the dashboard", how: "Make a fist and hold it", alt: "or press Space", done: (s) => !isOpen(s) },
];

export function Tour({ s, onClose }: { s: HudState; onClose: () => void }) {
  const compact = isOpen(s);
  const [i, setI] = useState(0);
  const start = useRef<HudState>(s);
  const latest = useRef<HudState>(s);
  latest.current = s;
  // The HUD state changes many times a second, so the short "done" pause is keyed on the step only.
  const done = i < STEPS.length && STEPS[i].done(s, start.current);
  useEffect(() => {
    if (!done) return;
    const t = setTimeout(() => { start.current = latest.current; setI((v) => v + 1); }, 450);
    return () => clearTimeout(t);
  }, [done, i]);
  const finished = i >= STEPS.length;
  if (compact) {
    // One line while the dashboard window is open, so it never covers the window on phones.
    return (
      <div className="tour compact" data-testid="tour" onPointerDown={(e) => e.stopPropagation()}>
        <span className="what">{finished ? "Tour complete" : `${i + 1}/${STEPS.length} · ${STEPS[i].what}`}</span>
        {!finished ? <span className="alt"> · {STEPS[i].alt.replace(/^or /, "")}</span> : null}
      </div>
    );
  }
  return (
    <div className="tour" data-testid="tour" onPointerDown={(e) => e.stopPropagation()}>
      <div className="step">{finished ? "Tour complete" : `Step ${i + 1} of ${STEPS.length}`}</div>
      <div className="what">{finished ? "You're ready." : STEPS[i].what}</div>
      <div>{finished ? "Explore freely. Open Controls (bottom left) for every action." : STEPS[i].how}</div>
      {!finished ? <div className="alt">{STEPS[i].alt}</div> : null}
      <div className="bar">{STEPS.map((_, k) => <span key={k} className={k < i ? "done" : k === i ? "now" : ""} />)}</div>
      <div style={{ display: "flex", justifyContent: "space-between", marginTop: 6 }}>
        {!finished ? <button className="linkbtn" onClick={() => { start.current = s; setI(i + 1); }}>Skip step</button> : <span />}
        <button className="linkbtn" onClick={onClose}>{finished ? "Close" : "End tour"}</button>
      </div>
    </div>
  );
}
