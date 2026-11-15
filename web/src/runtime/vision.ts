// Camera + MediaPipe in the browser: same .task models as the Android app.
// Hands: GestureRecognizer (falls back to HandLandmarker). Eyes: FaceLandmarker blendshapes, run on
// the same webcam stream when the camera faces the user (laptops), as the Android app's front camera.
import type { HandObservation } from "@/lib/gestures";
import { poseFromCategory } from "@/lib/gestures";
import { gazeFromLandmarks, type EyeSample } from "@/lib/eye";

type Vision = typeof import("@mediapipe/tasks-vision");

export interface VisionCallbacks {
  onHands: (hands: HandObservation[], t: number) => void;
  onEye: (s: EyeSample) => void;
  onEyeStatus: (s: "STARTING" | "UNSUPPORTED_DEVICE" | "MODEL_ERROR" | "OFF") => void;
  onFps?: (fps: number) => void;
}

export interface CameraInfo { stream: MediaStream; facing: "user" | "environment"; mirrored: boolean }

const isMobile = () => typeof navigator !== "undefined" && /Android|iPhone|iPad|iPod|Mobile/i.test(navigator.userAgent);

export async function openCamera(prefer: "auto" | "user" | "environment" = "auto"): Promise<CameraInfo> {
  const want = prefer === "auto" ? (isMobile() ? "environment" : "user") : prefer;
  const tries: MediaStreamConstraints[] = [
    { video: { facingMode: { ideal: want }, width: { ideal: 1280 }, height: { ideal: 720 } }, audio: false },
    { video: true, audio: false },
  ];
  let lastErr: unknown;
  for (const c of tries) {
    try {
      const stream = await navigator.mediaDevices.getUserMedia(c);
      const settings = stream.getVideoTracks()[0]?.getSettings() ?? {};
      const facing = (settings.facingMode as "user" | "environment" | undefined) ?? (isMobile() ? want : "user");
      return { stream, facing, mirrored: facing === "user" };
    } catch (e) {
      lastErr = e;
    }
  }
  throw lastErr;
}

export class VisionLoop {
  private vision: Vision | null = null;
  private gesture: import("@mediapipe/tasks-vision").GestureRecognizer | null = null;
  private hands: import("@mediapipe/tasks-vision").HandLandmarker | null = null;
  private face: import("@mediapipe/tasks-vision").FaceLandmarker | null = null;
  private raf = 0;
  private running = false;
  private lastTs = 0;
  private frame = 0;
  private fpsT0 = 0;
  private fpsN = 0;

  constructor(private video: HTMLVideoElement, private mirrored: boolean, private wantEyes: boolean, private cb: VisionCallbacks) {}

  async init(onProgress?: (msg: string) => void) {
    onProgress?.("Loading hand tracking…");
    this.vision = await import("@mediapipe/tasks-vision");
    const fileset = await this.vision.FilesetResolver.forVisionTasks("/mediapipe-wasm");
    const make = async <T>(f: (delegate: "GPU" | "CPU") => Promise<T>): Promise<T | null> => {
      try { return await f("GPU"); } catch { try { return await f("CPU"); } catch { return null; } }
    };
    this.gesture = await make((delegate) =>
      this.vision!.GestureRecognizer.createFromOptions(fileset, {
        baseOptions: { modelAssetPath: "/models/gesture_recognizer.task", delegate },
        runningMode: "VIDEO",
        numHands: 2,
        minHandDetectionConfidence: 0.5,
        minHandPresenceConfidence: 0.5,
        minTrackingConfidence: 0.5,
      }),
    );
    if (!this.gesture) {
      this.hands = await make((delegate) =>
        this.vision!.HandLandmarker.createFromOptions(fileset, {
          baseOptions: { modelAssetPath: "/models/hand_landmarker.task", delegate },
          runningMode: "VIDEO",
          numHands: 2,
        }),
      );
    }
    if (!this.gesture && !this.hands) throw new Error("Hand tracking model failed to load");
    if (this.wantEyes) {
      onProgress?.("Loading eye tracking…");
      this.cb.onEyeStatus("STARTING");
      this.face = await make((delegate) =>
        this.vision!.FaceLandmarker.createFromOptions(fileset, {
          baseOptions: { modelAssetPath: "/models/face_landmarker.task", delegate },
          runningMode: "VIDEO",
          numFaces: 1,
          outputFaceBlendshapes: true,
        }),
      );
      if (!this.face) this.cb.onEyeStatus("MODEL_ERROR");
    } else {
      this.cb.onEyeStatus("UNSUPPORTED_DEVICE");
    }
  }

  start() {
    this.running = true;
    const loop = () => {
      if (!this.running) return;
      this.raf = requestAnimationFrame(loop);
      this.process();
    };
    loop();
  }

  stop() {
    this.running = false;
    cancelAnimationFrame(this.raf);
    this.gesture?.close();
    this.hands?.close();
    this.face?.close();
  }

  private process() {
    const v = this.video;
    if (v.readyState < 2 || v.videoWidth === 0) return;
    let ts = performance.now();
    if (ts <= this.lastTs) ts = this.lastTs + 1;
    this.lastTs = ts;
    this.frame++;
    const flip = (x: number) => (this.mirrored ? 1 - x : x);
    try {
      if (this.gesture) {
        const r = this.gesture.recognizeForVideo(v, ts);
        const hands: HandObservation[] = r.landmarks.map((lm, i) => {
          const top = r.gestures[i]?.[0];
          return { landmarks: lm.map((p) => ({ x: flip(p.x), y: p.y, z: p.z })), pose: poseFromCategory(top?.categoryName), poseScore: top?.score ?? 0 };
        });
        this.cb.onHands(hands, ts);
      } else if (this.hands) {
        const r = this.hands.detectForVideo(v, ts);
        this.cb.onHands(r.landmarks.map((lm) => ({ landmarks: lm.map((p) => ({ x: flip(p.x), y: p.y, z: p.z })), pose: "NONE", poseScore: 0 })), ts);
      }
      // Face every other frame keeps phones and older laptops responsive.
      if (this.face && this.frame % 2 === 0) {
        const f = this.face.detectForVideo(v, ts);
        const lm = f.faceLandmarks[0];
        const bs = f.faceBlendshapes[0]?.categories ?? [];
        if (!lm) {
          this.cb.onEye({ timestampMs: ts, faceDetected: false, closureLeft: 0, closureRight: 0, gazeX: 0, gazeY: 0 });
        } else {
          const score = (n: string) => bs.find((c) => c.categoryName === n)?.score ?? 0;
          const [gx, gy] = gazeFromLandmarks(lm);
          this.cb.onEye({ timestampMs: ts, faceDetected: true, closureLeft: score("eyeBlinkLeft"), closureRight: score("eyeBlinkRight"), gazeX: gx, gazeY: gy });
        }
      }
    } catch {
      // A dropped frame is fine; MediaPipe throws if the video is mid-resize.
    }
    this.fpsN++;
    if (ts - this.fpsT0 > 1000) {
      this.cb.onFps?.(Math.round((this.fpsN * 1000) / (ts - this.fpsT0)));
      this.fpsT0 = ts;
      this.fpsN = 0;
    }
  }
}
