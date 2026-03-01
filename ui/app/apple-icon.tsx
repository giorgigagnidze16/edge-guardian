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
          {/* Octagonal frame */}
          <path
            d="M18 4h12l10 10v12l-10 10H18L8 26V14L18 4Z"
            stroke="#06b6d4"
            strokeWidth="2.5"
            strokeLinecap="round"
            strokeLinejoin="round"
            fill="none"
          />
          {/* Open edge gap */}
          <path
            d="M30 4l10 10"
            stroke="#080e18"
            strokeWidth="4.5"
            strokeLinecap="round"
          />
          {/* Beacon arcs */}
          <path
            d="M18.5 29.5a8 8 0 0 1 0-11.3"
            stroke="#06b6d4"
            strokeWidth="2"
            strokeLinecap="round"
            fill="none"
            opacity="0.9"
          />
          <path
            d="M14.5 33.5a14 14 0 0 1 0-19.8"
            stroke="#06b6d4"
            strokeWidth="2"
            strokeLinecap="round"
            fill="none"
            opacity="0.55"
          />
          <path
            d="M10.5 37.5a20 20 0 0 1 0-28.3"
            stroke="#06b6d4"
            strokeWidth="1.5"
            strokeLinecap="round"
            fill="none"
            opacity="0.25"
          />
          {/* Central node */}
          <circle cx="23" cy="24" r="3" fill="#06b6d4" />
          {/* Edge device dot */}
          <circle cx="35" cy="24" r="1.5" fill="#06b6d4" opacity="0.5" />
        </svg>
      </div>
    ),
    { ...size },
  );
}