"use client";

import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useSession } from "next-auth/react";
import { useRouter } from "next/navigation";
import {
  listArtifacts,
  listDeployments,
  deleteArtifact,
  type OtaArtifact,
  type OtaDeployment,
} from "@/lib/api/ota";
import { useOrganization } from "@/lib/hooks/use-organization";
import { PageHeader } from "@/components/page-header";
import { UploadArtifactDialog } from "@/components/upload-artifact-dialog";
import { CreateDeploymentDialog } from "@/components/create-deployment-dialog";
import { ConfirmDialog } from "@/components/confirm-dialog";
import { StateBadge } from "@/components/state-badge";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Progress } from "@/components/ui/progress";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { TableRowSkeleton } from "@/components/loading-skeleton";
import { EmptyState } from "@/components/empty-state";
import { Upload, Rocket, MoreVertical, Trash2 } from "lucide-react";
import { useState } from "react";
import { formatDistanceToNow } from "date-fns";
import { toast } from "sonner";

function formatBytes(bytes: number): string {
  if (bytes === 0) return "0 B";
  const k = 1024;
  const sizes = ["B", "KB", "MB", "GB"];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return `${(bytes / Math.pow(k, i)).toFixed(1)} ${sizes[i]}`;
}

export default function OTAPage() {
  const { data: session } = useSession();
  const { orgId } = useOrganization();
  const router = useRouter();
  const queryClient = useQueryClient();
  const token = session?.accessToken ?? "";

  const [uploadOpen, setUploadOpen] = useState(false);
  const [deployOpen, setDeployOpen] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<OtaArtifact | null>(null);

  const { data: artifacts, isLoading: artifactsLoading } = useQuery({
    queryKey: ["ota-artifacts", orgId],
    queryFn: () => listArtifacts(token, orgId!),
    enabled: !!token && !!orgId,
  });

  const { data: deployments, isLoading: deploymentsLoading } = useQuery({
    queryKey: ["ota-deployments", orgId],
    queryFn: () => listDeployments(token, orgId!),
    enabled: !!token && !!orgId,
  });

  const deleteMutation = useMutation({
    mutationFn: (id: number) => deleteArtifact(token, orgId!, id),
    onSuccess: () => {
      toast.success("Artifact deleted");
      queryClient.invalidateQueries({ queryKey: ["ota-artifacts"] });
      setDeleteTarget(null);
    },
    onError: (err: Error) => toast.error(err.message),
  });

  return (
    <div className="space-y-6">
      <PageHeader title="OTA Updates" description="Manage firmware artifacts and deployments">
        <Button variant="outline" onClick={() => setDeployOpen(true)}>
          <Rocket className="mr-2 h-4 w-4" />
          New Deployment
        </Button>
        <Button onClick={() => setUploadOpen(true)}>
          <Upload className="mr-2 h-4 w-4" />
          Upload Artifact
        </Button>
      </PageHeader>

      <Tabs defaultValue="artifacts">
        <TabsList>
          <TabsTrigger value="artifacts">
            Artifacts {artifacts ? `(${artifacts.length})` : ""}
          </TabsTrigger>
          <TabsTrigger value="deployments">
            Deployments {deployments ? `(${deployments.length})` : ""}
          </TabsTrigger>
        </TabsList>

        <TabsContent value="artifacts" className="mt-4">
          <div className="rounded-xl border border-border/50 overflow-hidden">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Name</TableHead>
                  <TableHead>Version</TableHead>
                  <TableHead>Architecture</TableHead>
                  <TableHead>Size</TableHead>
                  <TableHead>Uploaded</TableHead>
                  <TableHead className="w-10" />
                </TableRow>
              </TableHeader>
              <TableBody>
                {artifactsLoading ? (
                  Array.from({ length: 3 }).map((_, i) => (
                    <TableRowSkeleton key={i} columns={6} />
                  ))
                ) : !artifacts || artifacts.length === 0 ? (
                  <TableRow>
                    <TableCell colSpan={6}>
                      <EmptyState
                        title="No artifacts"
                        description="Upload your first OTA artifact to get started."
                      />
                    </TableCell>
                  </TableRow>
                ) : (
                  artifacts.map((artifact) => (
                    <TableRow key={artifact.id}>
                      <TableCell className="font-medium">{artifact.name}</TableCell>
                      <TableCell>
                        <Badge variant="secondary" className="font-mono text-xs">
                          {artifact.version}
                        </Badge>
                      </TableCell>
                      <TableCell>
                        <Badge variant="outline" className="font-mono text-xs">
                          {artifact.architecture}
                        </Badge>
                      </TableCell>
                      <TableCell>{formatBytes(artifact.size)}</TableCell>
                      <TableCell>
                        {formatDistanceToNow(new Date(artifact.createdAt), { addSuffix: true })}
                      </TableCell>
                      <TableCell>
                        <DropdownMenu>
                          <DropdownMenuTrigger asChild>
                            <Button variant="ghost" size="icon" className="h-8 w-8">
                              <MoreVertical className="h-4 w-4" />
                            </Button>
                          </DropdownMenuTrigger>
                          <DropdownMenuContent align="end">
                            <DropdownMenuItem onClick={() => setDeployOpen(true)}>
                              <Rocket className="mr-2 h-4 w-4" />
                              Deploy
                            </DropdownMenuItem>
                            <DropdownMenuItem
                              className="text-destructive"
                              onClick={() => setDeleteTarget(artifact)}
                            >
                              <Trash2 className="mr-2 h-4 w-4" />
                              Delete
                            </DropdownMenuItem>
                          </DropdownMenuContent>
                        </DropdownMenu>
                      </TableCell>
                    </TableRow>
                  ))
                )}
              </TableBody>
            </Table>
          </div>
        </TabsContent>

        <TabsContent value="deployments" className="mt-4">
          <div className="rounded-xl border border-border/50 overflow-hidden">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>ID</TableHead>
                  <TableHead>Artifact</TableHead>
                  <TableHead>Strategy</TableHead>
                  <TableHead>State</TableHead>
                  <TableHead>Progress</TableHead>
                  <TableHead>Labels</TableHead>
                  <TableHead>Created</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {deploymentsLoading ? (
                  Array.from({ length: 3 }).map((_, i) => (
                    <TableRowSkeleton key={i} columns={7} />
                  ))
                ) : !deployments || deployments.length === 0 ? (
                  <TableRow>
                    <TableCell colSpan={7}>
                      <EmptyState
                        title="No deployments"
                        description="Create a deployment to push artifacts to your devices."
                      />
                    </TableCell>
                  </TableRow>
                ) : (
                  deployments.map((d) => {
                    const progress =
                      d.totalDevices && d.totalDevices > 0
                        ? Math.round(((d.completedDevices ?? 0) / d.totalDevices) * 100)
                        : 0;
                    return (
                      <TableRow
                        key={d.id}
                        className="cursor-pointer"
                        onClick={() => router.push(`/ota/${d.id}`)}
                      >
                        <TableCell className="font-mono text-xs">#{d.id}</TableCell>
                        <TableCell className="font-mono text-xs">#{d.artifactId}</TableCell>
                        <TableCell>
                          <Badge variant="outline">{d.strategy}</Badge>
                        </TableCell>
                        <TableCell>
                          <StateBadge state={d.state} />
                        </TableCell>
                        <TableCell className="w-32">
                          <div className="flex items-center gap-2">
                            <Progress value={progress} className="h-2" />
                            <span className="text-xs text-muted-foreground">{progress}%</span>
                          </div>
                        </TableCell>
                        <TableCell>
                          {Object.entries(d.labelSelector ?? {}).length === 0 ? (
                            <span className="text-muted-foreground">all</span>
                          ) : (
                            <div className="flex flex-wrap gap-1">
                              {Object.entries(d.labelSelector).map(([k, v]) => (
                                <Badge key={k} variant="outline" className="font-mono text-xs">
                                  {k}={v}
                                </Badge>
                              ))}
                            </div>
                          )}
                        </TableCell>
                        <TableCell>
                          {formatDistanceToNow(new Date(d.createdAt), { addSuffix: true })}
                        </TableCell>
                      </TableRow>
                    );
                  })
                )}
              </TableBody>
            </Table>
          </div>
        </TabsContent>
      </Tabs>

      <UploadArtifactDialog open={uploadOpen} onOpenChange={setUploadOpen} />
      <CreateDeploymentDialog open={deployOpen} onOpenChange={setDeployOpen} />
      <ConfirmDialog
        open={!!deleteTarget}
        onOpenChange={(v) => { if (!v) setDeleteTarget(null); }}
        title="Delete Artifact"
        description={`Are you sure you want to delete "${deleteTarget?.name} v${deleteTarget?.version}"? This action cannot be undone.`}
        confirmLabel="Delete"
        variant="destructive"
        onConfirm={() => {
          if (deleteTarget) deleteMutation.mutate(deleteTarget.id);
        }}
        loading={deleteMutation.isPending}
      />
    </div>
  );
}
