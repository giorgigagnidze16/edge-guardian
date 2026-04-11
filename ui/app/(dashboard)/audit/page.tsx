"use client";

import { useQuery } from "@tanstack/react-query";
import { useSession } from "next-auth/react";
import { listAuditLog, type AuditLogEntry } from "@/lib/api/organizations";
import { useOrganization } from "@/lib/hooks/use-organization";
import { PageHeader } from "@/components/page-header";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { EmptyState } from "@/components/empty-state";
import { FileText, Search } from "lucide-react";
import { useState, useMemo } from "react";
import { formatDistanceToNow, format } from "date-fns";

const ACTION_COLORS: Record<string, string> = {
  CREATE:
    "border-emerald-500/30 bg-emerald-500/10 text-emerald-600 dark:border-emerald-400/30 dark:bg-emerald-400/10 dark:text-emerald-400",
  UPDATE:
    "border-blue-500/30 bg-blue-500/10 text-blue-600 dark:border-blue-400/30 dark:bg-blue-400/10 dark:text-blue-400",
  DELETE:
    "border-red-500/30 bg-red-500/10 text-red-600 dark:border-red-400/30 dark:bg-red-400/10 dark:text-red-400",
};

export default function AuditPage() {
  const { data: session } = useSession();
  const { orgId } = useOrganization();
  const token = session?.accessToken ?? "";

  const [search, setSearch] = useState("");
  const [actionFilter, setActionFilter] = useState("");
  const [resourceFilter, setResourceFilter] = useState("");
  const [page, setPage] = useState(0);
  const pageSize = 20;

  const { data: entries, isLoading } = useQuery({
    queryKey: ["audit-log", orgId],
    queryFn: () => listAuditLog(token),
    enabled: !!token && !!orgId,
  });

  const resourceTypes = useMemo(() => {
    const set = new Set((entries ?? []).map((e) => e.resourceType));
    return Array.from(set).sort();
  }, [entries]);

  const filtered = useMemo(() => {
    let result = entries ?? [];
    if (search) {
      const lower = search.toLowerCase();
      result = result.filter(
        (e) =>
          e.action.toLowerCase().includes(lower) ||
          e.resourceType.toLowerCase().includes(lower) ||
          (e.userEmail?.toLowerCase().includes(lower) ?? false),
      );
    }
    if (actionFilter) {
      result = result.filter((e) => e.action.toUpperCase() === actionFilter);
    }
    if (resourceFilter) {
      result = result.filter((e) => e.resourceType === resourceFilter);
    }
    return result;
  }, [entries, search, actionFilter, resourceFilter]);

  const totalPages = Math.ceil(filtered.length / pageSize);
  const paginated = filtered.slice(page * pageSize, (page + 1) * pageSize);

  return (
    <div className="space-y-6">
      <PageHeader
        title="Audit Log"
        description="Track all actions in your organization"
      />

      {/* Filters */}
      <div className="flex flex-wrap items-center gap-2">
        <div className="relative w-64">
          <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
          <Input
            placeholder="Filter by action, resource, or user..."
            value={search}
            onChange={(e) => { setSearch(e.target.value); setPage(0); }}
            className="pl-9"
          />
        </div>
        <Select value={actionFilter} onValueChange={(v) => { setActionFilter(v === "all" ? "" : v); setPage(0); }}>
          <SelectTrigger className="w-36">
            <SelectValue placeholder="Action type" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="all">All Actions</SelectItem>
            <SelectItem value="CREATE">CREATE</SelectItem>
            <SelectItem value="UPDATE">UPDATE</SelectItem>
            <SelectItem value="DELETE">DELETE</SelectItem>
          </SelectContent>
        </Select>
        <Select value={resourceFilter} onValueChange={(v) => { setResourceFilter(v === "all" ? "" : v); setPage(0); }}>
          <SelectTrigger className="w-40">
            <SelectValue placeholder="Resource type" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="all">All Resources</SelectItem>
            {resourceTypes.map((rt) => (
              <SelectItem key={rt} value={rt}>{rt}</SelectItem>
            ))}
          </SelectContent>
        </Select>
        {entries && (
          <Badge variant="secondary">{filtered.length} events</Badge>
        )}
      </div>

      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <FileText className="h-5 w-5" />
            Activity Timeline
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
          ) : paginated.length === 0 ? (
            <EmptyState
              title="No audit entries"
              description="Actions performed in the system will appear here."
            />
          ) : (
            <div className="space-y-4">
              {paginated.map((entry) => {
                const initials = entry.userEmail
                  ? entry.userEmail.charAt(0).toUpperCase()
                  : "S";
                return (
                  <div
                    key={entry.id}
                    className="flex items-start gap-4 rounded-lg border border-border/50 p-4 transition-colors hover:bg-muted/30"
                  >
                    <Avatar className="h-9 w-9">
                      <AvatarFallback className="text-xs">{initials}</AvatarFallback>
                    </Avatar>
                    <div className="flex-1">
                      <div className="flex items-center gap-2">
                        <Badge
                          variant="outline"
                          className={ACTION_COLORS[entry.action.toUpperCase()] ?? ""}
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
                );
              })}
            </div>
          )}
        </CardContent>
      </Card>

      {/* Pagination */}
      {filtered.length > pageSize && (
        <div className="flex items-center justify-between">
          <p className="text-sm text-muted-foreground">
            Showing {page * pageSize + 1}-{Math.min((page + 1) * pageSize, filtered.length)} of {filtered.length}
          </p>
          <div className="flex gap-2">
            <Button variant="outline" size="sm" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
              Previous
            </Button>
            <Button variant="outline" size="sm" disabled={page >= totalPages - 1} onClick={() => setPage((p) => p + 1)}>
              Next
            </Button>
          </div>
        </div>
      )}
    </div>
  );
}
