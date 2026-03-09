import type { Metadata } from "next";
import { Plus_Jakarta_Sans, JetBrains_Mono } from "next/font/google";
import { Providers } from "@/components/providers";
import "./globals.css";

const jakarta = Plus_Jakarta_Sans({
  subsets: ["latin"],
  variable: "--font-sans",
  display: "swap",
});

const jetbrains = JetBrains_Mono({
  subsets: ["latin"],
  variable: "--font-mono",
  display: "swap",
});

export const metadata: Metadata = {
  title: {
    default: "EdgeGuardian",
    template: "%s | EdgeGuardian",
  },
  description:
    "Kubernetes-style IoT fleet management. Sub-5MB agents, declarative YAML config, OTA updates with automatic rollback. Built for ARM, x86, and everything in between.",
  openGraph: {
    title: "EdgeGuardian — IoT Fleet Management",
    description:
      "Deploy to thousands. Monitor everything. Sub-5MB agents that self-heal with declarative config and OTA updates.",
    siteName: "EdgeGuardian",
    type: "website",
  },
  twitter: {
    card: "summary_large_image",
    title: "EdgeGuardian — IoT Fleet Management",
    description:
      "Deploy to thousands. Monitor everything. Sub-5MB agents that self-heal with declarative config and OTA updates.",
  },
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en" suppressHydrationWarning>
      <body
        className={`${jakarta.variable} ${jetbrains.variable} font-sans min-h-screen antialiased`}
      >
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}
