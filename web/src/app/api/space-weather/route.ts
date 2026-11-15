// NASA DONKI flares + solar energetic particle events for the last 7 days, cached for 30 minutes.
// The key stays on the server: set NASA_API_KEY in Vercel (DEMO_KEY works with low rate limits).
export const revalidate = 1800;

interface DonkiFlare { peakTime?: string; beginTime?: string; classType?: string }
interface DonkiSep { eventTime?: string; beginTime?: string }

export async function GET() {
  const key = process.env.NASA_API_KEY || "DEMO_KEY";
  const day = (ms: number) => new Date(ms).toISOString().slice(0, 10);
  const end = day(Date.now()), start = day(Date.now() - 7 * 86400000);
  const get = async <T,>(type: string): Promise<T[]> => {
    try {
      const r = await fetch(`https://api.nasa.gov/DONKI/${type}?startDate=${start}&endDate=${end}&api_key=${key}`, { next: { revalidate: 1800 } });
      if (!r.ok) return [];
      const j = await r.json();
      return Array.isArray(j) ? j : [];
    } catch {
      return [];
    }
  };
  const [flr, sep] = await Promise.all([get<DonkiFlare>("FLR"), get<DonkiSep>("SEP")]);
  const events = [
    ...flr.map((f) => ({ type: "FLR" as const, time: f.peakTime || f.beginTime || "", classType: f.classType || null })),
    ...sep.map((s) => ({ type: "SEP" as const, time: s.eventTime || s.beginTime || "", classType: null })),
  ].filter((e) => e.time);
  return Response.json({ events, fetchedAt: Date.now() });
}
