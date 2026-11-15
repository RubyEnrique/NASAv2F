// Port of MarsMolaMapView.kt: offline MOLA relief tiles around Jezero, the wearer's track, heading cone,
// crewmates (fitted to the map, pinned to the edge when out of range), zoom / tilt / pan.
"use client";
import { useEffect, useRef } from "react";
import type { CrewMember } from "@/lib/crew";
import { MAP } from "@/lib/hud";

const JEZERO_LAT = 18.38, JEZERO_LON = 77.58, MIN_TILE_Z = 4;
const tileZoomFor = (z: number) => (z >= 2.75 ? 6 : z >= 1.65 ? 5 : 4);

function latLonToTile(lat: number, lon: number, z: number) {
  const cols = 2 << z, rows = 1 << z;
  const u = (((lon + 180) % 360) + 360) % 360 / 360, v = (90 - Math.max(-89.999, Math.min(89.999, lat))) / 180;
  const ec = u * cols, er = v * rows;
  const col = Math.max(0, Math.min(cols - 1, Math.floor(ec))), row = Math.max(0, Math.min(rows - 1, Math.floor(er)));
  return { z, row, col, fx: Math.max(0, Math.min(1, ec - col)), fy: Math.max(0, Math.min(1, er - row)) };
}

const cache = new Map<string, HTMLImageElement | null>();
function loadTile(z: number, row: number, col: number, onLoad: () => void): HTMLImageElement | null {
  const key = `${z}/${row}/${col}`;
  if (cache.has(key)) return cache.get(key)!;
  cache.set(key, null);
  const img = new Image();
  img.onload = () => { cache.set(key, img); onLoad(); };
  img.onerror = () => cache.set(key, null);
  img.src = `/mars_mola_tiles/${key}.jpg`;
  return null;
}

/** Exact tile first, then ancestors cropped to the matching sub-tile (offline coverage). */
function resolve(z: number, row: number, col: number, onLoad: () => void) {
  for (let zz = z; zz >= MIN_TILE_Z; zz--) {
    const dz = z - zz, div = 1 << dz;
    const img = loadTile(zz, row >> dz, col >> dz, onLoad);
    if (img) return { img, div, sx: col & (div - 1), sy: row & (div - 1) };
  }
  return null;
}

export interface MapProps {
  track: { east: number; north: number }[];
  heading: number;
  hasGpsFix: boolean;
  zoom: number;
  tilt: number;
  panEast: number;
  panNorth: number;
  pinching: boolean;
  crew: CrewMember[];
}

export function MarsMap(p: MapProps) {
  const ref = useRef<HTMLCanvasElement>(null);
  const props = useRef(p);
  props.current = p;
  const anim = useRef({ zoom: p.zoom, tilt: p.tilt, pe: p.panEast, pn: p.panNorth });

  useEffect(() => {
    let raf = 0;
    const draw = () => {
      raf = requestAnimationFrame(draw);
      const cv = ref.current;
      if (!cv) return;
      const P = props.current, a = anim.current;
      a.zoom += (Math.max(MAP.MIN_ZOOM, Math.min(MAP.MAX_ZOOM, P.zoom)) - a.zoom) * 0.2;
      a.tilt += (P.tilt - a.tilt) * 0.2;
      a.pe += (P.panEast - a.pe) * 0.22;
      a.pn += (P.panNorth - a.pn) * 0.22;
      const dpr = Math.min(2, window.devicePixelRatio || 1);
      const w = cv.clientWidth, h = cv.clientHeight;
      if (!w || !h) return;
      if (cv.width !== Math.round(w * dpr) || cv.height !== Math.round(h * dpr)) { cv.width = Math.round(w * dpr); cv.height = Math.round(h * dpr); }
      const g = cv.getContext("2d")!;
      g.setTransform(dpr, 0, 0, dpr, 0, 0);
      const bg = g.createRadialGradient(w * 0.48, h * 0.45, 0, w * 0.48, h * 0.45, Math.max(w, h) * 0.75);
      bg.addColorStop(0, "#3a3a3c"); bg.addColorStop(0.5, "#2c2c2e"); bg.addColorStop(1, "#1c1c1e");
      g.fillStyle = bg;
      g.fillRect(0, 0, w, h);

      const tz = tileZoomFor(a.zoom);
      const tierScale = Math.max(0.75, Math.min(2.4, a.zoom / 2 ** (tz - MIN_TILE_Z)));
      const cur = P.track[P.track.length - 1] ?? { east: 0, north: 0 };
      const camLat = Math.max(-85, Math.min(85, JEZERO_LAT + cur.north / 59200 + a.pn));
      const camLon = JEZERO_LON + cur.east / 56100 + a.pe;
      const ct = latLonToTile(camLat, camLon, tz);
      const tile = Math.max(128, Math.round(Math.max(w, h) * 0.68 * tierScale));
      const ax = w / 2 - ct.fx * tile, ay = h / 2 - ct.fy * tile;
      g.save();
      g.filter = "grayscale(1)";
      g.globalAlpha = 0.92;
      let any = false;
      for (let dr = -1; dr <= 1; dr++) {
        const row = ct.row + dr;
        if (row < 0 || row >= 1 << tz) continue;
        for (let dc = -1; dc <= 1; dc++) {
          const cols = 2 << tz, col = (((ct.col + dc) % cols) + cols) % cols;
          const r = resolve(tz, row, col, () => {});
          if (!r) continue;
          any = true;
          const sw = r.img.width / r.div, sh = r.img.height / r.div;
          g.drawImage(r.img, r.sx * sw, r.sy * sh, sw, sh, Math.round(ax + dc * tile), Math.round(ay + dr * tile), tile, tile);
        }
      }
      g.restore();
      if (any) { g.fillStyle = "rgba(0,0,0,0.18)"; g.fillRect(0, 0, w, h); }

      // Graticule (moves against pan for parallax).
      const ppd = (tile * (2 << tz)) / 360, step = 44;
      g.strokeStyle = "rgba(255,255,255,0.08)";
      g.lineWidth = 0.6;
      for (let x = ((((w / 2 - a.pe * ppd) % step) + step) % step); x < w; x += step) { g.beginPath(); g.moveTo(x, 0); g.lineTo(x, h); g.stroke(); }
      for (let y = ((((h / 2 + a.pn * ppd) % step) + step) % step); y < h; y += step) { g.beginPath(); g.moveTo(0, y); g.lineTo(w, y); g.stroke(); }

      let ext = 35;
      for (const pt of P.track) ext = Math.max(ext, Math.abs(pt.east - cur.east), Math.abs(pt.north - cur.north));
      for (const m of P.crew) if (m.eastMeters != null && m.northMeters != null) ext = Math.max(ext, Math.min(500, Math.max(Math.abs(m.eastMeters), Math.abs(m.northMeters))) * 1.15);
      const ppm = ((Math.min(w, h) * 0.36) / ext) * a.zoom;
      const rover = { x: w / 2 - a.pe * ppd, y: h / 2 + a.pn * ppd };

      if (P.track.length > 1) {
        g.beginPath();
        P.track.forEach((pt, i) => {
          const x = rover.x + (pt.east - cur.east) * ppm, y = rover.y - (pt.north - cur.north) * ppm;
          if (i) g.lineTo(x, y); else g.moveTo(x, y);
        });
        g.strokeStyle = "rgba(255,255,255,0.75)";
        g.lineWidth = 2;
        g.lineJoin = "round";
        g.stroke();
      }
      // Heading cone.
      const hr = (P.heading * Math.PI) / 180, len = 26, spread = 0.42;
      const cone = g.createLinearGradient(rover.x, rover.y, rover.x + Math.sin(hr) * len, rover.y - Math.cos(hr) * len);
      cone.addColorStop(0, "rgba(255,255,255,0.42)"); cone.addColorStop(1, "rgba(255,255,255,0)");
      g.fillStyle = cone;
      g.beginPath();
      g.moveTo(rover.x, rover.y);
      g.lineTo(rover.x + Math.sin(hr - spread) * len, rover.y - Math.cos(hr - spread) * len);
      g.lineTo(rover.x + Math.sin(hr + spread) * len, rover.y - Math.cos(hr + spread) * len);
      g.closePath();
      g.fill();

      for (const m of P.crew) {
        if (m.eastMeters == null || m.northMeters == null) continue;
        const x = Math.max(6, Math.min(w - 6, rover.x + m.eastMeters * ppm)), y = Math.max(6, Math.min(h - 6, rover.y - m.northMeters * ppm));
        const c = m.emergency ? "255,69,58" : "255,255,255";
        g.fillStyle = `rgba(${c},0.3)`; g.beginPath(); g.arc(x, y, 9, 0, 7); g.fill();
        g.fillStyle = `rgb(${c})`; g.beginPath(); g.arc(x, y, 4, 0, 7); g.fill();
        g.font = "500 9px system-ui, sans-serif";
        g.fillStyle = "rgba(255,255,255,0.85)";
        g.fillText(m.name.replace(/^Crew-/, ""), x + 7, y - 6);
      }
      const pulse = 0.5 + 0.5 * Math.sin(performance.now() / 450);
      g.fillStyle = `rgba(${P.hasGpsFix ? "255,255,255" : "255,214,10"},${0.28 * (1 - pulse)})`;
      g.beginPath(); g.arc(rover.x, rover.y, 8 + 8 * pulse, 0, 7); g.fill();
      g.fillStyle = "#fff"; g.beginPath(); g.arc(rover.x, rover.y, 5.5, 0, 7); g.fill();
      g.fillStyle = P.hasGpsFix ? "#fff" : "#ffd60a"; g.beginPath(); g.arc(rover.x, rover.y, 3.5, 0, 7); g.fill();
      g.strokeStyle = "rgba(0,0,0,0.5)"; g.lineWidth = 0.8; g.stroke();

      g.font = "500 9px system-ui, sans-serif";
      g.fillStyle = "rgba(255,255,255,0.72)";
      g.fillText(`${camLat.toFixed(2)}°N  ${camLon.toFixed(2)}°E`, 10, h - 10);
      const tag = `${a.zoom.toFixed(1)}x  ${Math.round(a.tilt)}°  z${tz}`;
      g.fillText(tag, w - 10 - g.measureText(tag).width, h - 10);
    };
    draw();
    return () => cancelAnimationFrame(raf);
  }, []);

  return (
    <div style={{ flex: 1, minHeight: 0, borderRadius: "calc(var(--dp) * 18)", overflow: "hidden", background: "#0e1826", border: `${p.pinching ? 1.2 : 0.75}px solid rgba(255,255,255,${p.pinching ? 0.78 : 0.24})`, perspective: "calc(var(--dp) * 600)" }}>
      <canvas ref={ref} data-testid="mars_mola_map_canvas" style={{ width: "100%", height: "100%", display: "block", transform: `rotateX(${p.tilt}deg) scale(${1 + (p.tilt / 42) * 0.14})`, transformOrigin: "50% 58%", transition: "transform .17s" }} />
    </div>
  );
}
