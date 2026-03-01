"use client";

import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useSession } from "next-auth/react";
import { useRouter } from "next/navigation";
import { listDevices, deleteDevice, type Device } from "@/lib/api/devices";
import { useOrganization } from "@/lib/hooks/use-organization";
import { StateBadge } from "@/components/state-badge";
import { PageHeader } from "@/components/page-header";
import { ConfirmDialog } from "@/components/confirm-dialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Checkbox } from "@/components/ui/checkbox";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { TableRowSkeleton } from "@/components/loading-skeleton";
import { EmptyState } from "@/components/empty-state";
import {
  Search,
  ArrowUpDown,
  Plus,
  X,
  Trash2,
  Copy,
  Check,
} from "lucide-react";
import { useState, useMemo } from "react";
import { formatDistanceToNow } from "date-fns";
import { toast } from "sonner";

type SortKey = "hostname" | "state" | "lastHeartbeat";
type SortDir = "asc" | "desc";

export default function DevicesPage() {
  const { data: session } = useSession();
  const router = useRouter();
  const queryClient = useQueryClient();

  const { data: devices, isLoading } = useQuery({
    queryKey: ["devices"],
    queryFn: () => listDevices(session?.accessToken ?? ""),
    enabled: !!session?.accessToken,
  });

  // Filters
  const [search, setSearch] = useState("");
  const [stateFilter, setStateFilter] = useState<string[]>([]);
  const [archFilter, setArchFilter] = useState("");
  const [sortKey, setSortKey] = useState<SortKey>("hostname");
  const [sortDir, setSortDir] = useState<SortDir>("asc");

  // Pagination
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(10);

  // Selection
  const [selected, setSelected] = useState<Set<string>>(new Set());

  // Dialogs
  const [deleteConfirmOpen, setDeleteConfirmOpen] = useState(false);
  const [enrollDialogOpen, setEnrollDialogOpen] = useState(false);
  const [copied, setCopied] = useState(false);

  const deleteMutation = useMutation({
    mutationFn: async (ids: string[]) => {
      for (const id of ids) {
        await deleteDevice(session?.accessToken ?? "", id);
      }
    },
    onSuccess: () => {
      toast.success(`Deleted ${selected.size} device(s)`);
      setSelected(new Set());
      queryClient.invalidateQueries({ queryKey: ["devices"] });
    },
    onError: (err: Error) => {
      toast.error(`Failed to delete: ${err.message}`);
    },
  });

  const toggleState = (state: string) => {
    setStateFilter((prev) =>
      prev.includes(state) ? prev.filter((s) => s !== state) : [...prev, state],
    );
    setPage(0);
  };

  const toggleSort = (key: SortKey) => {
    if (sortKey === key) {
      setSortDir((d) => (d === "asc" ? "desc" : "asc"));
    } else {
      setSortKey(key);
      setSortDir("asc");
    }
  };

  const clearFilters = () => {
    setSearch("");
    setStateFilter([]);
    setArchFilter("");
    setPage(0);
  };

  const hasFilters = search || stateFilter.length > 0 || archFilter;

  const filtered = useMemo(() => {
    let result = devices ?? [];
    if (search) {
      const lower = search.toLowerCase();
      result = result.filter(
        (d) =>
          d.hostname.toLowerCase().includes(lower) ||
          d.deviceId.toLowerCase().includes(lower),
      );
    }
    if (stateFilter.length > 0) {
      result = result.filter((d) => stateFilter.includes(d.state));
    }
    if (archFilter) {
      result = result.filter((d) => d.architecture === archFilter);
    }
    result = [...result].sort((a, b) => {
      let cmp = 0;
      if (sortKey === "hostname") cmp = a.hostname.localeCompare(b.hostname);
      else if (sortKey === "state") cmp = a.state.localeCompare(b.state);
      else if (sortKey === "lastHeartbeat")
        cmp = (a.lastHeartbeat ?? "").localeCompare(b.lastHeartbeat ?? "");
      return sortDir === "asc" ? cmp : -cmp;
    });
    return result;
  }, [devices, search, stateFilter, archFilter, sortKey, sortDir]);

  const totalPages = Math.ceil(filtered.length / pageSize);
  const paginated = filtered.slice(page * pageSize, (page + 1) * pageSize);

  const architectures = useMemo(() => {
    const set = new Set((devices ?? []).map((d) => d.architecture));
    return Array.from(set).sort();
  }, [devices]);

  const toggleSelect = (id: string) => {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const toggleSelectAll = () => {
    if (selected.size === paginated.length) {
      setSelected(new Set());
    } else {
      setSelected(new Set(paginated.map((d) => d.deviceId)));
    }
  };

  const SortHeader = ({
    label,
    sortId,
  }: {
    label: string;
    sortId: SortKey;
  }) => (
    <button
      className="inline-flex items-center gap-1 hover:text-foreground"
      onClick={() => toggleSort(sortId)}
    >
      {label}
      <ArrowUpDown className="h-3 w-3" />
    </button>
  );

  return (
    <div className="space-y-4">
      <PageHeader
        title="Devices"
        description={`${devices?.length ?? 0} device${(devices?.length ?? 0) !== 1 ? "s" : ""} registered`}
      >
        <Dialog open={enrollDialogOpen} onOpenChange={setEnrollDialogOpen}>
          <DialogTrigger asChild>
            <Button>
              <Plus className="mr-2 h-4 w-4" />
              Add Device
            </Button>
          </DialogTrigger>
          <DialogContent>
            <DialogHeader>
              <DialogTitle>Add a Device</DialogTitle>
              <DialogDescription>
                Run this command on your edge device to enroll it.
              </DialogDescription>
            </DialogHeader>
            <div className="rounded-lg bg-muted/50 border border-border/50 p-4">
              <code className="text-sm font-mono break-all text-primary">
                curl -sSL https://get.edgeguardian.io | sh -s -- --token YOUR_ENROLLMENT_TOKEN
              </code>
            </div>
            <Button
              variant="outline"
              onClick={() => {
                navigator.clipboard.writeText(
                  "curl -sSL https://get.edgeguardian.io | sh -s -- --token YOUR_ENROLLMENT_TOKEN",
                );
                setCopied(true);
                setTimeout(() => setCopied(false), 2000);
              }}
            >
              {copied ? (
                <Check className="mr-2 h-4 w-4" />
              ) : (
                <Copy className="mr-2 h-4 w-4" />
              )}
              {copied ? "Copied!" : "Copy Command"}
            </Button>
          </DialogContent>
        </Dialog>
      </PageHeader>

      {/* Filter bar */}
      <div className="flex flex-wrap items-center gap-2">
        <div className="relative w-64">
          <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
          <Input
            placeholder="Search devices..."
            value={search}
            onChange={(e) => {
              setSearch(e.target.value);
              setPage(0);
            }}
            className="pl-9"
          />
        </div>

        {["ONLINE", "DEGRADED", "OFFLINE"].map((s) => (
          <Button
            key={s}
            variant={stateFilter.includes(s) ? "default" : "outline"}
            size="sm"
            onClick={() => toggleState(s)}
          >
            {s}
          </Button>
        ))}

        <Select
          value={archFilter}
          onValueChange={(v) => {
            setArchFilter(v === "all" ? "" : v);
            setPage(0);
          }}
        >
          <SelectTrigger className="w-40">
            <SelectValue placeholder="Architecture" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="all">All Architectures</SelectItem>
            {architectures.map((a) => (
              <SelectItem key={a} value={a}>
                {a}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>

        {hasFilters && (
          <Button variant="ghost" size="sm" onClick={clearFilters}>
            <X className="mr-1 h-3 w-3" />
            Clear
          </Button>
        )}
      </div>

      {/* Bulk actions bar */}
      {selected.size > 0 && (
        <div className="flex items-center gap-3 rounded-lg border border-primary/20 bg-primary/5 p-2 px-4">
          <span className="text-sm font-medium">
            {selected.size} selected
          </span>
          <Button
            variant="destructive"
            size="sm"
            onClick={() => setDeleteConfirmOpen(true)}
          >
            <Trash2 className="mr-1 h-3 w-3" />
            Delete
          </Button>
        </div>
      )}

      {/* Table */}
      <div className="rounded-xl border border-border/50 overflow-hidden">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead className="w-10">
                <Checkbox
                  checked={
                    paginated.length > 0 && selected.size === paginated.length
                  }
                  onCheckedChange={toggleSelectAll}
                />
              </TableHead>
              <TableHead>
                <SortHeader label="Hostname" sortId="hostname" />
              </TableHead>
              <TableHead>
                <SortHeader label="State" sortId="state" />
              </TableHead>
              <TableHead>Architecture</TableHead>
              <TableHead>Version</TableHead>
              <TableHead>
                <SortHeader label="Last Heartbeat" sortId="lastHeartbeat" />
              </TableHead>
              <TableHead>Labels</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {isLoading ? (
              Array.from({ length: 5 }).map((_, i) => (
                <TableRowSkeleton key={i} columns={7} />
              ))
            ) : paginated.length === 0 ? (
              <TableRow>
                <TableCell colSpan={7}>
                  <EmptyState
                    title="No devices found"
                    description="Register your first device to see it here."
                  />
                </TableCell>
              </TableRow>
            ) : (
              paginated.map((device) => (
                <TableRow
                  key={device.deviceId}
                  className="cursor-pointer"
                  onClick={() => router.push(`/devices/${device.deviceId}`)}
                >
                  <TableCell onClick={(e) => e.stopPropagation()}>
                    <Checkbox
                      checked={selected.has(device.deviceId)}
                      onCheckedChange={() => toggleSelect(device.deviceId)}
                    />
                  </TableCell>
                  <TableCell className="font-medium">
                    {device.hostname}
                  </TableCell>
                  <TableCell>
                    <StateBadge state={device.state} />
                  </TableCell>
                  <TableCell>
                    <Badge variant="secondary" className="font-mono text-xs">
                      {device.os}/{device.architecture}
                    </Badge>
                  </TableCell>
                  <TableCell>
                    <span className="font-mono text-xs">
                      {device.agentVersion}
                    </span>
                  </TableCell>
                  <TableCell>
                    {device.lastHeartbeat
                      ? formatDistanceToNow(new Date(device.lastHeartbeat), {
                          addSuffix: true,
                        })
                      : "Never"}
                  </TableCell>
                  <TableCell>
                    <div className="flex flex-wrap gap-1">
                      {Object.entries(device.labels ?? {})
                        .slice(0, 2)
                        .map(([k, v]) => (
                          <Badge
                            key={k}
                            variant="outline"
                            className="text-xs"
                          >
                            {k}={v}
                          </Badge>
                        ))}
                      {Object.keys(device.labels ?? {}).length > 2 && (
                        <Badge variant="outline" className="text-xs">
                          +{Object.keys(device.labels).length - 2}
                        </Badge>
                      )}
                    </div>
                  </TableCell>
                </TableRow>
              ))
            )}
          </TableBody>
        </Table>
      </div>

      {/* Pagination */}
      {filtered.length > 0 && (
        <div className="flex items-center justify-between">
          <p className="text-sm text-muted-foreground">
            Showing {page * pageSize + 1}-
            {Math.min((page + 1) * pageSize, filtered.length)} of{" "}
            {filtered.length}
          </p>
          <div className="flex items-center gap-2">
            <Select
              value={String(pageSize)}
              onValueChange={(v) => {
                setPageSize(Number(v));
                setPage(0);
              }}
            >
              <SelectTrigger className="w-20">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {[10, 25, 50].map((n) => (
                  <SelectItem key={n} value={String(n)}>
                    {n}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            <Button
              variant="outline"
              size="sm"
              disabled={page === 0}
              onClick={() => setPage((p) => p - 1)}
            >
              Previous
            </Button>
            <Button
              variant="outline"
              size="sm"
              disabled={page >= totalPages - 1}
              onClick={() => setPage((p) => p + 1)}
            >
              Next
            </Button>
          </div>
        </div>
      )}

      {/* Delete confirmation */}
      <ConfirmDialog
        open={deleteConfirmOpen}
        onOpenChange={setDeleteConfirmOpen}
        title="Delete Devices"
        description={`Are you sure you want to delete ${selected.size} device(s)? This action cannot be undone.`}
        confirmLabel="Delete"
        variant="destructive"
        onConfirm={() => {
          deleteMutation.mutate(Array.from(selected));
          setDeleteConfirmOpen(false);
        }}
        loading={deleteMutation.isPending}
      />
    </div>
  );
}
