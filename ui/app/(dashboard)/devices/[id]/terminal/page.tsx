"use client";

import { useEffect, useRef, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import { useSession } from "next-auth/react";
import { useOrganization } from "@/lib/hooks/use-organization";
import { createShellSession, shellWebSocketUrl } from "@/lib/api/shell";
import { PageHeader } from "@/components/page-header";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { ArrowLeft, RotateCw, ShieldAlert } from "lucide-react";
import "@xterm/xterm/css/xterm.css";

const ROLE_RANK: Record<string, number> = {
  viewer: 0,
  operator: 1,
  admin: 2,
  owner: 3,
};

type ConnectionStatus = "connecting" | "open" | "closed" | "error";

const STATUS_LABEL: Record<ConnectionStatus, string> = {
  connecting: "Connecting…",
  open: "Connected",
  closed: "Disconnected",
  error: "Error",
};

const STATUS_VARIANT: Record<ConnectionStatus, "default" | "secondary" | "destructive"> = {
  connecting: "secondary",
  open: "default",
  closed: "secondary",
  error: "destructive",
};

export default function DeviceTerminalPage() {
  const { id } = useParams<{ id: string }>();
  const { data: session } = useSession();
  const router = useRouter();
  const token = session?.accessToken ?? "";

  const { currentOrg, isLoading: orgLoading } = useOrganization();
  const role = currentOrg?.role?.toLowerCase() ?? "";
  const allowed = (ROLE_RANK[role] ?? -1) >= ROLE_RANK.operator;

  const containerRef = useRef<HTMLDivElement>(null);
  const [status, setStatus] = useState<ConnectionStatus>("connecting");
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    if (!token || !id || !allowed || !containerRef.current) return;

    let disposed = false;
    let ws: WebSocket | null = null;
    let term: import("@xterm/xterm").Terminal | null = null;
    let fit: import("@xterm/addon-fit").FitAddon | null = null;
    let resizeObserver: ResizeObserver | null = null;

    (async () => {
      const { Terminal } = await import("@xterm/xterm");
      const { FitAddon } = await import("@xterm/addon-fit");
      if (disposed || !containerRef.current) return;

      term = new Terminal({
        fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace",
        fontSize: 13,
        cursorBlink: true,
        theme: { background: "#09090b", foreground: "#e4e4e7" },
      });
      fit = new FitAddon();
      term.loadAddon(fit);
      term.open(containerRef.current);
      fit.fit();

      setStatus("connecting");
      let resp: { sessionId: string; wsTicket: string };
      try {
        resp = await createShellSession(token, id, { rows: term.rows, cols: term.cols });
      } catch (e) {
        if (!disposed) {
          setStatus("error");
          term.writeln(`\r\n\x1b[31mFailed to open session: ${(e as Error).message}\x1b[0m`);
        }
        return;
      }
      if (disposed) return;

      const encoder = new TextEncoder();
      ws = new WebSocket(shellWebSocketUrl(resp.wsTicket));
      ws.binaryType = "arraybuffer";

      ws.onopen = () => {
        if (disposed) return;
        setStatus("open");
        // The POST carried a pre-fit size guess; sync the actual fitted dims.
        ws?.send(JSON.stringify({ type: "resize", rows: term!.rows, cols: term!.cols }));
        term?.focus();
      };
      ws.onmessage = (ev) => {
        if (typeof ev.data === "string") return; // control frames, not terminal output
        term?.write(new Uint8Array(ev.data as ArrayBuffer));
      };
      ws.onclose = () => {
        if (disposed) return;
        setStatus("closed");
        term?.writeln("\r\n\x1b[90m── session closed ──\x1b[0m");
      };
      ws.onerror = () => {
        if (!disposed) setStatus("error");
      };

      term.onData((data) => {
        if (ws?.readyState === WebSocket.OPEN) ws.send(encoder.encode(data));
      });
      term.onResize(({ rows, cols }) => {
        if (ws?.readyState === WebSocket.OPEN) {
          ws.send(JSON.stringify({ type: "resize", rows, cols }));
        }
      });

      resizeObserver = new ResizeObserver(() => {
        try {
          fit?.fit();
        } catch {
          /* container detached mid-resize */
        }
      });
      resizeObserver.observe(containerRef.current);
    })();

    return () => {
      disposed = true;
      resizeObserver?.disconnect();
      ws?.close();
      term?.dispose();
    };
  }, [token, id, allowed, attempt]);

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-4">
        <Button variant="ghost" size="icon" onClick={() => router.push(`/devices/${id}`)}>
          <ArrowLeft className="h-4 w-4" />
        </Button>
        <PageHeader title="Terminal" description={id} />
        <div className="ml-auto flex items-center gap-2">
          {allowed && <Badge variant={STATUS_VARIANT[status]}>{STATUS_LABEL[status]}</Badge>}
          {allowed && (status === "closed" || status === "error") && (
            <Button variant="outline" size="sm" onClick={() => setAttempt((n) => n + 1)}>
              <RotateCw className="mr-2 h-4 w-4" />
              Reconnect
            </Button>
          )}
        </div>
      </div>

      {orgLoading ? (
        <Skeleton className="h-[600px] w-full" />
      ) : !allowed ? (
        <Card>
          <CardContent className="flex flex-col items-center gap-3 py-16 text-center">
            <ShieldAlert className="h-8 w-8 text-muted-foreground" />
            <p className="text-sm text-muted-foreground">
              You need the <span className="font-medium">Operator</span> role or higher to open a
              terminal on this device.
            </p>
          </CardContent>
        </Card>
      ) : (
        <Card>
          <CardContent className="p-2">
            <div
              ref={containerRef}
              className="h-[600px] w-full overflow-hidden rounded bg-[#09090b] p-2"
            />
          </CardContent>
        </Card>
      )}
    </div>
  );
}
