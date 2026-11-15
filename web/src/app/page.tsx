"use client";
import dynamic from "next/dynamic";

// The HUD needs camera, sensors and WebAssembly: client only.
const App = dynamic(() => import("@/components/App"), { ssr: false });

export default function Page() {
  return <App />;
}
