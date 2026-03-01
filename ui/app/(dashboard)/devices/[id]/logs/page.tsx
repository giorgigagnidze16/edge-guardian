"use client";

import { useQuery } from "@tanstack/react-query";
import { useSession } from "next-auth/react";
import { useParams, useRouter } from "next/navigation";
import { getDeviceLogs } from "@/lib/api/devices";
import { PageHeader } from "@/components/page-header";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent } from "@/components/ui/card";
import { ScrollArea } from "@/components/ui/scroll-area";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { ArrowLeft, Search, RefreshCw, Download } from "lucide-react";
import { useState } from "react";

const LEVEL_COLORS: Record<string, string> = {
  ERROR: "text-red-500 dark:text-red-400",
  WARN: "text-amber-500 dark:text-amber-400",
  INFO: "text-cyan-500 dark:text-cyan-400",
  DEBUG: "text-zinc-500 dark:text-zinc-500",
};

export default function DeviceLogsPage() {
  const { id } = useParams<{ id: string }>();
  const { data: session } = useSession();
  const router = useRouter();

  const [search, setSearch] = useState("");
  const [level, setLevel] = useState("");
  const [timeRange, setTimeRange] = useState("1h");

  const getStart = () => {
    const map: Record<string, number> = {
      "15m": 15 * 60 * 1000,
      "1h": 60 * 60 * 1000,
      "6h": 6 * 60 * 60 * 1000,
      "24h": 24 * 60 * 60 * 1000,
      "7d": 7 * 24 * 60 * 60 * 1000,
    };
    return new Date(Date.now() - (map[timeRange] ?? map["1h"])).toISOString();
  };

  const {
    data: logsResponse,
    isLoading,
    refetch,
  } = useQuery({
    queryKey: ["device-logs", id, level, search, timeRange],
    queryFn: () =>
      getDeviceLogs(session?.accessToken ?? "", id, {
        limit: 200,
        level: level || undefined,
        search: search || undefined,
        start: getStart(),
      }),
    enabled: !!session?.accessToken && !!id,
    refetchInterval: 10_000,
  });

  const logLines: { timestamp: string; line: string }[] = [];
  if (logsResponse && typeof logsResponse === "object") {
    const resp = logsResponse as {
      data?: { result?: { values?: [string, string][] }[] };
    };
    for (const stream of resp?.data?.result ?? []) {
      for (const [ts, line] of stream.values ?? []) {
        logLines.push({ timestamp: ts, line });
      }
    }
  }

  const downloadLogs = () => {
    const content = logLines.map((l) => l.line).join("\n");
    const blob = new Blob([content], { type: "text/plain" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `${id}-logs.txt`;
    a.click();
    URL.revokeObjectURL(url);
  };

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-4">
        <Button
          variant="ghost"
          size="icon"
          onClick={() => router.push(`/devices/${id}`)}
        >
          <ArrowLeft className="h-4 w-4" />
        </Button>
        <PageHeader
          title="Device Logs"
          description={id}
        />
      </div>

      <div className="flex flex-wrap items-center gap-2">
        <div className="relative w-64">
          <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
          <Input
            placeholder="Search logs..."
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="pl-9"
          />
        </div>
        <Select value={level} onValueChange={setLevel}>
          <SelectTrigger className="w-32">
            <SelectValue placeholder="All Levels" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="all">All Levels</SelectItem>
            <SelectItem value="ERROR">ERROR</SelectItem>
            <SelectItem value="WARN">WARN</SelectItem>
            <SelectItem value="INFO">INFO</SelectItem>
            <SelectItem value="DEBUG">DEBUG</SelectItem>
          </SelectContent>
        </Select>
        <Select value={timeRange} onValueChange={setTimeRange}>
          <SelectTrigger className="w-28">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="15m">15 min</SelectItem>
            <SelectItem value="1h">1 hour</SelectItem>
            <SelectItem value="6h">6 hours</SelectItem>
            <SelectItem value="24h">24 hours</SelectItem>
            <SelectItem value="7d">7 days</SelectItem>
          </SelectContent>
        </Select>
        <Button variant="outline" size="icon" onClick={() => refetch()}>
          <RefreshCw className="h-4 w-4" />
        </Button>
        <Button
          variant="outline"
          size="sm"
          onClick={downloadLogs}
          disabled={logLines.length === 0}
        >
          <Download className="mr-2 h-4 w-4" />
          Download
        </Button>
        <Badge variant="secondary">{logLines.length} lines</Badge>
      </div>

      <Card>
        <CardContent className="p-0">
          {isLoading ? (
            <div className="space-y-2 p-4">
              {Array.from({ length: 10 }).map((_, i) => (
                <Skeleton key={i} className="h-5 w-full" />
              ))}
            </div>
          ) : logLines.length === 0 ? (
            <p className="py-12 text-center text-sm text-muted-foreground">
              No log entries found. Logs will appear once the device starts
              forwarding them.
            </p>
          ) : (
            <ScrollArea className="h-[600px]">
              <pre className="p-4 text-xs font-mono leading-relaxed">
                {logLines.map((entry, i) => {
                  const levelMatch = entry.line.match(/level=(\w+)/);
                  const lineLevel = levelMatch?.[1] ?? "";
                  const colorClass = LEVEL_COLORS[lineLevel] ?? "text-foreground";
                  return (
                    <div
                      key={i}
                      className={`${colorClass} hover:bg-muted rounded px-1`}
                    >
                      {entry.line}
                    </div>
                  );
                })}
              </pre>
            </ScrollArea>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
