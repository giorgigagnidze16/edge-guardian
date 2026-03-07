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
          {/* Filled shield */}
          <path
            d="M24 4 L40 12 V28 L24 44 L8 28 V12 Z"
            fill="#06b6d4"
          />
          {/* Hub cutout */}
          <circle cx="24" cy="20" r="4" fill="#06060a" />
          {/* Node cutouts */}
          <circle cx="24" cy="10" r="2.5" fill="#06060a" />
          <circle cx="15" cy="32" r="2.5" fill="#06060a" />
          <circle cx="33" cy="32" r="2.5" fill="#06060a" />
        </svg>
      </div>
    ),
    { ...size },
  );
}
