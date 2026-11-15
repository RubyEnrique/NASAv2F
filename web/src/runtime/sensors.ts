// Phone sensors in the browser: orientation (compass heading + pitch of the rear camera, same maths
// as HeadingSensorMonitor.kt), geolocation (filtered like GpsDistanceMonitor.kt), battery, steps.
import { StepDetector } from "@/lib/suit";

const rad = (d: number) => (d * Math.PI) / 180;
const deg = (r: number) => (r * 180) / Math.PI;
const norm = (d: number) => ((d % 360) + 360) % 360;

/** W3C DeviceOrientation (alpha, beta, gamma) -> Android-style device-to-world rotation matrix. */
export function rotationMatrix(alpha: number, beta: number, gamma: number): number[] {
  const ca = Math.cos(rad(alpha)), sa = Math.sin(rad(alpha));
  const cb = Math.cos(rad(beta)), sb = Math.sin(rad(beta));
  const cg = Math.cos(rad(gamma)), sg = Math.sin(rad(gamma));
  return [
    ca * cg - sa * sb * sg, -cb * sa, cg * sa * sb + ca * sg,
    cg * sa + ca * sb * sg, ca * cb, sa * sg - ca * cg * sb,
    -cb * sg, sb, cb * cg,
  ];
}

/** Heading the rear camera looks at (falls back to the landscape top edge when flat). */
export function cameraHeading(R: number[]): number {
  let east = -R[2], north = -R[5];
  if (Math.hypot(east, north) < 0.22) { east = -R[0]; north = -R[3]; }
  return norm(deg(Math.atan2(east, north)));
}

/** Elevation of the rear-camera axis: +90 straight up, -90 straight down. */
export const cameraPitch = (R: number[]) => deg(Math.asin(Math.max(-1, Math.min(1, -R[8]))));

export interface OrientationHandlers { onHeading: (d: number) => void; onPitch: (d: number) => void; onAvailable: (absolute: boolean) => void }

type IOSOrientationEvent = DeviceOrientationEvent & { webkitCompassHeading?: number };

/** Must be called from a user gesture on iOS (permission prompt). */
export async function requestMotionPermission(): Promise<boolean> {
  const DOE = (globalThis as unknown as { DeviceOrientationEvent?: { requestPermission?: () => Promise<string> } }).DeviceOrientationEvent;
  const DME = (globalThis as unknown as { DeviceMotionEvent?: { requestPermission?: () => Promise<string> } }).DeviceMotionEvent;
  try {
    if (DOE?.requestPermission) {
      const r = await DOE.requestPermission();
      if (DME?.requestPermission) await DME.requestPermission().catch(() => "denied");
      return r === "granted";
    }
  } catch {
    return false;
  }
  return typeof window !== "undefined" && "DeviceOrientationEvent" in window;
}

export function startOrientation(h: OrientationHandlers): () => void {
  let smoothSin = 0, smoothCos = 1, smoothPitch = 0, first = true, seen = false, iosOffset: number | null = null;
  const handle = (e: IOSOrientationEvent, absolute: boolean) => {
    if (e.alpha == null || e.beta == null || e.gamma == null) return;
    let alpha = e.alpha;
    if (typeof e.webkitCompassHeading === "number") {
      // iOS: alpha is relative; webkitCompassHeading gives the compass reference.
      const off = norm(360 - e.webkitCompassHeading - e.alpha);
      iosOffset = iosOffset == null ? off : norm(iosOffset + 0.05 * (((off - iosOffset + 540) % 360) - 180));
      alpha = norm(e.alpha + iosOffset);
      absolute = true;
    }
    const R = rotationMatrix(alpha, e.beta, e.gamma);
    const hd = cameraHeading(R), p = cameraPitch(R);
    const a = 0.18;
    if (first) { smoothSin = Math.sin(rad(hd)); smoothCos = Math.cos(rad(hd)); smoothPitch = p; first = false; }
    smoothSin += a * (Math.sin(rad(hd)) - smoothSin);
    smoothCos += a * (Math.cos(rad(hd)) - smoothCos);
    smoothPitch += a * (p - smoothPitch);
    if (!seen) { seen = true; h.onAvailable(absolute); }
    h.onHeading(norm(deg(Math.atan2(smoothSin, smoothCos))));
    h.onPitch(smoothPitch);
  };
  const abs = (e: Event) => handle(e as IOSOrientationEvent, true);
  const rel = (e: Event) => handle(e as IOSOrientationEvent, (e as DeviceOrientationEvent).absolute === true);
  const hasAbs = "ondeviceorientationabsolute" in window;
  if (hasAbs) window.addEventListener("deviceorientationabsolute", abs);
  else window.addEventListener("deviceorientation", rel);
  return () => {
    window.removeEventListener("deviceorientationabsolute", abs);
    window.removeEventListener("deviceorientation", rel);
  };
}

export function startSteps(onStep: () => void): () => void {
  const det = new StepDetector();
  const f = (e: DeviceMotionEvent) => {
    const g = e.accelerationIncludingGravity;
    if (g?.x == null || g.y == null || g.z == null) return;
    if (det.onAccel(g.x, g.y, g.z, performance.now())) onStep();
  };
  window.addEventListener("devicemotion", f);
  return () => window.removeEventListener("devicemotion", f);
}

// ------------------------------------------------------------------ GPS (GpsDistanceMonitor filter)

export interface GpsHandlers {
  onSample: (dEast: number, dNorth: number, speed: number, accuracy: number) => void;
  onAbsoluteFix: (lat: number, lon: number, accuracy: number) => void;
  onPermission: (granted: boolean) => void;
}

const MAX_INDOOR_ACCURACY = 65, MAX_ACCEPTED_ACCURACY = 25, MAX_CREW_SHARE_ACCURACY = 250, MIN_MOVE = 2.5, MAX_SPEED = 35, MIN_MOVING = 0.4;

export function filterDelta(raw: number, elapsedSec: number, acc: number, reportedSpeed: number) {
  if (acc > MAX_INDOOR_ACCURACY || elapsedSec <= 0) return { delta: 0, speed: 0, accept: false };
  const implied = raw / elapsedSec;
  if (implied > MAX_SPEED) return { delta: 0, speed: 0, accept: false };
  const jitter = Math.max(MIN_MOVE, acc * (acc > MAX_ACCEPTED_ACCURACY ? 0.55 : 0.45));
  if (raw < jitter) return { delta: 0, speed: reportedSpeed >= MIN_MOVING ? reportedSpeed : 0, accept: false };
  return { delta: raw, speed: Math.min(reportedSpeed >= MIN_MOVING ? reportedSpeed : implied, MAX_SPEED), accept: true };
}

export function startGps(h: GpsHandlers): () => void {
  if (!("geolocation" in navigator)) { h.onPermission(false); return () => {}; }
  let anchor: GeolocationPosition | null = null;
  const R = 6371000;
  const id = navigator.geolocation.watchPosition(
    (p) => {
      h.onPermission(true);
      const acc = p.coords.accuracy ?? 9999;
      if (acc <= MAX_CREW_SHARE_ACCURACY) h.onAbsoluteFix(p.coords.latitude, p.coords.longitude, acc);
      const speed = p.coords.speed ?? -1;
      if (!anchor) {
        if (acc <= MAX_INDOOR_ACCURACY) { anchor = p; h.onSample(0, 0, speed >= MIN_MOVING ? speed : 0, acc); }
        return;
      }
      const meanLat = rad((anchor.coords.latitude + p.coords.latitude) / 2);
      const dE = rad(p.coords.longitude - anchor.coords.longitude) * Math.cos(meanLat) * R;
      const dN = rad(p.coords.latitude - anchor.coords.latitude) * R;
      const raw = Math.hypot(dE, dN);
      const f = filterDelta(raw, Math.max(0.25, (p.timestamp - anchor.timestamp) / 1000), acc, speed);
      if (f.accept) { anchor = p; h.onSample(dE, dN, f.speed, acc); }
      else if (acc <= MAX_INDOOR_ACCURACY) h.onSample(0, 0, f.speed, acc);
    },
    (err) => { if (err.code === err.PERMISSION_DENIED) h.onPermission(false); },
    { enableHighAccuracy: true, maximumAge: 1000, timeout: 20000 },
  );
  return () => navigator.geolocation.clearWatch(id);
}

// ------------------------------------------------------------------ battery

export async function startBattery(on: (pct: number | null) => void): Promise<() => void> {
  const nav = navigator as Navigator & { getBattery?: () => Promise<{ level: number; addEventListener: (t: string, f: () => void) => void; removeEventListener: (t: string, f: () => void) => void }> };
  if (!nav.getBattery) { on(null); return () => {}; }
  try {
    const b = await nav.getBattery();
    const f = () => on(Math.round(b.level * 100));
    f();
    b.addEventListener("levelchange", f);
    return () => b.removeEventListener("levelchange", f);
  } catch {
    on(null);
    return () => {};
  }
}
