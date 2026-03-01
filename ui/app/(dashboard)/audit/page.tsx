"use client";

import { useQuery } from "@tanstack/react-query";
import { useSession } from "next-auth/react";
import { apiFetch } from "@/lib/api-client";
import { Badge } from "@/components/ui/badge";
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import { EmptyState } from "@/components/empty-state";
import { FileText, Search } from "lucide-react";
import { useState, useMemo } from "react";
import { formatDistanceToNow, format } from "date-fns";

interface AuditEntry {
  id: number;
  userId: number | null;
  userEmail: string | null;
  action: string;
  resourceType: string;
  resourceId: string | null;
  details: string | null;
  createdAt: string;
}

const ACTION_COLORS: Record<string, string> = {
  CREATE:
    "border-green-200 bg-green-100 text-green-800 dark:border-green-800 dark:bg-green-900 dark:text-green-200",
  UPDATE:
    "border-blue-200 bg-blue-100 text-blue-800 dark:border-blue-800 dark:bg-blue-900 dark:text-blue-200",
  DELETE:
    "border-red-200 bg-red-100 text-red-800 dark:border-red-800 dark:bg-red-900 dark:text-red-200",
};

export default function AuditPage() {
  const { data: session } = useSession();
  const [search, setSearch] = useState("");
  const orgId = 1;

  const { data: entries, isLoading } = useQuery({
    queryKey: ["audit-log", orgId],
    queryFn: () =>
      apiFetch<AuditEntry[]>(
        `/api/v1/organizations/${orgId}/audit-log`,
        { token: session?.accessToken ?? "" },
      ),
    enabled: !!session?.accessToken,
  });

  const filtered = useMemo(() => {
    if (!search || !entries) return entries ?? [];
    const lower = search.toLowerCase();
    return entries.filter(
      (e) =>
        e.action.toLowerCase().includes(lower) ||
        e.resourceType.toLowerCase().includes(lower) ||
        (e.userEmail?.toLowerCase().includes(lower) ?? false) ||
        (e.details?.toLowerCase().includes(lower) ?? false),
    );
  }, [entries, search]);

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold">Audit Log</h1>

      <div className="relative max-w-sm">
        <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
        <Input
          placeholder="Filter by action, resource, or user..."
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          className="pl-9"
        />
      </div>

      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <FileText className="h-5 w-5" />
            Activity Timeline
            {entries && (
              <Badge variant="secondary">{entries.length} events</Badge>
            )}
          </CardTitle>
        </CardHeader>
        <CardContent>
          {isLoading ? (
            <div className="space-y-4">
              {Array.from({ length: 5 }).map((_, i) => (
                <div key={i} className="flex gap-4">
                  <Skeleton className="h-10 w-10 rounded-full" />
                  <div className="flex-1 space-y-2">
                    <Skeleton className="h-4 w-3/4" />
                    <Skeleton className="h-3 w-1/2" />
                  </div>
                </div>
              ))}
            </div>
          ) : filtered.length === 0 ? (
            <EmptyState
              title="No audit entries"
              description="Actions performed in the system will appear here."
            />
          ) : (
            <div className="space-y-4">
              {filtered.map((entry) => (
                <div
                  key={entry.id}
                  className="flex items-start gap-4 rounded-md border p-4"
                >
                  <div className="flex-1">
                    <div className="flex items-center gap-2">
                      <Badge
                        variant="outline"
                        className={
                          ACTION_COLORS[entry.action.toUpperCase()] ?? ""
                        }
                      >
                        {entry.action}
                      </Badge>
                      <Badge variant="secondary">{entry.resourceType}</Badge>
                      {entry.resourceId && (
                        <span className="font-mono text-xs text-muted-foreground">
                          {entry.resourceId}
                        </span>
                      )}
                    </div>
                    {entry.details && (
                      <p className="mt-1 text-sm text-muted-foreground">
                        {entry.details}
                      </p>
                    )}
                    <div className="mt-2 flex items-center gap-2 text-xs text-muted-foreground">
                      <span>{entry.userEmail ?? "system"}</span>
                      <span>&middot;</span>
                      <span
                        title={format(
                          new Date(entry.createdAt),
                          "yyyy-MM-dd HH:mm:ss",
                        )}
                      >
                        {formatDistanceToNow(new Date(entry.createdAt), {
                          addSuffix: true,
                        })}
                      </span>
                    </div>
                  </div>
                </div>
              ))}
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
