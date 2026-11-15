// Port of crew/CrewModel.kt (roster, geo maths) for the browser crew link.

export interface CrewStatus {
  id: string;
  name: string;
  lat: number | null;
  lon: number | null;
  headingDeg: number;
  heartRateBpm: number | null;
  cognitiveLoad: number;
  emergency: boolean;
  reason: string;
  sentAtMs: number;
}

export interface CrewMember {
  id: string;
  name: string;
  distanceMeters: number | null;
  bearingDeg: number | null;
  eastMeters: number | null;
  northMeters: number | null;
  heartRateBpm: number | null;
  cognitiveLoad: number;
  emergency: boolean;
  reason: string;
  lastSeenMs: number;
  sentPosition: boolean;
  simulated: boolean;
}

const R = 6371000;
const toRad = (d: number) => (d * Math.PI) / 180;

export const Geo = {
  offsetMeters(lat1: number, lon1: number, lat2: number, lon2: number): [number, number] {
    const meanLat = toRad((lat1 + lat2) / 2);
    return [toRad(lon2 - lon1) * Math.cos(meanLat) * R, toRad(lat2 - lat1) * R];
  },
  distanceMeters: (e: number, n: number) => Math.sqrt(e * e + n * n),
  bearingDeg: (e: number, n: number) => ((Math.atan2(e, n) * 180) / Math.PI + 360) % 360,
  /** Inverse of offsetMeters (used by simulated crew walking around this wearer). */
  displace(lat: number, lon: number, east: number, north: number): [number, number] {
    return [lat + (north / R) * (180 / Math.PI), lon + (east / (R * Math.cos(toRad(lat)))) * (180 / Math.PI)];
  },
};

/** Keeps the latest packet per crewmate and forgets anyone silent for staleMs. */
export class CrewRoster {
  private latest = new Map<string, { s: CrewStatus; seen: number; simulated: boolean }>();
  constructor(private selfId: string, private staleMs = 20000) {}

  onPacket(s: CrewStatus, receivedAt: number, simulated = false) {
    if (s.id === this.selfId) return;
    this.latest.set(s.id, { s, seen: receivedAt, simulated });
  }

  members(now: number, selfLat: number | null, selfLon: number | null): CrewMember[] {
    for (const [k, v] of this.latest) if (now - v.seen > this.staleMs) this.latest.delete(k);
    return [...this.latest.values()]
      .map(({ s, seen, simulated }) => {
        const off = selfLat != null && selfLon != null && s.lat != null && s.lon != null ? Geo.offsetMeters(selfLat, selfLon, s.lat, s.lon) : null;
        return {
          id: s.id,
          name: s.name,
          distanceMeters: off ? Geo.distanceMeters(off[0], off[1]) : null,
          bearingDeg: off ? Geo.bearingDeg(off[0], off[1]) : null,
          eastMeters: off ? off[0] : null,
          northMeters: off ? off[1] : null,
          heartRateBpm: s.heartRateBpm,
          cognitiveLoad: s.cognitiveLoad,
          emergency: s.emergency,
          reason: s.reason,
          lastSeenMs: seen,
          sentPosition: s.lat != null && s.lon != null,
          simulated,
        };
      })
      .sort((a, b) => (a.emergency === b.emergency ? (a.distanceMeters ?? Infinity) - (b.distanceMeters ?? Infinity) : a.emergency ? -1 : 1));
  }
}
