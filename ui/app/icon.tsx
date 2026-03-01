import { ImageResponse } from "next/og";

export const size = { width: 32, height: 32 };
export const contentType = "image/png";

export default function Icon() {
  return new ImageResponse(
    (
      <div
        style={{
          width: 32,
          height: 32,
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          background: "#06060a",
          borderRadius: 6,
        }}
      >
        <svg
          width="26"
          height="26"
          viewBox="0 0 48 48"
          fill="none"
          xmlns="http://www.w3.org/2000/svg"
        >
          {/* Octagonal frame */}
          <path
            d="M18 4h12l10 10v12l-10 10H18L8 26V14L18 4Z"
            stroke="#06b6d4"
            strokeWidth="3"
            strokeLinecap="round"
            strokeLinejoin="round"
            fill="none"
          />
          {/* Open edge gap */}
          <path
            d="M30 4l10 10"
            stroke="#06060a"
            strokeWidth="5"
            strokeLinecap="round"
          />
          {/* Beacon arcs */}
          <path
            d="M18.5 29.5a8 8 0 0 1 0-11.3"
            stroke="#06b6d4"
            strokeWidth="2.5"
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
            opacity="0.5"
          />
          {/* Central node */}
          <circle cx="23" cy="24" r="3.5" fill="#06b6d4" />
        </svg>
      </div>
    ),
    { ...size },
  );
}