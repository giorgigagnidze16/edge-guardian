"use client";

import { useState, useEffect, useRef, useCallback } from "react";
import { signIn } from "next-auth/react";
import { useTheme } from "next-themes";
import { Sun, Moon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { LogoIcon } from "@/components/logo";

/* ═══════════════════════════════════════════════════════════
 *  Static Data
 * ═══════════════════════════════════════════════════════════ */

const TERMINAL_LINES: Array<{ type: "cmd" | "out" | "gap"; text: string; cls?: string }> = [
  { type: "cmd", text: "ssh edge@rpi-gateway-01" },
  { type: "out", text: "Connected to rpi-gateway-01 (192.168.1.42)", cls: "text-emerald-400" },
  { type: "gap", text: "" },
  { type: "cmd", text: "edgeguard status" },
  { type: "out", text: "● EdgeGuardian Agent v2.4.1", cls: "font-semibold text-cyan-300" },
  { type: "out", text: "  Status:     active (running)" },
  { type: "out", text: "  Uptime:     14d 6h 32m" },
  { type: "out", text: "  Fleet:      connected (1,847 devices)" },
  { type: "out", text: "  Last sync:  2s ago" },
  { type: "gap", text: "" },
  { type: "cmd", text: "edgeguard deploy --rolling firmware-v2.5.0" },
  { type: "out", text: "✓ Artifact signature verified (sha256:a3f8…c291)", cls: "text-emerald-400" },
  { type: "out", text: "▸ Rolling deployment to 847 devices…", cls: "text-cyan-400" },
  { type: "out", text: "  ████████████████░░░░  78% complete", cls: "text-cyan-300/80" },
  { type: "out", text: "  661 updated · 183 pending · 3 queued" },
];

const MOCK_DEVICES = [
  { name: "rpi-gateway-01", status: "online", cpu: "23%", mem: "41%", region: "us-west" },
  { name: "jetson-ai-07", status: "online", cpu: "67%", mem: "72%", region: "eu-central" },
  { name: "esp32-temp-04", status: "degraded", cpu: "89%", mem: "34%", region: "ap-south" },
  { name: "nuc-edge-05", status: "online", cpu: "12%", mem: "28%", region: "us-east" },
  { name: "rpi-sensor-02", status: "online", cpu: "45%", mem: "55%", region: "eu-west" },
];

const CHART_BARS = [40, 55, 38, 72, 65, 48, 82, 58, 45, 90, 75, 62, 50, 68, 78, 42, 85, 55, 70, 60];

const LOG_ENTRIES = [
  { time: "14:32:01", level: "info", msg: "Heartbeat received from rpi-gateway-01", src: "fleet" },
  { time: "14:32:03", level: "warn", msg: "CPU threshold exceeded (89%) on esp32-temp-04", src: "health" },
  { time: "14:32:05", level: "info", msg: "Firmware v2.5.0 verified, initiating install", src: "ota" },
  { time: "14:32:06", level: "info", msg: "Desired state reconciled on jetson-ai-07", src: "reconciler" },
  { time: "14:32:08", level: "info", msg: "OTA rollout 92% complete for fleet-west", src: "deploy" },
];

const ARCH_ITEMS: PlatformItem[] = [
  { name: "ARM64", detail: "aarch64", icon: "arm" },
  { name: "ARMv7", detail: "armhf", icon: "arm" },
  { name: "x86_64", detail: "amd64", icon: "intel" },
  { name: "RISC-V", detail: "riscv64", icon: "riscv" },
];

const OS_ITEMS: PlatformItem[] = [
  { name: "Linux", icon: "linux" },
  { name: "Raspberry Pi", icon: "raspberrypi" },
  { name: "Ubuntu", icon: "ubuntu" },
  { name: "Debian", icon: "debian" },
  { name: "Alpine Linux", icon: "alpine" },
  { name: "NVIDIA Jetson", icon: "nvidia" },
  { name: "Intel NUC", icon: "intel" },
  { name: "ESP32", icon: "espressif" },
];

interface PlatformItem {
  name: string;
  detail?: string;
  icon: string;
}

/* Icons that use full multi-color SVG files (complex mascots) */
const ICON_FILE: Record<string, string> = {
  linux: "/icons/linux.svg",
  raspberrypi: "/icons/raspberrypi.svg",
};

/* Theme-aware icon colors for single-path SVG icons */
const ICON_COLOR: Record<string, string> = {
  ubuntu:      "text-[#E95420]",
  debian:      "text-[#A81D33] dark:text-[#D64468]",
  alpine:      "text-[#0D597F]",
  nvidia:      "text-[#76B900]",
  intel:       "text-[#0071C5]",
  arm:         "text-[#0091BD]",
  espressif:   "text-[#E7352C]",
  riscv:       "text-[#283272] dark:text-[#6478D8]",
};

/* Brand SVG paths (Simple Icons, CC0 license, 24x24 viewBox) */
const PLATFORM_ICONS: Record<string, string> = {
  linux:
    "M12.504 0c-.155 0-.315.008-.48.021-4.226.333-3.105 4.807-3.17 6.298-.076 1.092-.3 1.953-1.05 3.02-.885 1.051-2.127 2.75-2.716 4.521-.278.832-.41 1.684-.287 2.489a.424.424 0 00-.11.135c-.26.268-.45.6-.663.839-.199.199-.485.267-.797.4-.313.136-.658.269-.864.68-.09.189-.136.394-.132.602 0 .199.027.4.055.536.058.399.116.728.04.97-.249.68-.28 1.145-.106 1.484.174.334.535.47.94.601.81.2 1.91.135 2.774.6.926.466 1.866.67 2.616.47.526-.116.97-.464 1.208-.946.587-.003 1.23-.269 2.26-.334.699-.058 1.574.267 2.577.2.025.134.063.198.114.333l.003.003c.391.778 1.113 1.132 1.884 1.071.771-.06 1.592-.536 2.257-1.306.631-.765 1.683-1.084 2.378-1.503.348-.199.629-.469.649-.853.023-.4-.2-.811-.714-1.376v-.097l-.003-.003c-.17-.2-.25-.535-.338-.926-.085-.401-.182-.786-.492-1.046h-.003c-.059-.054-.123-.067-.188-.135a.357.357 0 00-.19-.064c.431-1.278.264-2.55-.173-3.694-.533-1.41-1.465-2.638-2.175-3.483-.796-1.005-1.576-1.957-1.56-3.368.026-2.152.236-6.133-3.544-6.139z",
  raspberrypi:
    "m19.8955 10.8961-.1726-.3028c.0068-2.1746-1.0022-3.061-2.1788-3.7348.356-.0938.7237-.1711.8245-.6182.6118-.1566.7397-.4398.8011-.7398.16-.1066.6955-.4061.6394-.9211.2998-.2069.4669-.4725.3819-.8487.3222-.3515.407-.6419.2702-.9096.3868-.4805.2152-.7295.05-.9817.2897-.5254.0341-1.0887-.7758-.9944-.3221-.4733-1.0244-.3659-1.133-.3637-.1215-.1519-.2819-.2821-.7755-.219-.3197-.2851-.6771-.2364-1.0458-.0964-.4378-.3403-.7275-.0675-1.0584.0356-.53-.1706-.6513.0631-.9117.1583-.5781-.1203-.7538.1416-1.0309.4182l-.3224-.0063c-.8719.5061-1.305 1.5366-1.4585 2.0664-.1536-.5299-.5858-1.5604-1.4575-2.0664l-.3223.0063C9.942.5014 9.7663.2394 9.1883.3597c-.2604-.0951-.3813-.3288-.9117-.1583-.2172-.0677-.417-.2084-.6522-.2012l.0004.0002C7.5017.0041 7.369.049 7.2185.166c-.3688-.1401-.7262-.1887-1.0459.0964-.4936-.0631-.654.0671-.7756.219C5.2887.4791 4.5862.3717 4.264.845c-.8096-.0943-1.0655.4691-.7756.9944-.1653.2521-.3366.5013.05.9819-.1367.2677-.0519.5581.2703.9096-.085.3763.0822.6418.3819.8487-.0561.515.4795.8144.6394.9211.0614.3001.1894.5832.8011.7398.1008.4472.4685.5244.8245.6183-1.1766.6737-2.1856 1.56-2.1788 3.7348l-.1724.3028c-1.3491.8082-2.5629 3.4056-.6648 5.5167.124.6609.3319 1.1355.5171 1.6609.2769 2.117 2.0841 3.1082 2.5608 3.2255.6984.524 1.4423 1.0212 2.449 1.3696.949.964 1.977 1.3314 3.0107 1.3308.0152 0 .0306.0002.0457 0 1.0337.0006 2.0618-.3668 3.0107-1.3308 1.0067-.3483 1.7506-.8456 2.4491-1.3696.4766-.1173 2.2838-1.1085 2.5607-3.2255.1851-.5253.3931-1 .517-1.6609 1.8981-2.1113.6843-4.7089-.6649-5.517z",
  ubuntu:
    "M17.61.455a3.41 3.41 0 00-3.41 3.41 3.41 3.41 0 003.41 3.41 3.41 3.41 0 003.41-3.41 3.41 3.41 0 00-3.41-3.41zM12.92.8C8.923.777 5.137 2.941 3.148 6.451a4.5 4.5 0 01.26-.007 4.92 4.92 0 012.585.737A8.316 8.316 0 0112.688 3.6 4.944 4.944 0 0113.723.834 11.008 11.008 0 0012.92.8zm9.226 4.994a4.915 4.915 0 01-1.918 2.246 8.36 8.36 0 01-.273 8.303 4.89 4.89 0 011.632 2.54 11.156 11.156 0 00.559-13.089zM3.41 7.932A3.41 3.41 0 000 11.342a3.41 3.41 0 003.41 3.409 3.41 3.41 0 003.41-3.41 3.41 3.41 0 00-3.41-3.41zm2.027 7.866a4.908 4.908 0 01-2.915.358 11.1 11.1 0 007.991 6.698 11.234 11.234 0 002.422.249 4.879 4.879 0 01-.999-2.85 8.484 8.484 0 01-.836-.136 8.304 8.304 0 01-5.663-4.32zm11.405.928a3.41 3.41 0 00-3.41 3.41 3.41 3.41 0 003.41 3.41 3.41 3.41 0 003.41-3.41 3.41 3.41 0 00-3.41-3.41z",
  debian:
    "M13.88 12.685c-.4 0 .08.2.601.28.14-.1.27-.22.39-.33a3 3 0 01-.99.05m2.14-.53c.23-.33.4-.69.47-1.06-.06.27-.2.5-.33.73-.75.47-.07-.27 0-.56-.8 1.01-.11.6-.14.89m.781-2.05c.05-.721-.14-.501-.2-.221.07.04.13.5.2.22M12.38.31c.2.04.45.07.42.12.23-.05.28-.1-.43-.12m.43.12l-.15.03.14-.01V.43m6.633 9.944c.02.64-.2.95-.38 1.5l-.35.181c-.28.54.03.35-.17.78-.44.39-1.34 1.22-1.62 1.301-.201 0 .14-.25.19-.34-.591.4-.481.6-1.371.85l-.03-.06c-2.221 1.04-5.303-1.02-5.253-3.842-.03.17-.07.13-.12.2a3.551 3.552 0 012.001-3.501 3.361 3.362 0 013.732.48 3.341 3.342 0 00-2.721-1.3c-1.18.01-2.281.76-2.651 1.57-.6.38-.67 1.47-.93 1.661-.361 2.601.66 3.722 2.38 5.042.27.19.08.21.12.35a4.702 4.702 0 01-1.53-1.16c.23.33.47.66.8.91-.55-.18-1.27-1.3-1.48-1.35.93 1.66 3.78 2.921 5.261 2.3a6.203 6.203 0 01-2.33-.28c-.33-.16-.77-.51-.7-.57a5.802 5.803 0 005.902-.84c.44-.35.93-.94 1.07-.95-.2.32.04.16-.12.44.44-.72-.2-.3.46-1.24l.24.33c-.09-.6.74-1.321.66-2.262.19-.3.2.3 0 .97.29-.74.08-.85.15-1.46.08.2.18.42.23.63-.18-.7.2-1.2.28-1.6-.09-.05-.28.3-.32-.53 0-.37.1-.2.14-.28-.08-.05-.26-.32-.38-.861.08-.13.22.33.34.34-.08-.42-.2-.75-.2-1.08-.34-.68-.12.1-.4-.3-.34-1.091.3-.25.34-.74.54.77.84 1.96.981 2.46-.1-.6-.28-1.2-.49-1.76.16.07-.26-1.241.21-.37A7.823 7.824 0 0017.702 1.6c.18.17.42.39.33.42-.75-.45-.62-.48-.73-.67-.61-.25-.65.02-1.06 0C15.082.73 14.862.8 13.8.4l.05.23c-.77-.25-.9.1-1.73 0-.05-.04.27-.14.53-.18-.741.1-.701-.14-1.431.03.17-.13.36-.21.55-.32-.6.04-1.44.35-1.18.07C9.6.68 7.847 1.3 6.867 2.22L6.838 2c-.45.54-1.96 1.611-2.08 2.311l-.131.03c-.23.4-.38.85-.57 1.261-.3.52-.45.2-.4.28-.6 1.22-.9 2.251-1.16 3.102.18.27 0 1.65.07 2.76-.3 5.463 3.84 10.776 8.363 12.006.67.23 1.65.23 2.49.25-.99-.28-1.12-.15-2.08-.49-.7-.32-.85-.7-1.34-1.13l.2.35c-.971-.34-.57-.42-1.361-.67l.21-.27c-.31-.03-.83-.53-.97-.81l-.34.01c-.41-.501-.63-.871-.61-1.161l-.111.2c-.13-.21-1.52-1.901-.8-1.511-.13-.12-.31-.2-.5-.55l.14-.17c-.35-.44-.64-1.02-.62-1.2.2.24.32.3.45.33-.88-2.172-.93-.12-1.601-2.202l.15-.02c-.1-.16-.18-.34-.26-.51l.06-.6c-.63-.74-.18-3.102-.09-4.402.07-.54.53-1.1.88-1.981l-.21-.04c.4-.71 2.341-2.872 3.241-2.761.43-.55-.09 0-.18-.14.96-.991 1.26-.7 1.901-.88.7-.401-.6.16-.27-.151 1.2-.3.85-.7 2.421-.85.16.1-.39.14-.52.26 1-.49 3.151-.37 4.562.27 1.63.77 3.461 3.011 3.531 5.132l.08.02c-.04.85.13 1.821-.17 2.711l.2-.42",
  alpine:
    "M5.998 1.607L0 12l5.998 10.393h12.004L24 12 18.002 1.607H5.998zM9.965 7.12L12.66 9.9l1.598 1.595.002-.002 2.41 2.363c-.2.14-.386.252-.563.344a3.756 3.756 0 01-.496.217 2.702 2.702 0 01-.425.111c-.131.023-.25.034-.358.034-.13 0-.242-.014-.338-.034a1.317 1.317 0 01-.24-.072.95.95 0 01-.2-.113l-1.062-1.092-3.039-3.041-1.1 1.053-3.07 3.072a.974.974 0 01-.2.111 1.274 1.274 0 01-.237.073c-.096.02-.209.033-.338.033-.108 0-.227-.009-.358-.031a2.7 2.7 0 01-.425-.114 3.748 3.748 0 01-.496-.217 5.228 5.228 0 01-.563-.343l6.803-6.727zm4.72.785l4.579 4.598 1.382 1.353a5.24 5.24 0 01-.564.344 3.73 3.73 0 01-.494.217 2.697 2.697 0 01-.426.111c-.13.023-.251.034-.36.034-.129 0-.241-.014-.337-.034a1.285 1.285 0 01-.385-.146c-.033-.02-.05-.036-.053-.04l-1.232-1.218-2.111-2.111-.334.334L12.79 9.8l1.896-1.897zm-5.966 4.12v2.529a2.128 2.128 0 01-.356-.035 2.765 2.765 0 01-.422-.116 3.708 3.708 0 01-.488-.214 5.217 5.217 0 01-.555-.34l1.82-1.825Z",
  nvidia:
    "M8.948 8.798v-1.43a6.7 6.7 0 01.424-.018c3.922-.124 6.493 3.374 6.493 3.374s-2.774 3.851-5.75 3.851c-.398 0-.787-.062-1.158-.185v-4.346c1.528.185 1.837.857 2.747 2.385l2.04-1.714s-1.492-1.952-4-1.952a6.016 6.016 0 00-.796.035m0-4.735v2.138l.424-.027c5.45-.185 9.01 4.47 9.01 4.47s-4.08 4.964-8.33 4.964c-.37 0-.733-.035-1.095-.097v1.325c.3.035.61.062.91.062 3.957 0 6.82-2.023 9.593-4.408.459.371 2.34 1.263 2.73 1.652-2.633 2.208-8.772 3.984-12.253 3.984-.335 0-.653-.018-.971-.053v1.864H24V4.063zm0 10.326v1.131c-3.657-.654-4.673-4.46-4.673-4.46s1.758-1.944 4.673-2.262v1.237H8.94c-1.528-.186-2.73 1.245-2.73 1.245s.68 2.412 2.739 3.11M2.456 10.9s2.164-3.197 6.5-3.533V6.201C4.153 6.59 0 10.653 0 10.653s2.35 6.802 8.948 7.42v-1.237c-4.84-.6-6.492-5.936-6.492-5.936z",
  intel:
    "M20.42 7.345v9.18h1.651v-9.18zM0 7.475v1.737h1.737V7.474zm9.78.352v6.053c0 .513.044.945.13 1.292.087.34.235.618.44.828.203.21.475.359.803.451.334.093.754.136 1.255.136h.216v-1.533c-.24 0-.445-.012-.593-.037a.672.672 0 01-.39-.173.693.693 0 01-.173-.377 4.002 4.002 0 01-.037-.606v-2.182h1.193v-1.416h-1.193V7.827zm-3.505 2.312c-.396 0-.76.08-1.082.241-.327.161-.6.384-.822.668l-.087.117v-.902H2.658v6.256h1.639v-3.214c.018-.588.16-1.02.433-1.299.29-.297.642-.445 1.044-.445.476 0 .841.149 1.082.433.235.284.359.686.359 1.2v3.324h1.663V12.97c.006-.89-.229-1.595-.686-2.09-.458-.495-1.1-.742-1.917-.742zm10.065.006a3.252 3.252 0 00-2.306.946c-.29.29-.525.637-.692 1.033a3.145 3.145 0 00-.254 1.273c0 .452.08.878.241 1.274.161.395.39.742.674 1.032.284.29.637.526 1.045.693.408.173.86.26 1.342.26 1.397 0 2.262-.637 2.782-1.23l-1.187-.904c-.248.297-.841.699-1.583.699-.464 0-.847-.105-1.138-.321a1.588 1.588 0 01-.593-.872l-.019-.056h4.915v-.587c0-.451-.08-.872-.235-1.267a3.393 3.393 0 00-.661-1.033 3.013 3.013 0 00-1.02-.692 3.345 3.345 0 00-1.311-.248zm-16.297.118v6.256h1.651v-6.256zm16.278 1.286c1.132 0 1.664.797 1.664 1.255l-3.32.006c0-.458.525-1.255 1.656-1.261z",
  arm:
    "M5.419 8.534h1.614v6.911H5.419v-.72c-.71.822-1.573.933-2.07.933C1.218 15.658 0 13.882 0 11.985c0-2.253 1.542-3.633 3.37-3.633.507 0 1.4.132 2.049.984zm-3.765 3.491c0 1.198.751 2.202 1.918 2.202 1.015 0 1.959-.74 1.959-2.181 0-1.512-.934-2.233-1.959-2.233-1.167-.01-1.918.974-1.918 2.212zm7.297-3.49h1.613v.618a3 3 0 01.67-.578c.314-.183.619-.233.984-.233.396 0 .822.06 1.269.324l-.66 1.462a1.432 1.432 0 00-.822-.244c-.345 0-.69.05-1.005.376-.446.477-.446 1.136-.446 1.593v3.582H8.94zm5.56 0h1.614v.639c.538-.66 1.177-.822 1.705-.822.72 0 1.4.345 1.786 1.015.579-.822 1.441-1.015 2.05-1.015.842 0 1.573.396 1.969 1.086.132.233.365.74.365 1.745v4.272h-1.614V11.65c0-.771-.08-1.086-.152-1.228-.101-.264-.345-.609-.923-.609-.396 0-.741.213-.954.508-.284.395-.315.984-.315 1.572v3.562H18.43V11.65c0-.771-.081-1.086-.152-1.228-.102-.264-.345-.609-.924-.609-.396 0-.74.213-.954.508-.284.395-.314.984-.314 1.572v3.562h-1.573z",
  espressif:
    "M12.926 19.324a7.6 7.6 0 00-2.983-6.754 7.44 7.44 0 00-3.828-1.554.697.697 0 01-.606-.731.674.674 0 01.743-.617 8.97 8.97 0 018 9.805 7.828 7.828 0 01-.298 1.542l1.989.56a11.039 11.039 0 001.714-.651 12.159 12.159 0 00.217-2.343A12.57 12.57 0 007.212 6.171a5.53 5.53 0 00-2 0 4.354 4.354 0 00-2.16 1.337 4.274 4.274 0 001.909 6.856 9.896 9.896 0 001.074.195 4.011 4.011 0 013.337 3.954 3.965 3.965 0 01-.64 2.16l1.371.88a10.182 10.182 0 002.057.342 7.52 7.52 0 00.754-2.628m.16 4.73A13.073 13.073 0 01.001 10.983 12.982 12.982 0 013.83 1.737l.743.697a12.067 12.067 0 000 17.141 12.067 12.067 0 0017.141 0l.697.697a12.97 12.97 0 01-9.336 3.726M24 10.993A10.993 10.993 0 0012.949 0c-.389 0-.766 0-1.143.057l-.252.732a18.912 18.912 0 0111.588 11.576l.731-.263c0-.366.069-.732.069-1.143m-1.269 5.165A17.53 17.53 0 007.818 1.27a11.119 11.119 0 00-2.457 1.77v1.635A13.919 13.919 0 0119.268 18.57h1.634a11.713 11.713 0 001.771-2.446M7.92 17.884a1.691 1.691 0 11-1.69-1.691 1.691 1.691 0 011.69 1.691",
  riscv:
    "M2.94 3.39h3.98v17.22H2.94zm5.09 0h4.32l3.7 10.49h.06l3.79-10.49h4.16L17.84 20.61h-3.66z",
};

const ST_DOT: Record<string, string> = {
  online: "bg-emerald-400",
  degraded: "bg-amber-400",
  offline: "bg-zinc-600",
};

/* ═══════════════════════════════════════════════════════════
 *  Hooks
 * ═══════════════════════════════════════════════════════════ */

/** IntersectionObserver-based one-shot reveal for `.reveal` elements */
function useScrollReveal() {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const root = ref.current;
    if (!root) return;
    const els = root.querySelectorAll(".reveal");
    if (!els.length) return;
    const io = new IntersectionObserver(
      (entries) =>
        entries.forEach((e) => {
          if (e.isIntersecting) {
            e.target.classList.add("revealed");
            io.unobserve(e.target);
          }
        }),
      { threshold: 0.12, rootMargin: "0px 0px -80px 0px" },
    );
    els.forEach((el) => io.observe(el));
    return () => io.disconnect();
  }, []);
  return ref;
}

/** Returns true once the referenced element enters the viewport */
function useInView(threshold = 0.25) {
  const ref = useRef<HTMLDivElement>(null);
  const [inView, setInView] = useState(false);
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const io = new IntersectionObserver(
      ([entry]) => {
        if (entry.isIntersecting) {
          setInView(true);
          io.disconnect();
        }
      },
      { threshold },
    );
    io.observe(el);
    return () => io.disconnect();
  }, [threshold]);
  return { ref, inView };
}

/** Navbar glass effect after scrolling */
function useNavbarScroll() {
  const [scrolled, setScrolled] = useState(false);
  useEffect(() => {
    const handler = () => setScrolled(window.scrollY > 20);
    handler();
    window.addEventListener("scroll", handler, { passive: true });
    return () => window.removeEventListener("scroll", handler);
  }, []);
  return scrolled;
}

/* ═══════════════════════════════════════════════════════════
 *  Page
 * ═══════════════════════════════════════════════════════════ */

export default function LoginPage() {
  const containerRef = useScrollReveal();
  const scrolled = useNavbarScroll();
  const { setTheme, resolvedTheme } = useTheme();
  const [mounted, setMounted] = useState(false);
  useEffect(() => setMounted(true), []);

  /* Parallax — DOM-level, no re-renders */
  const parallaxRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    let raf: number;
    const onScroll = () => {
      raf = requestAnimationFrame(() => {
        if (parallaxRef.current) {
          parallaxRef.current.style.transform = `translateY(${window.scrollY * 0.12}px)`;
        }
      });
    };
    window.addEventListener("scroll", onScroll, { passive: true });
    return () => {
      window.removeEventListener("scroll", onScroll);
      cancelAnimationFrame(raf);
    };
  }, []);

  /* Terminal trigger */
  const terminal = useInView(0.15);

  const doSignIn = useCallback(() => signIn("keycloak", { callbackUrl: "/" }), []);

  return (
    <div ref={containerRef} className="relative min-h-screen bg-background text-foreground [overflow-x:clip]">
      {/* ── Atmosphere ── */}
      <div className="pointer-events-none fixed inset-0 z-0">
        <div className="absolute -top-40 right-1/4 h-[700px] w-[700px] rounded-full bg-primary/[0.04] dark:bg-cyan-500/[0.07] blur-[200px]" />
        <div className="absolute top-2/3 -left-20 h-[600px] w-[600px] rounded-full bg-violet-500/[0.03] dark:bg-violet-500/[0.05] blur-[180px]" />
        <div className="absolute bottom-0 right-0 h-[500px] w-[500px] rounded-full bg-emerald-500/[0.02] dark:bg-emerald-500/[0.03] blur-[160px]" />
        {/* Grid — light mode */}
        <div
          className="absolute inset-0 dark:hidden opacity-[0.035]"
          style={{
            backgroundImage:
              "linear-gradient(rgba(0,0,0,.06) 1px, transparent 1px), linear-gradient(90deg, rgba(0,0,0,.06) 1px, transparent 1px)",
            backgroundSize: "80px 80px",
          }}
        />
        {/* Grid — dark mode */}
        <div
          className="absolute inset-0 hidden dark:block opacity-[0.02]"
          style={{
            backgroundImage:
              "linear-gradient(rgba(255,255,255,.1) 1px, transparent 1px), linear-gradient(90deg, rgba(255,255,255,.1) 1px, transparent 1px)",
            backgroundSize: "80px 80px",
          }}
        />
      </div>

      {/* ══════════════════════════════════════════════
       *  NAVBAR
       * ══════════════════════════════════════════════ */}
      <nav
        className={`fixed top-0 z-50 w-full transition-all duration-500 ${
          scrolled
            ? "backdrop-blur-xl bg-background/80 border-b border-border/50 shadow-sm"
            : "bg-transparent border-b border-transparent"
        }`}
      >
        <div className="mx-auto flex max-w-7xl items-center justify-between px-6 py-3.5 sm:px-12 lg:px-16">
          <div className="flex items-center gap-3">
            <LogoIcon size={36} />
            <span className="text-xl font-bold tracking-tight">
              Edge<span className="text-primary">Guardian</span>
            </span>
          </div>

          <div className="hidden md:flex items-center gap-8 text-sm font-medium text-muted-foreground">
            {[
              { label: "Features", id: "features" },
              { label: "Security", id: "security" },
              { label: "Updates", id: "updates" },
            ].map((link) => (
              <a
                key={link.id}
                href={`#${link.id}`}
                className="cursor-pointer hover:text-foreground transition-colors duration-200"
              >
                {link.label}
              </a>
            ))}
          </div>

          <div className="flex items-center gap-3">
            {mounted && (
              <button
                onClick={() => setTheme(resolvedTheme === "dark" ? "light" : "dark")}
                className="cursor-pointer rounded-xl p-2.5 text-muted-foreground hover:text-foreground hover:bg-muted/50 transition-all duration-200"
                aria-label="Toggle theme"
              >
                {resolvedTheme === "dark" ? <Sun size={20} /> : <Moon size={20} />}
              </button>
            )}
            <Button size="default" className="px-6" onClick={doSignIn}>
              Get Started
            </Button>
          </div>
        </div>
      </nav>

      {/* ══════════════════════════════════════════════
       *  HERO
       * ══════════════════════════════════════════════ */}
      <section className="relative z-10 mx-auto max-w-7xl px-6 pt-32 pb-24 sm:px-12 lg:px-16 lg:pt-40 min-h-screen flex items-center">
        <div className="grid lg:grid-cols-[1fr_1.2fr] gap-16 lg:gap-24 items-center w-full">
          {/* Copy */}
          <div className="reveal reveal-up">
            <p className="text-sm font-semibold text-primary uppercase tracking-widest mb-6">
              Kubernetes-style orchestration for IoT
            </p>

            <h1 className="text-4xl font-bold tracking-tight sm:text-5xl lg:text-6xl leading-[1.1]">
              Deploy to thousands.
              <br />
              <span className="text-gradient">Monitor everything.</span>
            </h1>

            <p className="mt-6 max-w-lg text-lg text-muted-foreground leading-relaxed">
              Sub-5MB agents that self-heal. Declarative YAML config. OTA
              updates with automatic rollback. Built for ARM, x86, and
              everything in between.
            </p>

            <div className="mt-10 flex flex-wrap gap-4">
              <Button size="lg" className="px-10 text-base font-bold h-13" onClick={doSignIn}>
                Get Started Free
              </Button>
              <Button
                variant="outline"
                size="lg"
                className="px-10 text-base h-13"
                onClick={() => document.getElementById("terminal")?.scrollIntoView({ behavior: "smooth" })}
              >
                See It In Action
              </Button>
            </div>

          </div>

          {/* Dashboard Mockup with parallax */}
          <div className="reveal reveal-right hidden lg:block">
            <div ref={parallaxRef} className="will-change-transform">
              <DashboardMockup />
            </div>
          </div>
        </div>
      </section>

      {/* ══════════════════════════════════════════════
       *  METRICS STRIP
       * ══════════════════════════════════════════════ */}
      <section className="relative z-10 mx-auto max-w-6xl px-6 sm:px-12 lg:px-16">
        <div className="reveal reveal-up grid grid-cols-2 lg:grid-cols-4 gap-px rounded-2xl border border-border bg-border overflow-hidden">
          {[
            { value: "1,847", label: "Devices Managed", suffix: "" },
            { value: "99.9", label: "Uptime SLA", suffix: "%" },
            { value: "< 5", label: "Agent Binary", suffix: " MB" },
            { value: "30", label: "Reconcile Cycle", suffix: "s" },
          ].map((s) => (
            <div key={s.label} className="bg-background px-6 py-8 sm:px-8 sm:py-10 text-center">
              <div className="text-3xl sm:text-4xl lg:text-5xl font-black tracking-tight tabular-nums">
                {s.value}
                <span className="text-primary">{s.suffix}</span>
              </div>
              <div className="mt-2 text-sm text-muted-foreground font-medium uppercase tracking-wider">
                {s.label}
              </div>
            </div>
          ))}
        </div>
      </section>

      {/* ══════════════════════════════════════════════
       *  SUPPORTED PLATFORMS — horizontal marquee
       * ══════════════════════════════════════════════ */}
      <section className="relative z-10 pt-28 lg:pt-36">
        <div className="reveal reveal-up text-center mb-12 px-6">
          <SectionLabel>Compatibility</SectionLabel>
          <h2 className="text-3xl sm:text-4xl font-bold tracking-tight">
            Runs <span className="text-gradient">everywhere</span>
          </h2>
          <p className="mt-3 text-lg text-muted-foreground max-w-xl mx-auto">
            From single-board computers to industrial gateways — one agent binary, every platform.
          </p>
        </div>

        <div className="reveal reveal-up space-y-5">
          {/* Row 1 — OS & Devices (scroll left) */}
          <MarqueeRow items={OS_ITEMS} direction="left" baseSpeed={45} />
          {/* Row 2 — Architectures (scroll right) */}
          <MarqueeRow items={ARCH_ITEMS} direction="right" baseSpeed={40} />
        </div>
      </section>

      {/* ══════════════════════════════════════════════
       *  INTERACTIVE TERMINAL
       * ══════════════════════════════════════════════ */}
      <section id="terminal" className="relative z-10 mt-32 lg:mt-40">
        {/* Dark background bleed */}
        <div className="absolute inset-0 bg-[#06060c] dark:bg-transparent" />
        <div className="absolute inset-0 dark:bg-card/50" />

        <div className="relative mx-auto max-w-7xl px-6 py-24 sm:px-12 lg:px-16 lg:py-32">
          <div className="reveal reveal-up text-center mb-16">
            <SectionLabel>Live Demo</SectionLabel>
            <h2 className="text-3xl sm:text-4xl lg:text-5xl font-bold tracking-tight text-white dark:text-foreground">
              See it in action
            </h2>
            <p className="mt-4 text-lg text-zinc-400 dark:text-muted-foreground max-w-2xl mx-auto">
              Connect to any device via web terminal or SSH. Deploy firmware updates.
              Monitor fleet health — all from a single interface.
            </p>
          </div>

          <div ref={terminal.ref} className="reveal reveal-scale">
            <TerminalVisual active={terminal.inView} />
          </div>
        </div>
      </section>

      {/* ══════════════════════════════════════════════
       *  FEATURES — 3 Cards
       * ══════════════════════════════════════════════ */}
      <section id="features" className="relative z-10 mx-auto max-w-7xl px-6 py-32 lg:py-40 sm:px-12 lg:px-16">
        <div className="reveal reveal-up text-center mb-20">
          <SectionLabel>Platform</SectionLabel>
          <h2 className="text-3xl sm:text-4xl lg:text-5xl font-bold tracking-tight">
            Everything you need for
            <span className="text-gradient"> edge operations</span>
          </h2>
          <p className="mt-4 text-lg text-muted-foreground max-w-2xl mx-auto">
            A complete platform for managing IoT devices at any scale — from prototypes to production fleets.
          </p>
        </div>

        <div className="grid gap-6 lg:grid-cols-3">
          {/* Fleet Dashboard */}
          <div className="reveal reveal-up group relative rounded-2xl border border-border bg-card p-8 overflow-hidden transition-all duration-300 hover:border-primary/30 hover:shadow-lg hover:shadow-primary/5 hover:-translate-y-1">
            <div className="absolute top-0 left-0 right-0 h-px bg-gradient-to-r from-transparent via-primary/40 to-transparent opacity-0 group-hover:opacity-100 transition-opacity duration-300" />
            <SectionLabel>Fleet Dashboard</SectionLabel>
            <h3 className="text-2xl font-bold mb-3">Every device. One view.</h3>
            <p className="text-base text-muted-foreground leading-relaxed mb-8">
              Real-time visibility into your entire fleet. Health scores, resource
              metrics, connection status — filter and drill into any device instantly.
            </p>
            {/* Device list visual */}
            <div className="rounded-xl bg-[#0a0a14] dark:bg-background border border-white/[0.06] dark:border-border p-4">
              <div className="grid grid-cols-3 gap-2 mb-3">
                {[
                  { v: "1,847", l: "total", c: "text-white dark:text-foreground" },
                  { v: "1,823", l: "online", c: "text-emerald-400" },
                  { v: "24", l: "attention", c: "text-amber-400" },
                ].map((m) => (
                  <div key={m.l} className="rounded-lg bg-white/[0.04] dark:bg-muted/30 px-3 py-2 text-center">
                    <div className={`text-sm font-bold ${m.c}`}>{m.v}</div>
                    <div className="text-[11px] text-zinc-500 dark:text-muted-foreground">{m.l}</div>
                  </div>
                ))}
              </div>
              <div className="space-y-1.5">
                {MOCK_DEVICES.map((d) => (
                  <div
                    key={d.name}
                    className="flex items-center gap-3 rounded-lg bg-white/[0.02] dark:bg-muted/20 px-3 py-2"
                  >
                    <div className={`h-2 w-2 rounded-full shrink-0 ${ST_DOT[d.status]}`} />
                    <span className="text-sm text-zinc-300 dark:text-muted-foreground font-mono truncate">
                      {d.name}
                    </span>
                    <span className="ml-auto text-xs text-zinc-500 dark:text-muted-foreground/60 shrink-0 font-mono">
                      {d.cpu}
                    </span>
                  </div>
                ))}
              </div>
            </div>
          </div>

          {/* OTA Updates */}
          <div id="updates" className="reveal reveal-up group relative rounded-2xl border border-border bg-card p-8 overflow-hidden transition-all duration-300 hover:border-primary/30 hover:shadow-lg hover:shadow-primary/5 hover:-translate-y-1" style={{ transitionDelay: "100ms" }}>
            <div className="absolute top-0 left-0 right-0 h-px bg-gradient-to-r from-transparent via-primary/40 to-transparent opacity-0 group-hover:opacity-100 transition-opacity duration-300" />
            <SectionLabel>OTA Updates</SectionLabel>
            <h3 className="text-2xl font-bold mb-3">Ship fearlessly.</h3>
            <p className="text-base text-muted-foreground leading-relaxed mb-8">
              Rolling, canary, and immediate deployment strategies. Signed artifacts
              with integrity verification. Automatic rollback on failure.
            </p>
            {/* Deployment visual */}
            <div className="rounded-xl bg-[#0a0a14] dark:bg-background border border-white/[0.06] dark:border-border p-5">
              <div className="flex items-center justify-between mb-3">
                <span className="text-sm text-zinc-200 dark:text-foreground font-medium font-mono">
                  firmware-v2.5.0
                </span>
                <span className="text-xs text-primary font-semibold px-2.5 py-1 rounded-full bg-primary/10">
                  Rolling
                </span>
              </div>
              <div className="h-2.5 bg-white/[0.06] dark:bg-muted rounded-full overflow-hidden mb-3">
                <div className="h-full rounded-full bg-gradient-to-r from-primary/80 to-primary w-[78%] landing-progress" />
              </div>
              <div className="flex justify-between text-sm text-zinc-400 dark:text-muted-foreground mb-4">
                <span>78% complete</span>
                <span>3 remaining</span>
              </div>
              <div className="flex gap-1">
                {Array.from({ length: 16 }).map((_, i) => (
                  <div
                    key={i}
                    className={`h-2 flex-1 rounded-full transition-all duration-500 ${
                      i < 12
                        ? "bg-emerald-500/60"
                        : i < 13
                          ? "bg-primary/60 landing-pulse"
                          : "bg-white/[0.06] dark:bg-muted"
                    }`}
                  />
                ))}
              </div>
            </div>
          </div>

          {/* Zero-Trust Security */}
          <div id="security" className="reveal reveal-up group relative rounded-2xl border border-border bg-card p-8 overflow-hidden transition-all duration-300 hover:border-primary/30 hover:shadow-lg hover:shadow-primary/5 hover:-translate-y-1" style={{ transitionDelay: "200ms" }}>
            <div className="absolute top-0 left-0 right-0 h-px bg-gradient-to-r from-transparent via-primary/40 to-transparent opacity-0 group-hover:opacity-100 transition-opacity duration-300" />
            <SectionLabel>Zero-Trust Security</SectionLabel>
            <h3 className="text-2xl font-bold mb-3">Secured from day one.</h3>
            <p className="text-base text-muted-foreground leading-relaxed mb-8">
              Mutual TLS on every connection. Encrypted VPN tunnels. Role-based
              access control with comprehensive audit trail.
            </p>
            {/* Shield visual */}
            <div className="flex items-center justify-center py-8">
              <div className="relative">
                {/* Animated rings */}
                <div className="absolute -inset-16 rounded-full border border-primary/[0.06]" />
                <div className="absolute -inset-11 rounded-full border border-primary/10" />
                <div className="absolute -inset-16 rounded-full border border-primary/[0.08] landing-ring" />
                <div className="absolute -inset-11 rounded-full border border-primary/[0.06] landing-ring" style={{ animationDelay: "1s" }} />
                <div className="relative h-24 w-24 rounded-2xl bg-gradient-to-br from-primary/25 to-primary/[0.03] border border-primary/25 flex items-center justify-center shadow-lg shadow-primary/10">
                  <svg width="44" height="44" viewBox="0 0 24 24" fill="none">
                    <path
                      d="M12 2L4 6v5c0 5.55 3.84 10.74 8 12 4.16-1.26 8-6.45 8-12V6l-8-4z"
                      stroke="currentColor"
                      className="text-primary"
                      strokeWidth="1.5"
                      fill="currentColor"
                      fillOpacity="0.1"
                    />
                    <path
                      d="M9 12l2 2 4-4"
                      stroke="currentColor"
                      className="text-primary"
                      strokeWidth="2"
                      strokeLinecap="round"
                      strokeLinejoin="round"
                    />
                  </svg>
                </div>
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* ══════════════════════════════════════════════
       *  DECLARATIVE CONFIG + OBSERVABILITY
       * ══════════════════════════════════════════════ */}
      <section className="relative z-10 mx-auto max-w-7xl px-6 pb-32 lg:pb-40 sm:px-12 lg:px-16">
        <div className="grid lg:grid-cols-2 gap-8">
          {/* Declarative Config */}
          <div className="reveal reveal-left">
            <div className="h-full rounded-2xl border border-border bg-card p-8 lg:p-10">
              <SectionLabel>Declarative Config</SectionLabel>
              <h3 className="text-2xl font-bold mb-3">
                Define once. Converge always.
              </h3>
              <p className="text-base text-muted-foreground leading-relaxed mb-8">
                Push YAML manifests describing desired device state. The agent
                reconciles automatically — services, files, network config.
              </p>
              {/* YAML mockup */}
              <div className="rounded-xl bg-[#0a0a14] dark:bg-background border border-white/[0.06] dark:border-border p-5 font-mono text-sm leading-relaxed overflow-hidden">
                <div>
                  <span className="text-violet-400">kind</span>
                  <span className="text-zinc-600">:</span>{" "}
                  <span className="text-emerald-400">DeviceManifest</span>
                </div>
                <div>
                  <span className="text-violet-400">spec</span>
                  <span className="text-zinc-600">:</span>
                </div>
                <div className="pl-4">
                  <span className="text-violet-400">services</span>
                  <span className="text-zinc-600">:</span>
                </div>
                <div className="pl-6">
                  <span className="text-zinc-600">- </span>
                  <span className="text-zinc-400">name:</span>{" "}
                  <span className="text-cyan-300">sensor-agent</span>
                </div>
                <div className="pl-8">
                  <span className="text-zinc-400">state:</span>{" "}
                  <span className="text-emerald-400">running</span>
                </div>
                <div className="pl-8">
                  <span className="text-zinc-400">restart:</span>{" "}
                  <span className="text-amber-300">on-failure</span>
                </div>
                <div className="pl-6">
                  <span className="text-zinc-600">- </span>
                  <span className="text-zinc-400">name:</span>{" "}
                  <span className="text-cyan-300">data-relay</span>
                </div>
                <div className="pl-8">
                  <span className="text-zinc-400">state:</span>{" "}
                  <span className="text-emerald-400">running</span>
                </div>
                <div className="pl-4">
                  <span className="text-violet-400">files</span>
                  <span className="text-zinc-600">:</span>
                </div>
                <div className="pl-6">
                  <span className="text-zinc-600">- </span>
                  <span className="text-zinc-400">path:</span>{" "}
                  <span className="text-cyan-300">/etc/edge/config.yaml</span>
                </div>
                <div className="pl-8">
                  <span className="text-zinc-400">mode:</span>{" "}
                  <span className="text-amber-300">0644</span>
                </div>
              </div>
            </div>
          </div>

          {/* Observability */}
          <div className="reveal reveal-right">
            <div className="h-full rounded-2xl border border-border bg-card p-8 lg:p-10">
              <SectionLabel>Observability</SectionLabel>
              <h3 className="text-2xl font-bold mb-3">
                Logs, metrics, insights.
              </h3>
              <p className="text-base text-muted-foreground leading-relaxed mb-8">
                Centralized log aggregation, health dashboards, and real-time
                alerting. Everything you need to understand your fleet.
              </p>
              {/* Log stream mockup */}
              <div className="rounded-xl bg-[#0a0a14] dark:bg-background border border-white/[0.06] dark:border-border p-5 font-mono overflow-hidden">
                <div className="flex items-center gap-2 mb-4">
                  <div className="h-2 w-2 rounded-full bg-emerald-400 landing-pulse" />
                  <span className="text-xs text-zinc-500 dark:text-muted-foreground uppercase tracking-wider font-semibold">
                    Live Log Stream
                  </span>
                </div>
                <div className="space-y-2">
                  {LOG_ENTRIES.map((e, i) => (
                    <div key={i} className="flex items-start gap-3 text-sm leading-relaxed">
                      <span className="text-zinc-600 dark:text-muted-foreground/40 shrink-0 tabular-nums">
                        {e.time}
                      </span>
                      <span
                        className={`shrink-0 uppercase font-semibold tracking-wide text-xs mt-0.5 ${
                          e.level === "warn"
                            ? "text-amber-400"
                            : "text-emerald-400/70"
                        }`}
                      >
                        {e.level}
                      </span>
                      <span className="text-zinc-400 dark:text-muted-foreground">
                        {e.msg}
                      </span>
                    </div>
                  ))}
                </div>
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* ══════════════════════════════════════════════
       *  CTA
       * ══════════════════════════════════════════════ */}
      <section className="relative z-10 mx-auto max-w-5xl px-6 pb-32 sm:px-12">
        <div className="reveal reveal-scale relative overflow-hidden rounded-3xl border border-border">
          <div className="absolute inset-0 bg-gradient-to-br from-primary/[0.08] via-transparent to-violet-500/[0.05]" />
          <div className="absolute top-0 left-1/2 h-px w-2/3 -translate-x-1/2 bg-gradient-to-r from-transparent via-primary/50 to-transparent" />
          <div className="absolute bottom-0 left-1/2 h-px w-1/3 -translate-x-1/2 bg-gradient-to-r from-transparent via-primary/20 to-transparent" />

          <div className="relative z-10 flex flex-col items-center py-20 lg:py-28 px-8 text-center">
            <LogoIcon size={56} />
            <h2 className="mt-8 text-3xl sm:text-4xl lg:text-5xl font-bold tracking-tight">
              Ready to take control?
            </h2>
            <p className="mt-5 max-w-md text-lg text-muted-foreground">
              Sign in to your fleet dashboard or create a new account to get
              started with EdgeGuardian.
            </p>
            <Button
              size="lg"
              className="mt-10 px-12 h-13 text-base font-bold shadow-lg shadow-primary/25 hover:shadow-xl hover:shadow-primary/30"
              onClick={doSignIn}
            >
              Get Started Free
            </Button>
            <p className="mt-5 text-sm text-muted-foreground">
              Google &amp; GitHub SSO available
            </p>
          </div>
        </div>
      </section>

      {/* ══════════════════════════════════════════════
       *  FOOTER
       * ══════════════════════════════════════════════ */}
      <footer className="relative z-10 border-t border-border py-10 px-6 sm:px-12">
        <div className="reveal reveal-up mx-auto max-w-7xl flex flex-col items-center justify-between gap-4 sm:flex-row">
          <div className="flex items-center gap-3">
            <LogoIcon size={24} />
            <span className="text-base font-semibold text-muted-foreground">
              EdgeGuardian
            </span>
          </div>
          <p className="text-sm text-muted-foreground">
            &copy; {new Date().getFullYear()} EdgeGuardian &mdash; IoT Fleet Management
          </p>
        </div>
      </footer>

      {/* ── Landing-specific animations ── */}
      <style>{`
        .landing-bar {
          transform: scaleY(0);
          transform-origin: bottom;
        }
        .revealed .landing-bar {
          animation: barGrow 0.7s ease-out both;
        }
        @keyframes barGrow {
          from { transform: scaleY(0); }
          to   { transform: scaleY(1); }
        }
        .landing-progress {
          width: 0%;
        }
        .revealed .landing-progress {
          animation: progFill 2s ease-out 0.3s both;
        }
        @keyframes progFill {
          from { width: 0%; }
          to   { width: 78%; }
        }
        .landing-pulse {
          animation: lPulse 2s ease-in-out infinite;
        }
        @keyframes lPulse {
          0%, 100% { opacity: 0.5; }
          50%      { opacity: 1; }
        }
        .landing-ring {
          animation: lRing 3s ease-out infinite;
        }
        @keyframes lRing {
          0%   { opacity: 0.15; transform: scale(1); }
          70%  { opacity: 0; transform: scale(1.5); }
          100% { opacity: 0; transform: scale(1.5); }
        }
      `}</style>
    </div>
  );
}

/* ═══════════════════════════════════════════════════════════
 *  Components
 * ═══════════════════════════════════════════════════════════ */

function SectionLabel({ children }: { children: React.ReactNode }) {
  return (
    <div className="text-sm text-primary font-semibold uppercase tracking-widest mb-4">
      {children}
    </div>
  );
}

/* ─── Terminal with typing animation ─── */

function TerminalVisual({ active }: { active: boolean }) {
  const [visibleCount, setVisibleCount] = useState(0);

  useEffect(() => {
    if (!active) return;
    const timers: ReturnType<typeof setTimeout>[] = [];
    let cumulative = 400;

    TERMINAL_LINES.forEach((line, i) => {
      const delay =
        line.type === "cmd"
          ? 500 + line.text.length * 22
          : line.type === "gap"
            ? 150
            : 90;
      cumulative += delay;
      timers.push(setTimeout(() => setVisibleCount(i + 1), cumulative));
    });

    return () => timers.forEach(clearTimeout);
  }, [active]);

  return (
    <div className="mx-auto max-w-4xl rounded-2xl bg-[#0a0a14] border border-white/[0.08] shadow-2xl shadow-black/40 overflow-hidden">
      {/* Window chrome */}
      <div className="flex items-center gap-2.5 px-5 py-3.5 border-b border-white/[0.06] bg-white/[0.02]">
        <div className="h-3 w-3 rounded-full bg-red-500/80" />
        <div className="h-3 w-3 rounded-full bg-amber-500/80" />
        <div className="h-3 w-3 rounded-full bg-emerald-500/80" />
        <span className="ml-4 text-xs text-zinc-500 tracking-wide font-mono">
          edge@rpi-gateway-01 &mdash; edgeguard
        </span>
      </div>

      {/* Terminal body */}
      <div className="p-6 sm:p-8 font-mono text-sm sm:text-base leading-relaxed min-h-[380px]">
        {TERMINAL_LINES.slice(0, visibleCount).map((line, i) => {
          if (line.type === "gap") return <div key={i} className="h-3" />;

          return (
            <div
              key={i}
              className={`${line.cls || "text-zinc-400"} ${
                i === visibleCount - 1 ? "animate-[fadeIn_0.15s_ease-out]" : ""
              }`}
            >
              {line.type === "cmd" && (
                <span className="text-emerald-400 select-none">$ </span>
              )}
              {line.text}
            </div>
          );
        })}

        {/* Blinking cursor */}
        {visibleCount > 0 && (
          <span
            className="inline-block w-2.5 h-5 bg-emerald-400/90 mt-1 align-middle"
            style={{ animation: "cursorBlink 1s step-end infinite" }}
          />
        )}
      </div>
    </div>
  );
}

/* ─── Infinite horizontal marquee ─── */

function MarqueeRow({
  items,
  direction,
  baseSpeed = 50,
}: {
  items: PlatformItem[];
  direction: "left" | "right";
  baseSpeed?: number; /* pixels per second */
}) {
  const repeated: PlatformItem[] = [];
  while (repeated.length < 24) repeated.push(...items);

  const trackRef = useRef<HTMLDivElement>(null);
  const pos = useRef(0);
  const speed = useRef(1);
  const target = useRef(1);
  const init = useRef(false);

  useEffect(() => {
    const el = trackRef.current;
    if (!el) return;
    let raf: number;
    let last = performance.now();
    const half = el.scrollWidth / 2;

    if (!init.current) {
      if (direction === "right") pos.current = -half;
      init.current = true;
    }

    const tick = (now: number) => {
      const dt = Math.min((now - last) / 1000, 0.1);
      last = now;
      speed.current += (target.current - speed.current) * 0.04;
      const move = baseSpeed * speed.current * dt;

      if (direction === "left") {
        pos.current -= move;
        if (pos.current <= -half) pos.current += half;
      } else {
        pos.current += move;
        if (pos.current >= 0) pos.current -= half;
      }

      el.style.transform = `translateX(${pos.current}px)`;
      raf = requestAnimationFrame(tick);
    };

    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [direction, baseSpeed]);

  const onEnter = () => { target.current = 0.2; };
  const onLeave = () => { target.current = 1; };

  const renderPill = (item: PlatformItem, i: number) => (
    <div
      key={i}
      className="flex shrink-0 items-center gap-4 rounded-full border border-border bg-card px-7 py-3.5"
    >
      {ICON_FILE[item.icon] ? (
        /* eslint-disable-next-line @next/next/no-img-element */
        <img
          src={ICON_FILE[item.icon]}
          alt=""
          className="h-9 w-9 shrink-0"
          draggable={false}
        />
      ) : (
        <svg
          viewBox="0 0 24 24"
          className={`h-9 w-9 shrink-0 fill-current ${ICON_COLOR[item.icon] || "text-foreground"}`}
          aria-hidden="true"
        >
          <path d={PLATFORM_ICONS[item.icon] || ""} />
        </svg>
      )}
      <span className="text-lg font-semibold whitespace-nowrap">{item.name}</span>
      {item.detail && (
        <span className="text-sm text-muted-foreground font-mono whitespace-nowrap">
          {item.detail}
        </span>
      )}
    </div>
  );

  return (
    <div
      className="relative overflow-hidden py-1"
      onMouseEnter={onEnter}
      onMouseLeave={onLeave}
      style={{
        maskImage: "linear-gradient(90deg, transparent 0%, black 8%, black 92%, transparent 100%)",
        WebkitMaskImage: "linear-gradient(90deg, transparent 0%, black 8%, black 92%, transparent 100%)",
      }}
    >
      <div ref={trackRef} className="flex w-max gap-5 will-change-transform">
        {repeated.map((item, i) => renderPill(item, i))}
        {repeated.map((item, i) => renderPill(item, i + repeated.length))}
      </div>
    </div>
  );
}

/* ─── Dashboard mockup for hero ─── */

function DashboardMockup() {
  return (
    <div className="relative">
      <div className="absolute inset-0 -m-12 rounded-3xl bg-primary/[0.05] dark:bg-cyan-500/[0.07] blur-[100px]" />
      <div className="relative" style={{ perspective: "1200px" }}>
        <div
          className="rounded-2xl border border-border bg-card dark:bg-[#0a0a14] dark:border-white/[0.08] overflow-hidden shadow-2xl shadow-black/20 dark:shadow-black/50"
          style={{ animation: "floatDashboard 6s ease-in-out infinite" }}
        >
          {/* Window chrome */}
          <div className="flex items-center gap-2 px-5 py-3 border-b border-border dark:border-white/[0.06] bg-muted/30 dark:bg-white/[0.02]">
            <div className="h-3 w-3 rounded-full bg-red-400/80" />
            <div className="h-3 w-3 rounded-full bg-amber-400/80" />
            <div className="h-3 w-3 rounded-full bg-emerald-400/80" />
            <span className="ml-4 text-xs text-muted-foreground dark:text-zinc-500 tracking-wide">
              EdgeGuardian Dashboard
            </span>
          </div>

          <div className="flex">
            {/* Sidebar */}
            <div className="w-12 sm:w-14 shrink-0 border-r border-border dark:border-white/[0.04] py-4 flex flex-col items-center gap-3">
              <div className="w-6 h-6 rounded-lg bg-primary/20 flex items-center justify-center">
                <div className="w-3 h-3 rounded bg-primary/50" />
              </div>
              {[1, 2, 3, 4, 5].map((i) => (
                <div
                  key={i}
                  className={`w-6 h-1 rounded-full ${
                    i === 2 ? "bg-primary/30" : "bg-muted dark:bg-white/[0.06]"
                  }`}
                />
              ))}
            </div>

            {/* Content */}
            <div className="flex-1 p-4 sm:p-5 min-w-0">
              {/* Metric cards */}
              <div className="grid grid-cols-3 gap-2.5 mb-4">
                {[
                  { label: "Devices", val: "1,847", accent: false },
                  { label: "Online", val: "1,823", accent: true },
                  { label: "Deploying", val: "12", accent: true },
                ].map((m) => (
                  <div
                    key={m.label}
                    className="rounded-xl bg-muted/40 dark:bg-white/[0.03] border border-border dark:border-white/[0.05] p-3"
                  >
                    <div className="text-[10px] text-muted-foreground dark:text-zinc-500 uppercase tracking-wider font-medium">
                      {m.label}
                    </div>
                    <div
                      className={`text-base sm:text-lg font-bold mt-1 ${
                        m.accent ? "text-primary" : ""
                      }`}
                    >
                      {m.val}
                    </div>
                  </div>
                ))}
              </div>

              {/* Chart */}
              <div className="rounded-xl bg-muted/40 dark:bg-white/[0.03] border border-border dark:border-white/[0.05] p-3 sm:p-4 mb-4">
                <div className="text-[10px] text-muted-foreground dark:text-zinc-500 uppercase tracking-wider mb-3 font-medium">
                  Fleet CPU &mdash; 24h
                </div>
                <div className="flex items-end gap-[3px] h-14 sm:h-16">
                  {CHART_BARS.map((h, i) => (
                    <div
                      key={i}
                      className="flex-1 rounded-t landing-bar"
                      style={{
                        height: `${h}%`,
                        background:
                          h > 80
                            ? "linear-gradient(to top, rgba(251,191,36,0.6), rgba(251,191,36,0.15))"
                            : "linear-gradient(to top, rgba(6,182,212,0.6), rgba(6,182,212,0.1))",
                        animationDelay: `${800 + i * 50}ms`,
                      }}
                    />
                  ))}
                </div>
              </div>

              {/* Device list */}
              <div className="space-y-1.5">
                {MOCK_DEVICES.slice(0, 4).map((d) => (
                  <div
                    key={d.name}
                    className="flex items-center gap-2.5 rounded-lg bg-muted/20 dark:bg-white/[0.02] px-3 py-2"
                  >
                    <div className={`h-2 w-2 rounded-full shrink-0 ${ST_DOT[d.status]}`} />
                    <span className="text-xs text-muted-foreground dark:text-zinc-400 font-mono truncate">
                      {d.name}
                    </span>
                    <span className="ml-auto text-[11px] text-muted-foreground/60 dark:text-zinc-600 shrink-0 font-mono">
                      {d.cpu}
                    </span>
                  </div>
                ))}
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
