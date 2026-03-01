"use client";

import { useQuery } from "@tanstack/react-query";
import { useSession } from "next-auth/react";
import { useParams, useRouter } from "next/navigation";
import { getDeviceLogs } from "@/lib/api/devices";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { ArrowLeft, RefreshCw, Search } from "lucide-react";
import { useState } from "react";

const LEVEL_COLORS: Record<string, string> = {
  ERROR: "text-red-600 dark:text-red-400",
  WARN: "text-yellow-600 dark:text-yellow-400",
  INFO: "text-blue-600 dark:text-blue-400",
  DEBUG: "text-gray-500",
};

export default function DeviceLogsPage() {
  const { id } = useParams<{ id: string }>();
  const { data: session } = useSession();
  const router = useRouter();

  const [search, setSearch] = useState("");
  const [level, setLevel] = useState("");

  const { data: logsResponse, isLoading, refetch } = useQuery({
    queryKey: ["device-logs", id, level, search],
    queryFn: () =>
      getDeviceLogs(session?.accessToken ?? "", id, {
        limit: 200,
        level: level || undefined,
        search: search || undefined,
      }),
    enabled: !!session?.accessToken && !!id,
    refetchInterval: 10_000,
  });

  // Parse Loki response format
  const logLines: { timestamp: string; line: string }[] = [];
  if (logsResponse && typeof logsResponse === "object") {
    const resp = logsResponse as {
      data?: { result?: { values?: [string, string][] }[] };
    };
    const results = resp?.data?.result ?? [];
    for (const stream of results) {
      for (const [ts, line] of stream.values ?? []) {
        logLines.push({ timestamp: ts, line });
      }
    }
  }

  const levels = ["", "ERROR", "WARN", "INFO", "DEBUG"];

  return (
    <div className="space-y-6">
      <div className="flex items-center gap-4">
        <Button
          variant="ghost"
          size="icon"
          onClick={() => router.push(`/devices/${id}`)}
        >
          <ArrowLeft className="h-4 w-4" />
        </Button>
        <div>
          <h1 className="text-2xl font-bold">Device Logs</h1>
          <p className="text-sm text-muted-foreground font-mono">{id}</p>
        </div>
      </div>

      {/* Filters */}
      <div className="flex items-center gap-3">
        <div className="relative max-w-sm flex-1">
          <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
          <Input
            placeholder="Search logs..."
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="pl-9"
          />
        </div>
        <div className="flex gap-1">
          {levels.map((l) => (
            <Button
              key={l || "all"}
              variant={level === l ? "default" : "outline"}
              size="sm"
              onClick={() => setLevel(l)}
            >
              {l || "All"}
            </Button>
          ))}
        </div>
        <Button variant="outline" size="icon" onClick={() => refetch()}>
          <RefreshCw className="h-4 w-4" />
        </Button>
      </div>

      {/* Log output */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            Log Output
            <Badge variant="secondary">{logLines.length} lines</Badge>
          </CardTitle>
        </CardHeader>
        <CardContent>
          {isLoading ? (
            <div className="space-y-2">
              {Array.from({ length: 10 }).map((_, i) => (
                <Skeleton key={i} className="h-5 w-full" />
              ))}
            </div>
          ) : logLines.length === 0 ? (
            <p className="py-8 text-center text-sm text-muted-foreground">
              No log entries found. Logs will appear once the device starts
              forwarding them.
            </p>
          ) : (
            <div className="max-h-[600px] overflow-auto rounded-md bg-muted p-4">
              <pre className="text-xs font-mono leading-relaxed">
                {logLines.map((entry, i) => {
                  // Extract level from the log line (format: "level=INFO source=agent message")
                  const levelMatch = entry.line.match(/level=(\w+)/);
                  const lineLevel = levelMatch?.[1] ?? "";
                  const colorClass =
                    LEVEL_COLORS[lineLevel] ?? "text-foreground";
                  return (
                    <div key={i} className={`${colorClass} hover:bg-background/50`}>
                      {entry.line}
                    </div>
                  );
                })}
              </pre>
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
