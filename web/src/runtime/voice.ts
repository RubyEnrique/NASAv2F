// Offline voice commands in the browser with the same Vosk model and phrase grammar as the app.
// Falls back to the browser's built-in speech recognition if Vosk cannot load.
import { GRAMMAR_PHRASES, minConfidenceFor, parseVoiceCommand, type VoiceCommand } from "@/lib/voiceCommands";

export interface VoiceHandlers {
  onCommand: (c: VoiceCommand, heard: string) => void;
  onStatus: (s: { listening?: boolean; speaking?: boolean; levelDb?: number; lastHeard?: string; engine?: "vosk" | "webspeech" | "none"; loadingPct?: number | null; error?: string | null; available?: boolean; sensitivity?: number }) => void;
}

const MODEL_URL = "/models/vosk-model-small-en-us-0.15.tar.gz";

type SpeechRec = { continuous: boolean; interimResults: boolean; lang: string; onresult: ((e: { results: ArrayLike<{ isFinal: boolean; 0: { transcript: string; confidence: number } }>; resultIndex: number }) => void) | null; onend: (() => void) | null; onerror: ((e: { error: string }) => void) | null; start: () => void; stop: () => void };

export class VoiceControl {
  private ctx: AudioContext | null = null;
  private stream: MediaStream | null = null;
  private node: ScriptProcessorNode | null = null;
  private analyser: AnalyserNode | null = null;
  private model: { terminate: () => void } | null = null;
  private rec: { acceptWaveform: (b: AudioBuffer) => void; remove: () => void } | null = null;
  private web: SpeechRec | null = null;
  private muted = false;
  private stopped = false;
  private sensitivity = 3;
  private lastCmd = "";
  private lastCmdAt = 0;
  private meterTimer: ReturnType<typeof setInterval> | null = null;

  constructor(private h: VoiceHandlers) {}

  /** Call from a user gesture (microphone permission). */
  async start(stream?: MediaStream) {
    this.stopped = false;
    try {
      this.stream = stream ?? (await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true, channelCount: 1 }, video: false }));
    } catch {
      this.h.onStatus({ available: false, error: "Microphone blocked", engine: "none" });
      return;
    }
    const AC = window.AudioContext ?? (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext;
    this.ctx = new AC();
    await this.ctx.resume().catch(() => {});
    const src = this.ctx.createMediaStreamSource(this.stream);
    this.analyser = this.ctx.createAnalyser();
    this.analyser.fftSize = 1024;
    src.connect(this.analyser);
    this.startMeter();
    this.h.onStatus({ available: true, listening: false, loadingPct: 0, engine: "none", error: null, sensitivity: this.sensitivity });
    try {
      await this.startVosk(src);
    } catch (e) {
      if (this.stopped) return;
      console.warn("Vosk unavailable, trying browser speech recognition", e);
      this.startWebSpeech();
    }
  }

  private async startVosk(src: MediaStreamAudioSourceNode) {
    const res = await fetch(MODEL_URL);
    if (!res.ok || !res.body) throw new Error(`model ${res.status}`);
    const total = Number(res.headers.get("content-length")) || 41_700_000;
    const reader = res.body.getReader();
    const chunks: Uint8Array[] = [];
    let got = 0;
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      chunks.push(value);
      got += value.length;
      this.h.onStatus({ loadingPct: Math.min(99, Math.round((got / total) * 100)) });
    }
    const blobUrl = URL.createObjectURL(new Blob(chunks as BlobPart[], { type: "application/gzip" }));
    const Vosk = await import("vosk-browser");
    const model = await Vosk.createModel(blobUrl);
    URL.revokeObjectURL(blobUrl);
    if (this.stopped || !this.ctx) { model.terminate(); return; }
    this.model = model;
    const rec = new model.KaldiRecognizer(this.ctx.sampleRate, JSON.stringify(GRAMMAR_PHRASES));
    rec.setWords(true);
    rec.on("result", (m) => {
      const r = (m as unknown as { result: { text: string; result?: { conf: number }[] } }).result;
      const text = r?.text?.trim() ?? "";
      if (!text || text === "[unk]") return;
      const conf = r.result?.length ? r.result.reduce((a, w) => a + w.conf, 0) / r.result.length : 1;
      this.h.onStatus({ lastHeard: text });
      if (conf < minConfidenceFor(this.sensitivity)) return;
      this.emit(text);
    });
    this.rec = rec;
    this.node = this.ctx.createScriptProcessor(4096, 1, 1);
    this.node.onaudioprocess = (ev) => {
      if (this.muted || !this.rec) return;
      try { this.rec.acceptWaveform(ev.inputBuffer); } catch { /* recogniser busy */ }
    };
    src.connect(this.node);
    this.node.connect(this.ctx.destination);
    this.h.onStatus({ engine: "vosk", listening: true, loadingPct: null });
  }

  private startWebSpeech() {
    const W = window as unknown as { SpeechRecognition?: new () => SpeechRec; webkitSpeechRecognition?: new () => SpeechRec };
    const Ctor = W.SpeechRecognition ?? W.webkitSpeechRecognition;
    if (!Ctor) {
      this.h.onStatus({ engine: "none", listening: false, loadingPct: null, error: "No speech engine in this browser" });
      return;
    }
    const r = new Ctor();
    r.continuous = true;
    r.interimResults = false;
    r.lang = "en-US";
    r.onresult = (e) => {
      for (let i = e.resultIndex; i < e.results.length; i++) {
        const res = e.results[i];
        if (!res.isFinal) continue;
        const text = res[0].transcript.trim();
        this.h.onStatus({ lastHeard: text });
        if (!this.muted) this.emit(text);
      }
    };
    r.onend = () => { if (!this.stopped) try { r.start(); } catch { /* already started */ } };
    r.onerror = (e) => { if (e.error === "not-allowed") this.h.onStatus({ error: "Microphone blocked", listening: false }); };
    try { r.start(); } catch { /* ignore */ }
    this.web = r;
    this.h.onStatus({ engine: "webspeech", listening: true, loadingPct: null, error: null });
  }

  private emit(text: string) {
    const c = parseVoiceCommand(text);
    if (!c) return;
    const key = JSON.stringify(c);
    const now = performance.now();
    if (key === this.lastCmd && now - this.lastCmdAt < 950) return;
    this.lastCmd = key;
    this.lastCmdAt = now;
    this.h.onCommand(c, text);
  }

  private startMeter() {
    const buf = new Float32Array(1024);
    this.meterTimer = setInterval(() => {
      if (!this.analyser) return;
      this.analyser.getFloatTimeDomainData(buf);
      let s = 0;
      for (const v of buf) s += v * v;
      const db = 20 * Math.log10(Math.sqrt(s / buf.length) + 1e-9);
      this.h.onStatus({ levelDb: Math.max(-90, db), speaking: db > -42 });
    }, 150);
  }

  /** Mute while the HUD is talking so it does not hear itself. */
  setMuted(m: boolean) { this.muted = m; }

  adjustSensitivity(d: number) {
    this.sensitivity = Math.min(5, Math.max(1, this.sensitivity + d));
    this.h.onStatus({ sensitivity: this.sensitivity });
  }

  stop() {
    this.stopped = true;
    if (this.meterTimer) clearInterval(this.meterTimer);
    this.node?.disconnect();
    this.rec?.remove();
    this.model?.terminate();
    this.web?.stop();
    this.stream?.getTracks().forEach((t) => t.stop());
    this.ctx?.close().catch(() => {});
  }
}

/** Spoken alerts (speechSynthesis). */
export class Announcer {
  constructor(private onSpeaking: (s: boolean) => void) {}
  speak(text: string) {
    if (!("speechSynthesis" in window)) return;
    const u = new SpeechSynthesisUtterance(text);
    u.rate = 1.02;
    u.onstart = () => this.onSpeaking(true);
    u.onend = () => this.onSpeaking(false);
    u.onerror = () => this.onSpeaking(false);
    speechSynthesis.cancel();
    speechSynthesis.speak(u);
  }
  stop() {
    if ("speechSynthesis" in window) speechSynthesis.cancel();
    this.onSpeaking(false);
  }
}
