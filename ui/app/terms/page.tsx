"use client";

import { useTheme } from "next-themes";
import { useState, useEffect } from "react";
import { Sun, Moon, ArrowLeft } from "lucide-react";
import { LogoIcon } from "@/components/logo";
import Link from "next/link";

const LAST_UPDATED = "March 9, 2026";

export default function TermsPage() {
  const { setTheme, resolvedTheme } = useTheme();
  const [mounted, setMounted] = useState(false);
  useEffect(() => setMounted(true), []);

  return (
    <div className="relative min-h-screen bg-background text-foreground">
      {/* Header */}
      <header className="sticky top-0 z-50 backdrop-blur-xl bg-background/80 border-b border-border/50">
        <div className="mx-auto max-w-4xl flex items-center justify-between px-6 py-3.5 sm:px-12">
          <Link href="/" className="flex items-center gap-3 text-muted-foreground hover:text-foreground transition-colors">
            <ArrowLeft size={18} />
            <LogoIcon size={28} />
            <span className="text-lg font-bold tracking-tight text-foreground">
              Edge<span className="text-primary">Guardian</span>
            </span>
          </Link>
          {mounted && (
            <button
              onClick={() => setTheme(resolvedTheme === "dark" ? "light" : "dark")}
              className="cursor-pointer rounded-xl p-2.5 text-muted-foreground hover:text-foreground hover:bg-muted/50 transition-all duration-200"
              aria-label="Toggle theme"
            >
              {resolvedTheme === "dark" ? <Sun size={20} /> : <Moon size={20} />}
            </button>
          )}
        </div>
      </header>

      {/* Content */}
      <main className="mx-auto max-w-4xl px-6 py-16 sm:px-12 lg:py-24">
        <h1 className="text-3xl sm:text-4xl font-bold tracking-tight mb-2">
          Terms of Service
        </h1>
        <p className="text-sm text-muted-foreground mb-12">
          Last updated: {LAST_UPDATED}
        </p>

        <div className="prose-legal space-y-10 text-[15px] leading-relaxed text-muted-foreground">

          {/* 1 */}
          <Section n="1" title="Acceptance of Terms">
            <p>
              By accessing or using the EdgeGuardian platform - including the web dashboard,
              controller API, agent binary, and all related services (collectively, the
              &ldquo;Service&rdquo;) - you agree to be bound by these Terms of Service
              (&ldquo;Terms&rdquo;). If you do not agree, do not use the Service. Continued use
              constitutes acceptance of these Terms and any future amendments.
            </p>
          </Section>

          {/* 2 */}
          <Section n="2" title="Beta Status &amp; Assumption of Risk">
            <p className="uppercase font-semibold text-foreground text-sm tracking-wide">
              EdgeGuardian is currently in beta. By using the Service, you expressly acknowledge
              and accept all risks associated with beta software.
            </p>
            <p>
              The Service is under active development. Features may be incomplete, changed, or
              removed without notice. APIs, protocols, and data formats may change between
              releases. While we work to ensure stability and reliability, beta software by its
              nature may contain bugs, defects, or unexpected behavior.
            </p>
            <p>
              You assume sole and complete responsibility for:
            </p>
            <ul>
              <li>Evaluating whether the Service is suitable for your intended use case;</li>
              <li>Any and all consequences arising from your deployment of the Service on your devices and infrastructure;</li>
              <li>Maintaining adequate backups, failover mechanisms, and recovery procedures for any systems managed through the Service;</li>
              <li>Ensuring compliance with all laws, regulations, and industry standards applicable to your use.</li>
            </ul>
          </Section>

          {/* 3 */}
          <Section n="3" title="Disclaimer of Warranties">
            <p className="uppercase font-semibold text-foreground text-sm tracking-wide">
              The Service is provided &ldquo;as is&rdquo; and &ldquo;as available&rdquo; without
              warranty of any kind, whether express, implied, statutory, or otherwise.
            </p>
            <p>
              To the maximum extent permitted by applicable law, EdgeGuardian disclaims all
              warranties, including but not limited to:
            </p>
            <ul>
              <li>Implied warranties of merchantability, fitness for a particular purpose, and non-infringement;</li>
              <li>Any warranty that the Service will operate without interruption, error, or data loss;</li>
              <li>Any warranty of compatibility with any specific hardware platform, architecture (ARM, x86, RISC-V, or otherwise), operating system, kernel version, or system configuration;</li>
              <li>Any warranty that over-the-air updates, binary deployments, or configuration reconciliation will complete successfully on all target devices;</li>
              <li>Any warranty regarding the preservation, integrity, or recoverability of device data, telemetry, configurations, or logs.</li>
            </ul>
          </Section>

          {/* 4 */}
          <Section n="4" title="Limitation of Liability">
            <p className="uppercase font-semibold text-foreground text-sm tracking-wide">
              In no event shall EdgeGuardian, its author(s), contributors, or affiliates be liable
              for any direct, indirect, incidental, special, consequential, or exemplary damages
              arising out of or in connection with your use of the Service.
            </p>
            <p>
              This includes, without limitation: loss of data or configurations; loss of revenue
              or profits; business interruption; device malfunction, instability, or unrecoverable
              failure; unauthorized access to your data; failed or partial OTA updates; service
              outages; or any other damages - regardless of whether EdgeGuardian has been advised
              of the possibility of such damages. You use the Service entirely at your own risk
              and expense. In jurisdictions that do not permit full exclusion of liability,
              liability is limited to the maximum extent permitted by law. The aggregate liability
              of EdgeGuardian shall in no case exceed zero euros (&euro;0.00).
            </p>
          </Section>

          {/* 5 */}
          <Section n="5" title="Device &amp; Infrastructure Risks">
            <p>
              The EdgeGuardian agent operates at a system level on target devices. It may manage
              services, write files, apply configurations, and perform binary updates as part of
              its normal operation. You acknowledge and agree that:
            </p>
            <ul>
              <li>All installation and deployment of agent software is performed at your sole risk and discretion;</li>
              <li>The agent modifies system state by design - including services, files, and network configuration - as directed by the desired-state manifests you define;</li>
              <li>Over-the-air binary updates carry inherent risk including device instability, boot failure, or data loss;</li>
              <li>Watchdog and rollback mechanisms operate on a best-effort basis and cannot guarantee recovery in all failure scenarios;</li>
              <li>EdgeGuardian bears no responsibility for hardware damage, data loss, voided manufacturer warranties, or any other consequence resulting from agent operation on your devices;</li>
              <li>You are solely responsible for testing in a controlled environment before deploying to any devices you cannot afford to lose.</li>
            </ul>
          </Section>

          {/* 6 */}
          <Section n="6" title="Data &amp; Privacy">
            <p>
              The Service processes the following data categories in the course of normal operation:
            </p>
            <ul>
              <li><strong>Account data:</strong> authentication credentials and profile information via third-party identity providers;</li>
              <li><strong>Device telemetry:</strong> hardware metrics, OS information, network status, and agent version;</li>
              <li><strong>Operational data:</strong> service state, reconciliation events, deployment progress, and error reports;</li>
              <li><strong>Usage metadata:</strong> dashboard interactions, API request logs, and session information.</li>
            </ul>
            <p>
              Data is processed solely to provide and improve the Service. No data is sold to
              third parties. Data is stored on infrastructure under the Service operator&apos;s
              control. Given the beta status of the Service, no guarantee is made regarding the
              security, integrity, or availability of stored data. You are responsible for
              compliance with all applicable data protection legislation, including the General
              Data Protection Regulation (GDPR) where applicable.
            </p>
          </Section>

          {/* 7 */}
          <Section n="7" title="Third-Party Dependencies">
            <p>
              The Service relies on third-party software and services including, but not limited
              to: PostgreSQL, Keycloak, EMQX, Grafana, Loki, and various open-source libraries.
              EdgeGuardian makes no warranties regarding the availability, performance, or security
              of any third-party component. Their use is governed by their respective licenses and
              terms. EdgeGuardian is not liable for any failure, outage, vulnerability, or data
              breach originating from a third-party dependency.
            </p>
          </Section>

          {/* 8 */}
          <Section n="8" title="Indemnification">
            <p>
              You agree to indemnify, defend, and hold harmless EdgeGuardian and its author(s),
              contributors, and affiliates from any claims, damages, liabilities, costs, or
              expenses (including legal fees) arising from: (a) your use of the Service;
              (b) your breach of these Terms; (c) your violation of any third-party right; or
              (d) any damage to third parties resulting from your use of the Service or devices
              managed through it.
            </p>
          </Section>

          {/* 9 */}
          <Section n="9" title="Changes &amp; Termination">
            <p>
              EdgeGuardian reserves the right to modify, suspend, or discontinue any part of the
              Service at any time without notice. These Terms may be revised at any time; the date
              at the top of this page reflects the latest revision. Continued use after changes
              constitutes acceptance. EdgeGuardian may restrict or terminate your access at its
              sole discretion, without prior notice or liability.
            </p>
          </Section>

          {/* 10 */}
          <Section n="10" title="Governing Law">
            <p>
              These Terms are governed by the laws of the jurisdiction in which the Service
              operator resides, without regard to conflict-of-law provisions. Disputes shall be
              resolved exclusively in the competent courts of that jurisdiction. If any provision
              is found unenforceable, the remaining provisions remain in full effect.
            </p>
          </Section>

          {/* 11 */}
          <Section n="11" title="Entire Agreement">
            <p>
              These Terms constitute the entire agreement between you and EdgeGuardian regarding
              the Service. They supersede all prior agreements and understandings. Failure to
              enforce any provision does not constitute a waiver of that or any other provision.
            </p>
          </Section>

          {/* Contact */}
          <div className="pt-6 border-t border-border">
            <h2 className="text-lg font-bold text-foreground mb-2">Contact</h2>
            <p>
              For questions regarding these Terms, open an issue in the project repository or
              contact the maintainer through the appropriate institutional channels.
            </p>
          </div>

        </div>
      </main>

      {/* Footer */}
      <footer className="border-t border-border py-8 px-6 sm:px-12">
        <div className="mx-auto max-w-4xl flex flex-col items-center justify-between gap-4 sm:flex-row">
          <div className="flex items-center gap-3">
            <LogoIcon size={20} />
            <span className="text-sm font-semibold text-muted-foreground">EdgeGuardian</span>
          </div>
          <p className="text-xs text-muted-foreground">
            &copy; {new Date().getFullYear()} EdgeGuardian. All rights reserved.
          </p>
        </div>
      </footer>
    </div>
  );
}

function Section({ n, title, children }: { n: string; title: string; children: React.ReactNode }) {
  return (
    <section>
      <h2 className="text-lg font-bold text-foreground mb-3">
        {n}. {title}
      </h2>
      <div className="space-y-3 [&_ul]:list-disc [&_ul]:pl-6 [&_ul]:space-y-1.5 [&_strong]:text-foreground">
        {children}
      </div>
    </section>
  );
}
