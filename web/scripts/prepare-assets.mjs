// Copies the Android app's on-device assets into public/ so the web build uses the exact same
// models and map tiles. Runs before `next dev` / `next build` (also on Vercel).
import { cpSync, existsSync, mkdirSync, statSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import * as tar from "tar";

const web = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const assets = resolve(web, "../app/src/main/assets");
const pub = join(web, "public");

if (!existsSync(assets)) {
  console.error(
    `prepare-assets: ${assets} not found.\n` +
      "On Vercel, keep 'Include files outside the root directory in the Build Step' enabled (Project Settings > General).",
  );
  process.exit(1);
}

function newer(src, dst) {
  return !existsSync(dst) || statSync(src).mtimeMs > statSync(dst).mtimeMs;
}

mkdirSync(join(pub, "models"), { recursive: true });
for (const f of ["gesture_recognizer.task", "hand_landmarker.task", "face_landmarker.task"]) {
  if (newer(join(assets, f), join(pub, "models", f))) cpSync(join(assets, f), join(pub, "models", f));
}

cpSync(join(assets, "mars_mola_tiles"), join(pub, "mars_mola_tiles"), { recursive: true });
cpSync(join(web, "node_modules/@mediapipe/tasks-vision/wasm"), join(pub, "mediapipe-wasm"), { recursive: true });

const voskName = "vosk-model-small-en-us-0.15";
const voskOut = join(pub, "models", `${voskName}.tar.gz`);
if (!existsSync(voskOut)) {
  await tar.create({ gzip: true, file: voskOut, cwd: assets, portable: true }, [voskName]);
}
console.log("prepare-assets: models, map tiles, MediaPipe wasm and Vosk model ready in public/");
