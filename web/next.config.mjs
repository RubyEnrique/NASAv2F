/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  async headers() {
    const longCache = [{ key: "Cache-Control", value: "public, max-age=31536000, immutable" }];
    return [
      { source: "/models/:path*", headers: longCache },
      { source: "/mediapipe-wasm/:path*", headers: longCache },
      { source: "/mars_mola_tiles/:path*", headers: longCache },
      // Camera, microphone, motion and location are used by this page only.
      { source: "/:path*", headers: [{ key: "Permissions-Policy", value: "camera=(self), microphone=(self), geolocation=(self), accelerometer=(self), gyroscope=(self), magnetometer=(self)" }] },
    ];
  },
};
export default nextConfig;
