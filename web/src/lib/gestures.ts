// Port of app/.../gesture/HandGestureClassifier.kt (same thresholds, same behaviour).
// Coordinates are MediaPipe normalised image coords (0..1). For a mirrored front webcam the
// caller flips x first so "swipe right" means the same thing as on the phone's rear camera.

export type DashboardMode = "CLOSED" | "QUICK_VOICE" | "PERMANENT";
export type HandPose = "NONE" | "OPEN_PALM" | "CLOSED_FIST" | "POINTING_UP" | "THUMB_UP" | "THUMB_DOWN" | "VICTORY" | "I_LOVE_YOU";
export type GestureAction =
  | "OPEN_DASHBOARD"
  | "CLOSE_DASHBOARD"
  | "NEXT_TILE"
  | "PREVIOUS_TILE"
  | "TOGGLE_CENTER_LOCK"
  | "ACKNOWLEDGE"
  | "TOGGLE_EYE_PREVIEW"
  | "CLEAR_DRILL";

export const POSE_LABEL: Record<HandPose, string> = {
  NONE: "—",
  OPEN_PALM: "PALM",
  CLOSED_FIST: "FIST",
  POINTING_UP: "POINT",
  THUMB_UP: "THUMB UP",
  THUMB_DOWN: "THUMB DOWN",
  VICTORY: "V SIGN",
  I_LOVE_YOU: "ROCK",
};

export const ACTION_LABEL: Record<GestureAction, string> = {
  OPEN_DASHBOARD: "OPEN",
  CLOSE_DASHBOARD: "CLOSE",
  NEXT_TILE: "NEXT",
  PREVIOUS_TILE: "BACK",
  TOGGLE_CENTER_LOCK: "CENTER LOCK",
  ACKNOWLEDGE: "ACK ALERT",
  TOGGLE_EYE_PREVIEW: "EYE CAM",
  CLEAR_DRILL: "CLEAR DRILL",
};

export function poseFromCategory(name: string | undefined): HandPose {
  switch (name) {
    case "Open_Palm": return "OPEN_PALM";
    case "Closed_Fist": return "CLOSED_FIST";
    case "Pointing_Up": return "POINTING_UP";
    case "Thumb_Up": return "THUMB_UP";
    case "Thumb_Down": return "THUMB_DOWN";
    case "Victory": return "VICTORY";
    case "ILoveYou": return "I_LOVE_YOU";
    default: return "NONE";
  }
}

export interface Pt { x: number; y: number; z: number }
export interface HandObservation { landmarks: Pt[]; pose: HandPose; poseScore: number }

export interface GestureUpdate {
  action: GestureAction | null;
  pendingAction: GestureAction | null;
  holdProgress: number;
  activePose: HandPose;
  mapPinchActive: boolean;
  mapPinchForwardDelta: number;
  mapPinchPanDeltaX: number;
  mapPinchPanDeltaY: number;
}

const update = (p: Partial<GestureUpdate> = {}): GestureUpdate => ({
  action: null,
  pendingAction: null,
  holdProgress: 0,
  activePose: "NONE",
  mapPinchActive: false,
  mapPinchForwardDelta: 0,
  mapPinchPanDeltaX: 0,
  mapPinchPanDeltaY: 0,
  ...p,
});

export const G = {
  MIN_POSE_SCORE: 0.55,
  HOLD_GAP_GRACE_MS: 350,
  HOLD_MAX_TRAVEL: 0.09,
  ACTION_COOLDOWN_MS: 650,
  PALM_CLOSE_HOLD_MS: 1000,
  PINCH_MAX_GAP_RATIO: 0.45,
  PINCH_GAP_RESET_MS: 260,
  PINCH_FILTER_ALPHA: 0.55,
  PINCH_POSITION_ALPHA: 0.65,
  PINCH_FORWARD_DEADZONE: 0.004,
  PINCH_PAN_DEADZONE: 0.002,
  SWIPE_MIN_DELTA_X: 0.11,
  SWIPE_MAX_WINDOW_MS: 750,
  SWIPE_GAP_RESET_MS: 280,
  SWIPE_COOLDOWN_MS: 650,
  SWIPE_REVERSAL_ARM_X: 0.04,
  SWIPE_HORIZONTAL_RATIO: 1.15,
  MIN_PALM_SIZE: 0.03,
} as const;

const WRIST = 0, THUMB_TIP = 4, INDEX_MCP = 5, INDEX_PIP = 6, INDEX_TIP = 8;
const MIDDLE_MCP = 9, MIDDLE_PIP = 10, MIDDLE_TIP = 12, RING_MCP = 13, RING_PIP = 14, RING_TIP = 16;
const PINKY_MCP = 17, PINKY_PIP = 18, PINKY_TIP = 20;

const clamp = (v: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, v));
const dist2D = (a: Pt, b: Pt) => Math.hypot(a.x - b.x, a.y - b.y);
const centerX = (l: Pt[]) => (l[WRIST].x + l[MIDDLE_MCP].x) * 0.5;
const centerY = (l: Pt[]) => (l[WRIST].y + l[MIDDLE_MCP].y) * 0.5;
const pinchX = (l: Pt[]) => (l[THUMB_TIP].x + l[INDEX_TIP].x) * 0.5;
const pinchY = (l: Pt[]) => (l[THUMB_TIP].y + l[INDEX_TIP].y) * 0.5;
const pinchZ = (l: Pt[]) => (l[THUMB_TIP].z + l[INDEX_TIP].z) * 0.5;
const palmScale = (l: Pt[]) => Math.max(dist2D(l[WRIST], l[MIDDLE_MCP]), dist2D(l[INDEX_MCP], l[PINKY_MCP]));

function extendedAnyAngle(w: Pt, mcp: Pt, pip: Pt, tip: Pt) {
  const wt = dist2D(w, tip);
  return wt > dist2D(w, mcp) * 1.15 && wt > dist2D(w, pip) * 1.02;
}
function extendedUpright(w: Pt, mcp: Pt, pip: Pt, tip: Pt) {
  const wt = dist2D(w, tip);
  return wt > dist2D(w, pip) * 1.1 && wt > dist2D(w, mcp) * 1.28 && tip.y < mcp.y;
}
function curledForgiving(w: Pt, mcp: Pt, pip: Pt, tip: Pt, scale: number) {
  const wt = dist2D(w, tip);
  return (wt <= dist2D(w, pip) * 1.22 || dist2D(mcp, tip) <= scale * 1.15) && wt < scale * 1.85;
}

export function isOpenPalmUp(l: Pt[]): boolean {
  if (l.length < 21) return false;
  const w = l[WRIST], m = l[MIDDLE_MCP];
  const palm = dist2D(w, m);
  if (palm < G.MIN_PALM_SIZE) return false;
  if (m.y - w.y > -0.28 * palm) return false;
  const ext =
    extendedUpright(w, l[INDEX_MCP], l[INDEX_PIP], l[INDEX_TIP]) &&
    extendedUpright(w, l[MIDDLE_MCP], l[MIDDLE_PIP], l[MIDDLE_TIP]) &&
    extendedUpright(w, l[RING_MCP], l[RING_PIP], l[RING_TIP]) &&
    extendedUpright(w, l[PINKY_MCP], l[PINKY_PIP], l[PINKY_TIP]);
  if (!ext) return false;
  return dist2D(l[THUMB_TIP], l[PINKY_MCP]) > palm * 0.85 && dist2D(l[THUMB_TIP], l[INDEX_MCP]) > palm * 0.4;
}

export function isClosedFist(l: Pt[]): boolean {
  if (l.length < 21) return false;
  const w = l[WRIST], s = palmScale(l);
  if (s < G.MIN_PALM_SIZE || isOpenPalmUp(l)) return false;
  const curled = [
    curledForgiving(w, l[INDEX_MCP], l[INDEX_PIP], l[INDEX_TIP], s),
    curledForgiving(w, l[MIDDLE_MCP], l[MIDDLE_PIP], l[MIDDLE_TIP], s),
    curledForgiving(w, l[RING_MCP], l[RING_PIP], l[RING_TIP], s),
    curledForgiving(w, l[PINKY_MCP], l[PINKY_PIP], l[PINKY_TIP], s),
  ].filter(Boolean).length;
  return curled >= 4;
}

export function isPointing(l: Pt[]): boolean {
  if (l.length < 21) return false;
  const w = l[WRIST], s = palmScale(l);
  if (s < G.MIN_PALM_SIZE) return false;
  if (!extendedAnyAngle(w, l[INDEX_MCP], l[INDEX_PIP], l[INDEX_TIP])) return false;
  const others = [
    curledForgiving(w, l[MIDDLE_MCP], l[MIDDLE_PIP], l[MIDDLE_TIP], s),
    curledForgiving(w, l[RING_MCP], l[RING_PIP], l[RING_TIP], s),
    curledForgiving(w, l[PINKY_MCP], l[PINKY_PIP], l[PINKY_TIP], s),
  ].filter(Boolean).length;
  return others >= 2;
}

export function isSwipeOpenHand(l: Pt[]): boolean {
  if (l.length < 21) return false;
  const w = l[WRIST];
  if (palmScale(l) < G.MIN_PALM_SIZE || isClosedFist(l)) return false;
  const ext = [
    extendedAnyAngle(w, l[INDEX_MCP], l[INDEX_PIP], l[INDEX_TIP]),
    extendedAnyAngle(w, l[MIDDLE_MCP], l[MIDDLE_PIP], l[MIDDLE_TIP]),
    extendedAnyAngle(w, l[RING_MCP], l[RING_PIP], l[RING_TIP]),
    extendedAnyAngle(w, l[PINKY_MCP], l[PINKY_PIP], l[PINKY_TIP]),
  ].filter(Boolean).length;
  return ext >= 3;
}

export function isThumbIndexPinch(l: Pt[]): boolean {
  if (l.length < 21) return false;
  const s = palmScale(l);
  if (s < G.MIN_PALM_SIZE || isClosedFist(l) || isOpenPalmUp(l)) return false;
  if (dist2D(l[THUMB_TIP], l[INDEX_TIP]) > s * G.PINCH_MAX_GAP_RATIO) return false;
  const w = l[WRIST];
  const curled = [
    curledForgiving(w, l[MIDDLE_MCP], l[MIDDLE_PIP], l[MIDDLE_TIP], s),
    curledForgiving(w, l[RING_MCP], l[RING_PIP], l[RING_TIP], s),
    curledForgiving(w, l[PINKY_MCP], l[PINKY_PIP], l[PINKY_TIP], s),
  ].filter(Boolean).length;
  return curled <= 2;
}

export function resolvePose(o: HandObservation): HandPose {
  if (o.pose !== "NONE" && o.poseScore >= G.MIN_POSE_SCORE) return o.pose;
  const l = o.landmarks;
  if (isOpenPalmUp(l)) return "OPEN_PALM";
  if (isPointing(l)) return "POINTING_UP";
  if (isClosedFist(l)) return "CLOSED_FIST";
  return "NONE";
}

function actionFor(pose: HandPose, mode: DashboardMode): GestureAction | null {
  const open = mode !== "CLOSED";
  switch (pose) {
    case "OPEN_PALM": return open ? "CLOSE_DASHBOARD" : "OPEN_DASHBOARD";
    case "CLOSED_FIST": return open ? "CLOSE_DASHBOARD" : null;
    case "POINTING_UP": return open ? "TOGGLE_CENTER_LOCK" : null;
    case "THUMB_UP": return "ACKNOWLEDGE";
    case "VICTORY": return open ? "TOGGLE_EYE_PREVIEW" : null;
    case "THUMB_DOWN": return "CLEAR_DRILL";
    default: return null;
  }
}

function holdDurationFor(a: GestureAction): number {
  switch (a) {
    case "ACKNOWLEDGE": return 400;
    case "TOGGLE_CENTER_LOCK": return 700;
    case "CLEAR_DRILL": return 900;
    case "CLOSE_DASHBOARD": return 900;
    default: return 600;
  }
}

export class HandGestureClassifier {
  private heldPose: HandPose = "NONE";
  private holdStartMs = 0;
  private holdLastSeenMs = 0;
  private holdStartX = 0;
  private holdStartY = 0;
  private awaitingReleaseOf: HandPose = "NONE";

  private swipeStartMs: number | null = null;
  private swipeStartX = 0;
  private swipeStartY = 0;
  private swipeLastX = 0;
  private swipeLastSeenMs: number | null = null;
  private swipeCooldownUntilMs = 0;

  private pinchAnchorScale = 0;
  private pinchLastScaleRatio = 1;
  private pinchSmoothedScale = 0;
  private pinchSmoothedZ = 0;
  private pinchSmoothedX = 0;
  private pinchSmoothedY = 0;
  private pinchLastZ = 0;
  private pinchLastX = 0;
  private pinchLastY = 0;
  private pinchLastSeenMs: number | null = null;

  private cooldownUntilMs = 0;

  reset() {
    this.clearHold();
    this.awaitingReleaseOf = "NONE";
    this.clearSwipe();
    this.clearPinch();
    this.swipeCooldownUntilMs = 0;
    this.cooldownUntilMs = 0;
  }

  process(observations: HandObservation[], t: number, mode: DashboardMode, page: string): GestureUpdate {
    const hands = observations.filter((o) => o.landmarks.length >= 21);
    const resolved = hands.map((o) => [o, resolvePose(o)] as const);
    let dominant: (typeof resolved)[number] | null = null;
    let best = -Infinity;
    for (const r of resolved) {
      const score = r[1] === "NONE" ? -1 : r[0].poseScore + 1;
      if (score > best) { best = score; dominant = r; }
    }
    const dominantPose: HandPose = dominant ? dominant[1] : "NONE";

    if (this.awaitingReleaseOf !== "NONE" && !resolved.some((r) => r[1] === this.awaitingReleaseOf)) {
      this.awaitingReleaseOf = "NONE";
    }
    if (t < this.cooldownUntilMs) return update({ activePose: dominantPose });

    const open = mode !== "CLOSED";

    if (open && page === "ENVIRONMENT") {
      const pinch = this.detectMapPinch(resolved.filter((r) => r[1] === "NONE").map((r) => r[0].landmarks), t);
      if (pinch.mapPinchActive) {
        this.clearHold();
        this.clearSwipe();
        return { ...pinch, activePose: "NONE" };
      }
    } else {
      this.clearPinch();
    }

    if (open) {
      const swipeHands = resolved.filter(([o, p]) => p === "OPEN_PALM" || isSwipeOpenHand(o.landmarks)).map((r) => r[0].landmarks);
      const dir = this.detectSwipe(swipeHands, t);
      if (dir) {
        this.clearHold();
        return update({ action: dir === "NEXT" ? "NEXT_TILE" : "PREVIOUS_TILE", activePose: dominantPose });
      }
    } else {
      this.clearSwipe();
    }

    const action = actionFor(dominantPose, mode);
    if (!dominant || !action || dominantPose === this.awaitingReleaseOf) {
      if (t - this.holdLastSeenMs > G.HOLD_GAP_GRACE_MS) this.clearHold();
      return update({ activePose: dominantPose });
    }

    const cx = centerX(dominant[0].landmarks);
    const cy = centerY(dominant[0].landmarks);
    const same =
      this.heldPose === dominantPose &&
      t - this.holdLastSeenMs <= G.HOLD_GAP_GRACE_MS &&
      Math.hypot(cx - this.holdStartX, cy - this.holdStartY) <= G.HOLD_MAX_TRAVEL;
    if (!same) {
      this.heldPose = dominantPose;
      this.holdStartMs = t;
      this.holdStartX = cx;
      this.holdStartY = cy;
    }
    this.holdLastSeenMs = t;

    const needed = dominantPose === "OPEN_PALM" && action === "CLOSE_DASHBOARD" ? G.PALM_CLOSE_HOLD_MS : holdDurationFor(action);
    const progress = clamp((t - this.holdStartMs) / needed, 0, 1);
    if (progress >= 1) {
      this.awaitingReleaseOf = dominantPose;
      this.clearHold();
      this.cooldownUntilMs = t + G.ACTION_COOLDOWN_MS;
      return update({ action, activePose: dominantPose });
    }
    return update({ pendingAction: action, holdProgress: progress, activePose: dominantPose });
  }

  private clearHold() {
    this.heldPose = "NONE";
    this.holdStartMs = 0;
    this.holdLastSeenMs = 0;
  }

  private detectMapPinch(hands: Pt[][], t: number): GestureUpdate {
    const pinchHands = hands.filter((h) => h.length >= 21 && isThumbIndexPinch(h));
    let active: Pt[] | null = null;
    if (pinchHands.length === 1) active = pinchHands[0];
    else if (pinchHands.length > 1 && this.pinchLastSeenMs !== null) {
      active = pinchHands.reduce((a, b) => (Math.abs(pinchX(a) - this.pinchLastX) <= Math.abs(pinchX(b) - this.pinchLastX) ? a : b));
    }
    if (!active) {
      if (this.pinchLastSeenMs === null || t - this.pinchLastSeenMs > G.PINCH_GAP_RESET_MS) this.clearPinch();
      return update();
    }
    const rawScale = palmScale(active), rx = pinchX(active), ry = pinchY(active), rz = pinchZ(active);
    if (this.pinchLastSeenMs === null || t - this.pinchLastSeenMs > G.PINCH_GAP_RESET_MS || this.pinchAnchorScale <= 0) {
      this.pinchAnchorScale = rawScale;
      this.pinchSmoothedScale = rawScale;
      this.pinchSmoothedZ = rz;
      this.pinchSmoothedX = rx;
      this.pinchSmoothedY = ry;
      this.pinchLastScaleRatio = 1;
      this.pinchLastZ = rz;
      this.pinchLastX = rx;
      this.pinchLastY = ry;
      this.pinchLastSeenMs = t;
      return update({ mapPinchActive: true });
    }
    this.pinchSmoothedScale += G.PINCH_FILTER_ALPHA * (rawScale - this.pinchSmoothedScale);
    this.pinchSmoothedZ += G.PINCH_FILTER_ALPHA * (rz - this.pinchSmoothedZ);
    this.pinchSmoothedX += G.PINCH_POSITION_ALPHA * (rx - this.pinchSmoothedX);
    this.pinchSmoothedY += G.PINCH_POSITION_ALPHA * (ry - this.pinchSmoothedY);
    const ratio = clamp(this.pinchSmoothedScale / this.pinchAnchorScale, 0.42, 2.8);
    const dScale = ratio - this.pinchLastScaleRatio;
    const dZ = this.pinchLastZ - this.pinchSmoothedZ;
    const rawFwd = clamp(dScale * 2.15 + dZ * 3.1, -0.55, 0.55);
    const fwd = Math.abs(rawFwd) < G.PINCH_FORWARD_DEADZONE ? 0 : rawFwd;
    const rdx = clamp(this.pinchSmoothedX - this.pinchLastX, -0.22, 0.22);
    const rdy = clamp(this.pinchSmoothedY - this.pinchLastY, -0.22, 0.22);
    const dx = Math.abs(rdx) < G.PINCH_PAN_DEADZONE ? 0 : rdx;
    const dy = Math.abs(rdy) < G.PINCH_PAN_DEADZONE ? 0 : rdy;
    this.pinchLastScaleRatio = ratio;
    this.pinchLastZ = this.pinchSmoothedZ;
    this.pinchLastX = this.pinchSmoothedX;
    this.pinchLastY = this.pinchSmoothedY;
    this.pinchLastSeenMs = t;
    return update({ mapPinchActive: true, mapPinchForwardDelta: fwd, mapPinchPanDeltaX: dx, mapPinchPanDeltaY: dy });
  }

  private clearPinch() {
    this.pinchAnchorScale = 0;
    this.pinchLastScaleRatio = 1;
    this.pinchSmoothedScale = 0;
    this.pinchSmoothedZ = 0;
    this.pinchSmoothedX = 0;
    this.pinchSmoothedY = 0;
    this.pinchLastZ = 0;
    this.pinchLastX = 0;
    this.pinchLastY = 0;
    this.pinchLastSeenMs = null;
  }

  private detectSwipe(hands: Pt[][], t: number): "NEXT" | "PREVIOUS" | null {
    if (t < this.swipeCooldownUntilMs) {
      this.clearSwipe();
      return null;
    }
    let active: Pt[] | null = null;
    if (hands.length === 1) active = hands[0];
    else if (hands.length > 1 && this.swipeStartMs !== null) {
      active = hands.reduce((a, b) => (Math.abs(centerX(a) - this.swipeLastX) <= Math.abs(centerX(b) - this.swipeLastX) ? a : b));
    }
    if (!active) {
      if (this.swipeLastSeenMs === null || t - this.swipeLastSeenMs > G.SWIPE_GAP_RESET_MS) this.clearSwipe();
      return null;
    }
    const px = centerX(active), py = centerY(active);
    const start = this.swipeStartMs, last = this.swipeLastSeenMs;
    if (start === null || last === null || t - last > G.SWIPE_GAP_RESET_MS || t - start > G.SWIPE_MAX_WINDOW_MS) {
      this.swipeStartMs = t;
      this.swipeStartX = px;
      this.swipeStartY = py;
      this.swipeLastX = px;
      this.swipeLastSeenMs = t;
      return null;
    }
    const overall = this.swipeLastX - this.swipeStartX;
    const step = px - this.swipeLastX;
    if (Math.abs(overall) >= G.SWIPE_REVERSAL_ARM_X && overall * step < 0 && Math.abs(step) >= 0.012) {
      this.swipeStartMs = last;
      this.swipeStartX = this.swipeLastX;
      this.swipeStartY = py;
    }
    this.swipeLastX = px;
    this.swipeLastSeenMs = t;
    const dx = px - this.swipeStartX, dy = py - this.swipeStartY;
    if (Math.abs(dx) >= G.SWIPE_MIN_DELTA_X && Math.abs(dx) >= Math.abs(dy) * G.SWIPE_HORIZONTAL_RATIO) {
      this.clearSwipe();
      this.swipeCooldownUntilMs = t + G.SWIPE_COOLDOWN_MS;
      return dx < 0 ? "PREVIOUS" : "NEXT";
    }
    return null;
  }

  private clearSwipe() {
    this.swipeStartMs = null;
    this.swipeStartX = 0;
    this.swipeStartY = 0;
    this.swipeLastX = 0;
    this.swipeLastSeenMs = null;
  }
}
