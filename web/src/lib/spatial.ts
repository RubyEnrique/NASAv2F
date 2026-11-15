// Port of hud/SpatialLayout.kt and the DashboardPage ring.

export type DashboardPage = "RADIATION" | "ISOLATION" | "DISTANCE" | "GRAVITY" | "ENVIRONMENT" | "DRILLS";

export const PAGE_INFO: Record<DashboardPage, { hazard: number; title: string; focus: string; tab: string }> = {
  RADIATION: { hazard: 1, title: "Space Radiation", focus: "Mind · protect cognition", tab: "Radiation" },
  ISOLATION: { hazard: 2, title: "Isolation & Confinement", focus: "Mind · load, sleep, rhythm", tab: "Isolation" },
  DISTANCE: { hazard: 3, title: "Distance from Earth", focus: "Mind · self-sufficiency", tab: "Distance" },
  GRAVITY: { hazard: 4, title: "Gravity", focus: "Body · prevent atrophy", tab: "Gravity" },
  ENVIRONMENT: { hazard: 5, title: "Hostile Environment", focus: "Live · habitability & map", tab: "Environment" },
  DRILLS: { hazard: 0, title: "Drills", focus: "Emergency training", tab: "Drills" },
};

export const RING: DashboardPage[] = ["RADIATION", "ISOLATION", "DISTANCE", "GRAVITY", "ENVIRONMENT"];
export const HOME: DashboardPage = "ISOLATION";

export const ringNext = (p: DashboardPage) => { const i = RING.indexOf(p); return i < 0 ? HOME : RING[(i + 1) % RING.length]; };
export const ringPrevious = (p: DashboardPage) => { const i = RING.indexOf(p); return i < 0 ? HOME : RING[(i - 1 + RING.length) % RING.length]; };

export const TILE_SPACING_DEG = 40;
export const DRILLS_PITCH_OFFSET_DEG = -34;
const FISHEYE = 0.0018;

export function angleDelta(a: number, b: number) {
  let d = (a - b) % 360;
  if (d > 180) d -= 360;
  if (d < -180) d += 360;
  return d;
}

const ringIndex = (p: DashboardPage) => Math.max(0, RING.indexOf(p));
export const anchorYawFor = (p: DashboardPage, heading: number) => (p === "DRILLS" ? heading : heading - ringIndex(p) * TILE_SPACING_DEG);
export const tileYaw = (p: DashboardPage, anchorYaw: number, heading: number) => (p === "DRILLS" ? heading : anchorYaw + ringIndex(p) * TILE_SPACING_DEG);
export const tilePitch = (p: DashboardPage, anchorPitch: number) => (p === "DRILLS" ? anchorPitch + DRILLS_PITCH_OFFSET_DEG : anchorPitch);

export function focusedPage(heading: number, pitch: number, anchorYaw: number, anchorPitch: number): DashboardPage {
  if (pitch - anchorPitch <= DRILLS_PITCH_OFFSET_DEG / 2) return "DRILLS";
  const rel = angleDelta(heading, anchorYaw);
  const idx = Math.min(RING.length - 1, Math.max(0, Math.round(rel / TILE_SPACING_DEG)));
  return RING[idx];
}

export interface Projection { xDeg: number; yDeg: number; scale: number; rotationY: number; rotationX: number; alpha: number; visible: boolean }

export function project(yaw: number, pitchT: number, heading: number, pitch: number): Projection {
  const dx = angleDelta(yaw, heading), dy = pitchT - pitch;
  const r = Math.sqrt(dx * dx + dy * dy);
  const c = Math.max(0.72, 1 - FISHEYE * r);
  return {
    xDeg: dx * c,
    yDeg: dy * c,
    scale: Math.min(1, Math.max(0.62, 1 - 0.0042 * r)),
    rotationY: Math.min(34, Math.max(-34, -dx * 0.42)),
    rotationX: Math.min(22, Math.max(-22, dy * 0.3)),
    alpha: Math.min(1, Math.max(0.28, 1 - (r - 18) / 70)),
    visible: Math.abs(dx) < 95 && Math.abs(dy) < 80,
  };
}
