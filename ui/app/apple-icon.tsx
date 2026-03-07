import { ImageResponse } from "next/og";

export const size = { width: 180, height: 180 };
export const contentType = "image/png";

export default function AppleIcon() {
  return new ImageResponse(
    (
      <div
        style={{
          width: 180,
          height: 180,
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          background: "linear-gradient(135deg, #06060a 0%, #0c1220 100%)",
          borderRadius: 40,
        }}
      >
        <svg
          width="120"
          height="120"
          viewBox="0 0 48 48"
          fill="none"
          xmlns="http://www.w3.org/2000/svg"
        >
          {/* Filled shield */}
          <path
            d="M24 4 L40 12 V28 L24 44 L8 28 V12 Z"
            fill="#06b6d4"
          />
          {/* Spoke cutouts */}
          <path
            d="M24 20 L15 32 M24 20 L33 32 M24 20 V10"
            stroke="#080e18"
            strokeWidth="2"
            strokeLinecap="round"
          />
          {/* Hub cutout */}
          <circle cx="24" cy="20" r="4" fill="#080e18" />
          {/* Node cutouts */}
          <circle cx="24" cy="10" r="2.5" fill="#080e18" />
          <circle cx="15" cy="32" r="2.5" fill="#080e18" />
          <circle cx="33" cy="32" r="2.5" fill="#080e18" />
        </svg>
      </div>
    ),
    { ...size },
  );
}
