// Crew link for the browser. The Android app uses UDP on the shared Wi-Fi, which browsers cannot do.
// Here: (1) a room shared by tabs/windows of the same browser (BroadcastChannel), and (2) simulated
// crewmates for demos. A hosted realtime service can be added later behind the same interface.
import type { CrewStatus } from "@/lib/crew";
import { Geo } from "@/lib/crew";

export interface CrewTransport {
  start(): void;
  send(s: CrewStatus): void;
  stop(): void;
}

export class TabRoomTransport implements CrewTransport {
  private ch: BroadcastChannel | null = null;
  constructor(private room: string, private onPacket: (s: CrewStatus) => void) {}
  start() {
    if (typeof BroadcastChannel === "undefined") return;
    this.ch = new BroadcastChannel(`crew-room-${this.room}`);
    this.ch.onmessage = (e) => {
      const s = e.data as CrewStatus;
      if (s && typeof s.id === "string") this.onPacket(s);
    };
  }
  send(s: CrewStatus) { this.ch?.postMessage(s); }
  stop() { this.ch?.close(); this.ch = null; }
}

/** Two crewmates walking around this wearer; one can be put into an emergency on demand. */
export class SimulatedCrew {
  private t0 = Date.now();
  private sosUntil = 0;
  constructor(private getCenter: () => [number, number] | null, private onPacket: (s: CrewStatus, simulated: boolean) => void) {}

  triggerEmergency(ms = 45000) { this.sosUntil = Date.now() + ms; }
  clearEmergency() { this.sosUntil = 0; }

  tick() {
    const c = this.getCenter();
    const t = (Date.now() - this.t0) / 1000;
    const bots = [
      { id: "SIM1", name: "Crew-Vega", r: 38, period: 240, phase: 0, hr: 94 },
      { id: "SIM2", name: "Crew-Orion", r: 120, period: 600, phase: 2.1, hr: 102 },
    ];
    for (const b of bots) {
      const a = b.phase + (2 * Math.PI * t) / b.period;
      const east = b.r * Math.sin(a), north = b.r * Math.cos(a);
      const pos = c ? Geo.displace(c[0], c[1], east, north) : null;
      const sos = b.id === "SIM1" && Date.now() < this.sosUntil;
      this.onPacket(
        {
          id: b.id,
          name: b.name,
          lat: pos ? pos[0] : null,
          lon: pos ? pos[1] : null,
          headingDeg: ((a * 180) / Math.PI + 90) % 360,
          heartRateBpm: sos ? 138 : b.hr + Math.round(4 * Math.sin(t / 7)),
          cognitiveLoad: sos ? 96 : 34 + Math.round(6 * Math.sin(t / 11)),
          emergency: sos,
          reason: sos ? "E-01 Pressure Leak" : "",
          sentAtMs: Date.now(),
        },
        true,
      );
    }
  }
}

export function randomCrewId() {
  const a = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  let s = "";
  for (let i = 0; i < 4; i++) s += a[Math.floor(Math.random() * a.length)];
  return s;
}
