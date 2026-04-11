"use client";

import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useSession } from "next-auth/react";
import {
  listCertificateRequests,
  listCertificates,
  approveCertRequest,
  rejectCertRequest,
  revokeCertificate,
  getCaCert,
  type CertificateRequest,
  type Certificate,
} from "@/lib/api/certificates";
import { useOrganization } from "@/lib/hooks/use-organization";
import { PageHeader } from "@/components/page-header";
import { ConfirmDialog } from "@/components/confirm-dialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { TableRowSkeleton } from "@/components/loading-skeleton";
import { EmptyState } from "@/components/empty-state";
import {
  ShieldCheck,
  ShieldAlert,
  Download,
  Check,
  X,
  Ban,
} from "lucide-react";
import { useState } from "react";
import { toast } from "sonner";

function statusBadge(status: string) {
  switch (status) {
    case "valid":
      return <Badge className="bg-emerald-500/10 text-emerald-500 border-emerald-500/20">Valid</Badge>;
    case "expiring":
      return <Badge className="bg-amber-500/10 text-amber-500 border-amber-500/20">Expiring Soon</Badge>;
    case "expired":
      return <Badge className="bg-red-500/10 text-red-500 border-red-500/20">Expired</Badge>;
    case "revoked":
      return <Badge className="bg-zinc-500/10 text-zinc-500 border-zinc-500/20">Revoked</Badge>;
    default:
      return <Badge variant="outline">{status}</Badge>;
  }
}

function requestStateBadge(state: string) {
  switch (state) {
    case "pending":
      return <Badge className="bg-amber-500/10 text-amber-500 border-amber-500/20">Pending</Badge>;
    case "approved":
      return <Badge className="bg-emerald-500/10 text-emerald-500 border-emerald-500/20">Approved</Badge>;
    case "rejected":
      return <Badge className="bg-red-500/10 text-red-500 border-red-500/20">Rejected</Badge>;
    case "blocked":
      return <Badge className="bg-red-600/20 text-red-400 border-red-600/30">Blocked - Compromise</Badge>;
    default:
      return <Badge variant="outline">{state}</Badge>;
  }
}

function formatDate(iso: string) {
  return new Date(iso).toLocaleDateString("en-US", {
    month: "short", day: "numeric", year: "numeric",
  });
}

function timeUntil(iso: string) {
  const diff = new Date(iso).getTime() - Date.now();
  const days = Math.floor(diff / (1000 * 60 * 60 * 24));
  if (days < 0) return `${Math.abs(days)}d ago`;
  if (days === 0) return "today";
  return `in ${days}d`;
}

export default function CertificatesPage() {
  const { data: session } = useSession();
  const { orgId } = useOrganization();
  const queryClient = useQueryClient();
  const token = session?.accessToken ?? "";

  const [revokeTarget, setRevokeTarget] = useState<Certificate | null>(null);
  const [rejectTarget, setRejectTarget] = useState<CertificateRequest | null>(null);

  const { data: requests, isLoading: reqLoading } = useQuery({
    queryKey: ["cert-requests", orgId],
    queryFn: () => listCertificateRequests(token),
    enabled: !!token && !!orgId,
  });

  const { data: certs, isLoading: certsLoading } = useQuery({
    queryKey: ["certificates", orgId],
    queryFn: () => listCertificates(token),
    enabled: !!token && !!orgId,
  });

  const approveMutation = useMutation({
    mutationFn: (id: number) => approveCertRequest(token, id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["cert-requests", orgId] });
      queryClient.invalidateQueries({ queryKey: ["certificates", orgId] });
      toast.success("Certificate approved and issued");
    },
    onError: () => toast.error("Failed to approve certificate"),
  });

  const rejectMutation = useMutation({
    mutationFn: (id: number) => rejectCertRequest(token, id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["cert-requests", orgId] });
      setRejectTarget(null);
      toast.success("Certificate request rejected");
    },
    onError: () => toast.error("Failed to reject request"),
  });

  const revokeMutation = useMutation({
    mutationFn: (id: number) => revokeCertificate(token, id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["certificates", orgId] });
      setRevokeTarget(null);
      toast.success("Certificate revoked");
    },
    onError: () => toast.error("Failed to revoke certificate"),
  });

  async function downloadCaCert() {
    if (!token || !orgId) return;
    try {
      const pem = await getCaCert(token);
      const blob = new Blob([pem], { type: "application/x-pem-file" });
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = "edgeguardian-ca.crt";
      a.click();
      URL.revokeObjectURL(url);
    } catch {
      toast.error("Failed to download CA certificate");
    }
  }

  const pendingCount = requests?.filter((r) => r.state === "pending").length ?? 0;

  return (
    <div className="space-y-6">
      <PageHeader title="Certificates" description="Manage X.509 certificates for your devices">
        <Button variant="outline" onClick={downloadCaCert}>
          <Download className="mr-2 h-4 w-4" /> Download CA Cert
        </Button>
      </PageHeader>

      <Tabs defaultValue="requests">
        <TabsList>
          <TabsTrigger value="requests">
            Requests {pendingCount > 0 && (
              <span className="ml-1.5 rounded-full bg-amber-500/20 px-1.5 py-0.5 text-xs text-amber-500">
                {pendingCount}
              </span>
            )}
          </TabsTrigger>
          <TabsTrigger value="certificates">Issued Certificates</TabsTrigger>
        </TabsList>

        <TabsContent value="requests" className="mt-4">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Device</TableHead>
                <TableHead>Name</TableHead>
                <TableHead>Common Name</TableHead>
                <TableHead>Type</TableHead>
                <TableHead>State</TableHead>
                <TableHead>Requested</TableHead>
                <TableHead></TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {reqLoading ? (
                Array.from({ length: 3 }).map((_, i) => <TableRowSkeleton key={i} columns={7} />)
              ) : !requests || requests.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={7}>
                    <EmptyState
                      icon={ShieldCheck}
                      title="No certificate requests"
                      description="Certificate requests from devices will appear here."
                    />
                  </TableCell>
                </TableRow>
              ) : (
                requests.map((req) => (
                  <TableRow key={req.id} className={req.state === "blocked" ? "bg-red-500/5" : ""}>
                    <TableCell className="font-mono text-sm">{req.deviceId}</TableCell>
                    <TableCell>{req.name}</TableCell>
                    <TableCell className="text-muted-foreground">{req.commonName}</TableCell>
                    <TableCell><Badge variant="outline">{req.type}</Badge></TableCell>
                    <TableCell>{requestStateBadge(req.state)}</TableCell>
                    <TableCell className="text-muted-foreground">{formatDate(req.createdAt)}</TableCell>
                    <TableCell>
                      {req.state === "pending" && (
                        <div className="flex gap-1">
                          <Button
                            size="sm"
                            variant="outline"
                            className="text-emerald-500 hover:text-emerald-600"
                            onClick={() => approveMutation.mutate(req.id)}
                            disabled={approveMutation.isPending}
                          >
                            <Check className="h-4 w-4" />
                          </Button>
                          <Button
                            size="sm"
                            variant="outline"
                            className="text-red-500 hover:text-red-600"
                            onClick={() => setRejectTarget(req)}
                          >
                            <X className="h-4 w-4" />
                          </Button>
                        </div>
                      )}
                      {req.state === "blocked" && (
                        <ShieldAlert className="h-5 w-5 text-red-500" />
                      )}
                    </TableCell>
                  </TableRow>
                ))
              )}
            </TableBody>
          </Table>
        </TabsContent>

        <TabsContent value="certificates" className="mt-4">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Device</TableHead>
                <TableHead>Name</TableHead>
                <TableHead>Common Name</TableHead>
                <TableHead>Serial</TableHead>
                <TableHead>Status</TableHead>
                <TableHead>Expires</TableHead>
                <TableHead></TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {certsLoading ? (
                Array.from({ length: 3 }).map((_, i) => <TableRowSkeleton key={i} columns={7} />)
              ) : !certs || certs.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={7}>
                    <EmptyState
                      icon={ShieldCheck}
                      title="No certificates issued"
                      description="Approved certificates will appear here."
                    />
                  </TableCell>
                </TableRow>
              ) : (
                certs.map((cert) => (
                  <TableRow key={cert.id}>
                    <TableCell className="font-mono text-sm">{cert.deviceId}</TableCell>
                    <TableCell>{cert.name}</TableCell>
                    <TableCell className="text-muted-foreground">{cert.commonName}</TableCell>
                    <TableCell className="font-mono text-xs text-muted-foreground">
                      {cert.serialNumber.substring(0, 16)}...
                    </TableCell>
                    <TableCell>{statusBadge(cert.status)}</TableCell>
                    <TableCell className="text-muted-foreground">
                      {formatDate(cert.notAfter)} ({timeUntil(cert.notAfter)})
                    </TableCell>
                    <TableCell>
                      {!cert.revoked && (
                        <Button
                          size="sm"
                          variant="outline"
                          className="text-red-500 hover:text-red-600"
                          onClick={() => setRevokeTarget(cert)}
                        >
                          <Ban className="mr-1 h-3 w-3" /> Revoke
                        </Button>
                      )}
                    </TableCell>
                  </TableRow>
                ))
              )}
            </TableBody>
          </Table>
        </TabsContent>
      </Tabs>

      <ConfirmDialog
        open={!!rejectTarget}
        onOpenChange={(v) => { if (!v) setRejectTarget(null); }}
        title="Reject Certificate Request"
        description={`Reject the certificate request "${rejectTarget?.name}" from device ${rejectTarget?.deviceId}?`}
        confirmLabel="Reject"
        variant="destructive"
        onConfirm={() => rejectTarget && rejectMutation.mutate(rejectTarget.id)}
        loading={rejectMutation.isPending}
      />

      <ConfirmDialog
        open={!!revokeTarget}
        onOpenChange={(v) => { if (!v) setRevokeTarget(null); }}
        title="Revoke Certificate"
        description={`Revoke certificate "${revokeTarget?.name}" (serial ${revokeTarget?.serialNumber.substring(0, 16)}...) for device ${revokeTarget?.deviceId}? This cannot be undone.`}
        confirmLabel="Revoke"
        variant="destructive"
        onConfirm={() => revokeTarget && revokeMutation.mutate(revokeTarget.id)}
        loading={revokeMutation.isPending}
      />
    </div>
  );
}
