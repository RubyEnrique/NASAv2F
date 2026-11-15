// Shared HUD primitives (ports of BentoCard, BigValue, SmallNote, StatusPill, HazardBadge, LoadGauge…).
import type { CSSProperties, ReactNode } from "react";
import type { CognitiveLevel } from "@/lib/mental";

export const dp = (n: number) => `calc(var(--dp) * ${n})`;
export const C = { red: "#ff453a", yellow: "#ffd60a", green: "#30d158", label: "#ffffff", text2: "rgba(255,255,255,0.68)", text3: "rgba(255,255,255,0.45)" };

export const levelColor = (l: CognitiveLevel) => (l === "NOMINAL" ? C.green : l === "CAUTION" ? C.yellow : C.red);

export const fmt1 = (v: number) => v.toFixed(1);
export const fmt0 = (v: number) => Math.round(v).toString();
export function formatDuration(sec: number) {
  const s = Math.max(0, Math.trunc(sec));
  return s >= 3600 ? `${Math.trunc(s / 3600)}:${String(Math.trunc((s % 3600) / 60)).padStart(2, "0")}h` : `${String(Math.trunc(s / 60)).padStart(2, "0")}:${String(s % 60).padStart(2, "0")}`;
}
export function formatMinutes(min: number) {
  const m = Math.max(0, Math.trunc(min));
  return m >= 60 ? `${Math.trunc(m / 60)}h ${String(m % 60).padStart(2, "0")}m` : `${m}m`;
}
export const cap = (s: string) => s.toLowerCase().replace(/^./, (c) => c.toUpperCase());

export function Card({ title, tint, style, children, testId }: { title: string; tint?: string | null; style?: CSSProperties; children?: ReactNode; testId?: string }) {
  return (
    <div className="card" data-testid={testId} style={{ ...(tint ? { backgroundImage: `linear-gradient(135deg, ${tint}42, ${tint}10)` } : {}), ...style }}>
      <div className="card-title">{title}</div>
      {children}
    </div>
  );
}

export function Big({ value, unit, color = C.label, size = 20, testId }: { value: string; unit?: string | null; color?: string; size?: number; testId?: string }) {
  return (
    <div style={{ display: "flex", alignItems: "baseline", minWidth: 0 }}>
      <span className="big" data-testid={testId} style={{ fontSize: dp(size), color }}>{value}</span>
      {unit ? <span className="unit">{unit}</span> : null}
    </div>
  );
}

export function Small({ children, color, lines = 1, testId }: { children: ReactNode; color?: string; lines?: number; testId?: string }) {
  return <div className="small" data-testid={testId} style={{ color, WebkitLineClamp: lines }}>{children}</div>;
}

export function Pill({ text, color, testId }: { text: string; color: string; testId?: string }) {
  return <span className="pill" data-testid={testId} style={{ background: `${color}33`, color }}>{text}</span>;
}

export function Badge({ hazard, size = 20 }: { hazard: number; size?: number }) {
  return <span className="badge" style={{ width: dp(size), height: dp(size), borderRadius: dp(size * 0.28), fontSize: dp(size * 0.55) }}>{hazard === 0 ? "D" : hazard}</span>;
}

export function Dot({ color, size = 8, className }: { color: string; size?: number; className?: string }) {
  return <span className={className} style={{ display: "inline-block", width: dp(size), height: dp(size), borderRadius: "50%", background: color, flex: "none" }} />;
}

export function LevelBar({ frac, color, height = 3 }: { frac: number; color: string; height?: number }) {
  return (
    <div style={{ height: dp(height), borderRadius: 99, background: "rgba(255,255,255,0.14)", overflow: "hidden" }}>
      <div style={{ width: `${Math.max(0, Math.min(1, frac)) * 100}%`, height: "100%", background: color, borderRadius: 99, transition: "width .3s" }} />
    </div>
  );
}

/** 270° gauge (LoadGauge.kt) with an optional forecast dot. */
export function Gauge({ value, color, stroke = 6, forecast, size = 70 }: { value: number; color: string; stroke?: number; forecast?: number | null; size?: number }) {
  const r = 50 - stroke / 2 - 1;
  const arc = (from: number, sweep: number) => {
    const a0 = (from * Math.PI) / 180, a1 = ((from + sweep) * Math.PI) / 180;
    const x0 = 50 + r * Math.cos(a0), y0 = 50 + r * Math.sin(a0), x1 = 50 + r * Math.cos(a1), y1 = 50 + r * Math.sin(a1);
    return `M ${x0} ${y0} A ${r} ${r} 0 ${sweep > 180 ? 1 : 0} 1 ${x1} ${y1}`;
  };
  const v = Math.max(0, Math.min(100, value));
  const fa = forecast != null ? ((135 + 270 * Math.max(0, Math.min(100, forecast)) / 100) * Math.PI) / 180 : null;
  return (
    <svg viewBox="0 0 100 100" style={{ width: dp(size), height: dp(size), position: "absolute", inset: 0 }}>
      <path d={arc(135, 270)} stroke="rgba(120,120,128,0.36)" strokeWidth={stroke} fill="none" strokeLinecap="round" />
      {v > 0.5 ? <path d={arc(135, 270 * (v / 100))} stroke={color} strokeWidth={stroke} fill="none" strokeLinecap="round" style={{ transition: "d .4s" }} /> : null}
      {fa != null ? <circle cx={50 + r * Math.cos(fa)} cy={50 + r * Math.sin(fa)} r={stroke * 0.32} fill="#fff" /> : null}
    </svg>
  );
}

/** Activity ring (gravity page). */
export function Ring({ frac, color, inset, stroke }: { frac: number; color: string; inset: number; stroke: number }) {
  const r = 50 - inset - stroke / 2;
  const c = 2 * Math.PI * r;
  return (
    <g>
      <circle cx="50" cy="50" r={r} stroke={`${color}`} strokeOpacity={0.2} strokeWidth={stroke} fill="none" />
      <circle cx="50" cy="50" r={r} stroke={color} strokeWidth={stroke} fill="none" strokeLinecap="round" strokeDasharray={`${c * Math.max(0.001, Math.min(1, frac))} ${c}`} transform="rotate(-90 50 50)" />
    </g>
  );
}

export function TrendChart({ history, projected, color }: { history: number[]; projected: number | null; color: string }) {
  const pts = history.length >= 2 ? history : [history[0] ?? 0, history[0] ?? 0];
  const n = pts.length;
  const W = 200, H = 60, padR = projected != null ? 40 : 0;
  const x = (i: number) => (i / Math.max(1, n - 1)) * (W - padR);
  const y = (v: number) => H - (Math.max(0, Math.min(100, v)) / 100) * H;
  const d = pts.map((v, i) => `${i ? "L" : "M"} ${x(i).toFixed(1)} ${y(v).toFixed(1)}`).join(" ");
  return (
    <svg viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" style={{ width: "100%", flex: 1, minHeight: 0 }} data-testid="cognitive_trend_chart">
      <line x1="0" x2={W} y1={y(70)} y2={y(70)} stroke={C.yellow} strokeOpacity="0.45" strokeDasharray="3 3" strokeWidth="0.8" vectorEffect="non-scaling-stroke" />
      <path d={d} stroke={color} strokeWidth="1.8" fill="none" vectorEffect="non-scaling-stroke" strokeLinejoin="round" />
      {projected != null ? <line x1={x(n - 1)} y1={y(pts[n - 1])} x2={W} y2={y(projected)} stroke="#fff" strokeOpacity="0.7" strokeDasharray="3 3" strokeWidth="1.4" vectorEffect="non-scaling-stroke" /> : null}
    </svg>
  );
}
