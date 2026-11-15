// Port of the phrase grammar and parser in voice/VoiceCommandRecognizer.kt.

export type VoiceCommand =
  | { kind: "TOGGLE_QUICK" | "OPEN_PERMANENT" | "CLOSE_PERMANENT" }
  | { kind: "NAVIGATE"; page: "RADIATION" | "ISOLATION" | "DISTANCE" | "GRAVITY" | "ENVIRONMENT" | "DRILLS" }
  | { kind: "RUN_EMERGENCY"; number: number }
  | { kind: "MAP_ZOOM_IN" | "MAP_ZOOM_OUT" | "MAP_RECENTER" | "RECENTER_VIEW" | "CENTER_LOCK" }
  | { kind: "END_ALL_EMERGENCIES" | "ACKNOWLEDGE" | "TOGGLE_EYE_PREVIEW" | "CALIBRATE_MIC" | "SENSITIVITY_UP" | "SENSITIVITY_DOWN" };

export const GRAMMAR_PHRASES = [
  "dashboard", "dashboard open", "dashboard close", "open dashboard", "close dashboard",
  "mental", "mental health", "cognitive", "readiness", "isolation", "sleep", "vitals",
  "distance", "earth", "comms", "gravity", "exercise", "fitness", "environment", "suit",
  "suit status", "radiation", "shield", "shelter", "map", "meter", "drills", "drill", "emergencies",
  "emergency zero", "emergency one", "emergency two", "emergency three", "emergency four",
  "emergency five", "emergency six", "emergency seven", "emergency eight", "emergency nine",
  "emergency ten", "clear drill", "stop drill", "reset drills", "zoom in", "zoom out",
  "recenter", "recenter view", "center", "center lock", "keep centered", "center map", "reset map",
  "end all emergencies", "end emergencies", "all clear", "acknowledge", "copy", "silence",
  "eyes", "eye preview", "show eyes", "hide eyes", "calibrate", "calibrate microphone",
  "sensitivity up", "sensitivity down", "[unk]",
];

export const minConfidenceFor = (sensitivity: number) => 0.55 + (3 - Math.min(5, Math.max(1, sensitivity))) * 0.05;

const NUM: Record<string, number> = {
  "0": 0, zero: 0, "1": 1, one: 1, "2": 2, two: 2, "3": 3, three: 3, "4": 4, four: 4, "5": 5, five: 5,
  "6": 6, six: 6, "7": 7, seven: 7, "8": 8, eight: 8, "9": 9, nine: 9, "10": 10, ten: 10,
};

export function parseVoiceCommand(transcript: string): VoiceCommand | null {
  const n = transcript.toLowerCase().replace(/[^a-z0-9\s]/g, " ").replace(/\s+/g, " ").trim();
  if (!n) return null;
  if (/\b(end all emergencies|end emergencies|all clear)\b/.test(n)) return { kind: "END_ALL_EMERGENCIES" };
  if (/\b(clear drills?|stop drills?|reset drills?)\b/.test(n)) return { kind: "RUN_EMERGENCY", number: 0 };
  const em = /\bemergency\s+(10|ten|9|nine|8|eight|7|seven|6|six|5|five|4|four|3|three|2|two|1|one|0|zero)\b/.exec(n);
  if (em && em[1] in NUM) return { kind: "RUN_EMERGENCY", number: NUM[em[1]] };
  const has = (p: string) => new RegExp(`\\b(${p})\\b`).test(n);
  if (n.includes("dashboard open") || n.includes("open dashboard")) return { kind: "OPEN_PERMANENT" };
  if (n.includes("dashboard close") || n.includes("close dashboard")) return { kind: "CLOSE_PERMANENT" };
  if (has("calibrate|calibrate microphone")) return { kind: "CALIBRATE_MIC" };
  if (has("sensitivity up")) return { kind: "SENSITIVITY_UP" };
  if (has("sensitivity down")) return { kind: "SENSITIVITY_DOWN" };
  if (has("zoom in")) return { kind: "MAP_ZOOM_IN" };
  if (has("zoom out")) return { kind: "MAP_ZOOM_OUT" };
  if (has("center map|reset map")) return { kind: "MAP_RECENTER" };
  if (has("recenter|recenter view")) return { kind: "RECENTER_VIEW" };
  if (has("center|center lock|keep centered")) return { kind: "CENTER_LOCK" };
  if (has("acknowledge|copy|silence")) return { kind: "ACKNOWLEDGE" };
  if (has("eyes|eye preview|show eyes|hide eyes")) return { kind: "TOGGLE_EYE_PREVIEW" };
  if (has("radiation|shield|shelter")) return { kind: "NAVIGATE", page: "RADIATION" };
  if (has("mental|mental health|cognitive|readiness|isolation|sleep|vitals")) return { kind: "NAVIGATE", page: "ISOLATION" };
  if (has("distance|earth|comms")) return { kind: "NAVIGATE", page: "DISTANCE" };
  if (has("gravity|exercise|fitness")) return { kind: "NAVIGATE", page: "GRAVITY" };
  if (has("environment|suit|suit status|map|meter")) return { kind: "NAVIGATE", page: "ENVIRONMENT" };
  if (has("drills?|emergencies")) return { kind: "NAVIGATE", page: "DRILLS" };
  if (has("dashboard")) return { kind: "TOGGLE_QUICK" };
  return null;
}
