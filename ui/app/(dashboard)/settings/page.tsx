"use client";

import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useSession } from "next-auth/react";
import {
  getOrganization,
  updateOrganization,
  listMembers,
  addMember,
  removeMember,
  listEnrollmentTokens,
  createEnrollmentToken,
  deleteEnrollmentToken,
  listApiKeys,
  createApiKey,
  deleteApiKey,
  type Organization,
  type OrgMember,
  type EnrollmentToken,
  type ApiKeyEntry,
} from "@/lib/api/organizations";
import { useOrganization } from "@/lib/hooks/use-organization";
import { PageHeader } from "@/components/page-header";
import { ConfirmDialog } from "@/components/confirm-dialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { Skeleton } from "@/components/ui/skeleton";
import { EmptyState } from "@/components/empty-state";
import { Users, Key, KeyRound, Plus, Copy, Trash2, Check } from "lucide-react";
import { useState } from "react";
import { formatDistanceToNow } from "date-fns";
import { toast } from "sonner";

export default function SettingsPage() {
  const { data: session } = useSession();
  const { orgId, currentOrg } = useOrganization();
  const queryClient = useQueryClient();
  const token = session?.accessToken ?? "";

  // General
  const { data: org } = useQuery({
    queryKey: ["organization", orgId],
    queryFn: () => getOrganization(token),
    enabled: !!token && !!orgId,
  });

  const [orgName, setOrgName] = useState<string | null>(null);
  const [orgDesc, setOrgDesc] = useState<string | null>(null);
  const [orgDirty, setOrgDirty] = useState(false);

  const initOrgForm = () => {
    if (org) {
      setOrgName(org.name);
      setOrgDesc(org.description ?? "");
      setOrgDirty(false);
    }
  };

  const updateOrgMutation = useMutation({
    mutationFn: () => updateOrganization(token, { name: orgName ?? org?.name ?? "", description: orgDesc ?? org?.description ?? "" }),
    onMutate: async () => {
      await queryClient.cancelQueries({ queryKey: ["organization", orgId] });
      const previous = queryClient.getQueryData<Organization>(["organization", orgId]);
      queryClient.setQueryData<Organization>(["organization", orgId], (old) =>
        old ? { ...old, name: orgName ?? old.name, description: orgDesc ?? old.description } : old,
      );
      return { previous };
    },
    onSuccess: () => {
      toast.success("Organization updated");
      setOrgName(null);
      setOrgDesc(null);
      setOrgDirty(false);
      queryClient.invalidateQueries({ queryKey: ["me"] });
    },
    onError: (err: Error, _vars, context) => {
      if (context?.previous) {
        queryClient.setQueryData(["organization", orgId], context.previous);
      }
      toast.error(err.message);
    },
  });

  // Members
  const { data: members, isLoading: membersLoading } = useQuery({
    queryKey: ["org-members", orgId],
    queryFn: () => listMembers(token),
    enabled: !!token && !!orgId,
  });
  const [inviteOpen, setInviteOpen] = useState(false);
  const [inviteEmail, setInviteEmail] = useState("");
  const [inviteRole, setInviteRole] = useState("member");
  const [removeMemberTarget, setRemoveMemberTarget] = useState<OrgMember | null>(null);

  const addMemberMutation = useMutation({
    mutationFn: () => addMember(token, { email: inviteEmail, role: inviteRole }),
    onSuccess: () => {
      toast.success("Member invited");
      queryClient.invalidateQueries({ queryKey: ["org-members", orgId] });
      setInviteOpen(false);
      setInviteEmail("");
      setInviteRole("member");
    },
    onError: (err: Error) => toast.error(err.message),
  });

  const removeMemberMutation = useMutation({
    mutationFn: (userId: number) => removeMember(token, userId),
    onSuccess: () => {
      toast.success("Member removed");
      queryClient.invalidateQueries({ queryKey: ["org-members", orgId] });
      setRemoveMemberTarget(null);
    },
    onError: (err: Error) => toast.error(err.message),
  });

  // Enrollment Tokens
  const { data: tokens, isLoading: tokensLoading } = useQuery({
    queryKey: ["enrollment-tokens", orgId],
    queryFn: () => listEnrollmentTokens(token),
    enabled: !!token && !!orgId,
  });
  const [tokenOpen, setTokenOpen] = useState(false);
  const [tokenDesc, setTokenDesc] = useState("");
  const [tokenMaxUses, setTokenMaxUses] = useState("100");
  const [deleteTokenTarget, setDeleteTokenTarget] = useState<EnrollmentToken | null>(null);

  const [createdToken, setCreatedToken] = useState<string | null>(null);

  const createTokenMutation = useMutation({
    mutationFn: () => createEnrollmentToken(token, { description: tokenDesc, maxUses: Number(tokenMaxUses) }),
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: ["enrollment-tokens", orgId] });
      setCreatedToken(data.token);
      setTokenDesc("");
    },
    onError: (err: Error) => toast.error(err.message),
  });

  const deleteTokenMutation = useMutation({
    mutationFn: (id: number) => deleteEnrollmentToken(token, id),
    onSuccess: () => {
      toast.success("Token revoked");
      queryClient.invalidateQueries({ queryKey: ["enrollment-tokens", orgId] });
      setDeleteTokenTarget(null);
    },
    onError: (err: Error) => toast.error(err.message),
  });

  // API Keys
  const { data: apiKeys, isLoading: apiKeysLoading } = useQuery({
    queryKey: ["api-keys", orgId],
    queryFn: () => listApiKeys(token),
    enabled: !!token && !!orgId,
  });
  const [apiKeyOpen, setApiKeyOpen] = useState(false);
  const [apiKeyName, setApiKeyName] = useState("");
  const [createdKey, setCreatedKey] = useState<string | null>(null);
  const [deleteKeyTarget, setDeleteKeyTarget] = useState<ApiKeyEntry | null>(null);
  const [copied, setCopied] = useState(false);

  const createApiKeyMutation = useMutation({
    mutationFn: () => createApiKey(token, { name: apiKeyName }),
    onSuccess: (data) => {
      toast.success("API key created");
      queryClient.invalidateQueries({ queryKey: ["api-keys", orgId] });
      setApiKeyName("");
      setCreatedKey(data.rawKey);
    },
    onError: (err: Error) => toast.error(err.message),
  });

  const deleteKeyMutation = useMutation({
    mutationFn: (id: number) => deleteApiKey(token, id),
    onSuccess: () => {
      toast.success("API key revoked");
      queryClient.invalidateQueries({ queryKey: ["api-keys", orgId] });
      setDeleteKeyTarget(null);
    },
    onError: (err: Error) => toast.error(err.message),
  });

  return (
    <div className="space-y-6">
      <PageHeader title="Settings" description="Manage your organization" />

      <Tabs defaultValue="general" onValueChange={(v) => { if (v === "general") initOrgForm(); }}>
        <TabsList>
          <TabsTrigger value="general">General</TabsTrigger>
          <TabsTrigger value="members">Members</TabsTrigger>
          <TabsTrigger value="tokens">Tokens</TabsTrigger>
          <TabsTrigger value="api-keys">API Keys</TabsTrigger>
        </TabsList>

        {/* General Tab */}
        <TabsContent value="general" className="mt-4">
          <Card>
            <CardHeader>
              <CardTitle>Organization</CardTitle>
              <CardDescription>Update your organization details.</CardDescription>
            </CardHeader>
            <CardContent className="space-y-4 max-w-lg">
              <div className="space-y-2">
                <Label htmlFor="org-name">Name</Label>
                <Input
                  id="org-name"
                  value={orgName ?? org?.name ?? ""}
                  onChange={(e) => { setOrgName(e.target.value); setOrgDirty(true); }}
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="org-desc">Description</Label>
                <Textarea
                  id="org-desc"
                  value={orgDesc ?? org?.description ?? ""}
                  onChange={(e) => { setOrgDesc(e.target.value); setOrgDirty(true); }}
                  rows={3}
                />
              </div>
              <div className="space-y-2">
                <Label>Slug</Label>
                <Input value={org?.slug ?? ""} disabled />
              </div>
              <div className="space-y-2">
                <Label>Your Role</Label>
                <div>
                  <Badge>{currentOrg?.role ?? "--"}</Badge>
                </div>
              </div>
              <Button
                onClick={() => updateOrgMutation.mutate()}
                disabled={!orgDirty || updateOrgMutation.isPending}
              >
                {updateOrgMutation.isPending ? "Saving..." : "Save Changes"}
              </Button>
            </CardContent>
          </Card>
        </TabsContent>

        {/* Members Tab */}
        <TabsContent value="members" className="mt-4">
          <Card>
            <CardHeader className="flex flex-row items-center justify-between">
              <div>
                <CardTitle className="flex items-center gap-2">
                  <Users className="h-5 w-5" />
                  Members
                </CardTitle>
                <CardDescription>Manage team access to your organization.</CardDescription>
              </div>
              <Button onClick={() => setInviteOpen(true)}>
                <Plus className="mr-2 h-4 w-4" />
                Invite
              </Button>
            </CardHeader>
            <CardContent>
              <div className="rounded-xl border border-border/50 overflow-hidden">
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>User</TableHead>
                      <TableHead>Role</TableHead>
                      <TableHead>Joined</TableHead>
                      <TableHead className="w-10" />
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {membersLoading ? (
                      Array.from({ length: 3 }).map((_, i) => (
                        <TableRow key={i}>
                          {Array.from({ length: 4 }).map((_, j) => (
                            <TableCell key={j}><Skeleton className="h-4 w-full" /></TableCell>
                          ))}
                        </TableRow>
                      ))
                    ) : !members || members.length === 0 ? (
                      <TableRow>
                        <TableCell colSpan={4}>
                          <EmptyState title="No members" description="Invite team members." />
                        </TableCell>
                      </TableRow>
                    ) : (
                      members.map((m) => {
                        const name = m.displayName ?? m.email?.split("@")[0] ?? "?";
                        const initials = name.split(" ").map((n) => n[0]).join("").slice(0, 2).toUpperCase();
                        return (
                          <TableRow key={m.userId}>
                            <TableCell>
                              <div className="flex items-center gap-3">
                                <Avatar className="h-8 w-8">
                                  <AvatarFallback className="text-xs">{initials}</AvatarFallback>
                                </Avatar>
                                <div>
                                  <p className="text-sm font-medium">{m.displayName ?? m.email?.split("@")[0] ?? "Unknown"}</p>
                                  <p className="text-xs text-muted-foreground">{m.email}</p>
                                </div>
                              </div>
                            </TableCell>
                            <TableCell>
                              <Badge variant={m.role === "owner" ? "default" : "secondary"}>
                                {m.role}
                              </Badge>
                            </TableCell>
                            <TableCell>
                              {formatDistanceToNow(new Date(m.joinedAt), { addSuffix: true })}
                            </TableCell>
                            <TableCell>
                              {m.role !== "owner" && (
                                <Button
                                  variant="ghost"
                                  size="icon"
                                  className="h-8 w-8 text-destructive"
                                  onClick={() => setRemoveMemberTarget(m)}
                                >
                                  <Trash2 className="h-4 w-4" />
                                </Button>
                              )}
                            </TableCell>
                          </TableRow>
                        );
                      })
                    )}
                  </TableBody>
                </Table>
              </div>
            </CardContent>
          </Card>
        </TabsContent>

        {/* Tokens Tab */}
        <TabsContent value="tokens" className="mt-4">
          <Card>
            <CardHeader className="flex flex-row items-center justify-between">
              <div>
                <CardTitle className="flex items-center gap-2">
                  <Key className="h-5 w-5" />
                  Enrollment Tokens
                </CardTitle>
                <CardDescription>Tokens used by devices to register with the fleet.</CardDescription>
              </div>
              <Button onClick={() => setTokenOpen(true)}>
                <Plus className="mr-2 h-4 w-4" />
                Create Token
              </Button>
            </CardHeader>
            <CardContent>
              <div className="rounded-xl border border-border/50 overflow-hidden">
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>Token</TableHead>
                      <TableHead>Description</TableHead>
                      <TableHead>Usage</TableHead>
                      <TableHead>Expires</TableHead>
                      <TableHead className="w-10" />
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {tokensLoading ? (
                      Array.from({ length: 2 }).map((_, i) => (
                        <TableRow key={i}>
                          {Array.from({ length: 5 }).map((_, j) => (
                            <TableCell key={j}><Skeleton className="h-4 w-full" /></TableCell>
                          ))}
                        </TableRow>
                      ))
                    ) : !tokens || tokens.length === 0 ? (
                      <TableRow>
                        <TableCell colSpan={5}>
                          <EmptyState title="No tokens" description="Create a token to enroll devices." />
                        </TableCell>
                      </TableRow>
                    ) : (
                      tokens.map((t) => (
                        <TableRow key={t.id}>
                          <TableCell className="font-mono text-xs">{t.token.slice(0, 16)}...</TableCell>
                          <TableCell>{t.name}</TableCell>
                          <TableCell>{t.useCount} / {t.maxUses}</TableCell>
                          <TableCell>{t.expiresAt ? formatDistanceToNow(new Date(t.expiresAt), { addSuffix: true }) : "Never"}</TableCell>
                          <TableCell>
                            <Button variant="ghost" size="icon" className="h-8 w-8 text-destructive" onClick={() => setDeleteTokenTarget(t)}>
                              <Trash2 className="h-4 w-4" />
                            </Button>
                          </TableCell>
                        </TableRow>
                      ))
                    )}
                  </TableBody>
                </Table>
              </div>
            </CardContent>
          </Card>
        </TabsContent>

        {/* API Keys Tab */}
        <TabsContent value="api-keys" className="mt-4">
          <Card>
            <CardHeader className="flex flex-row items-center justify-between">
              <div>
                <CardTitle className="flex items-center gap-2">
                  <KeyRound className="h-5 w-5" />
                  API Keys
                </CardTitle>
                <CardDescription>Keys for programmatic access to the EdgeGuardian API.</CardDescription>
              </div>
              <Button onClick={() => setApiKeyOpen(true)}>
                <Plus className="mr-2 h-4 w-4" />
                Create Key
              </Button>
            </CardHeader>
            <CardContent className="space-y-4">
              {createdKey && (
                <div className="rounded-lg border border-emerald-500/30 bg-emerald-500/5 p-3">
                  <p className="text-sm font-medium text-green-800 dark:text-green-200">
                    API Key created! Copy it now — it won&apos;t be shown again.
                  </p>
                  <div className="mt-2 flex items-center gap-2">
                    <code className="rounded bg-green-100 px-2 py-1 text-xs font-mono dark:bg-green-800">{createdKey}</code>
                    <Button
                      variant="ghost"
                      size="icon"
                      onClick={() => {
                        navigator.clipboard.writeText(createdKey);
                        setCopied(true);
                        setTimeout(() => setCopied(false), 2000);
                      }}
                    >
                      {copied ? <Check className="h-4 w-4" /> : <Copy className="h-4 w-4" />}
                    </Button>
                  </div>
                </div>
              )}
              <div className="rounded-xl border border-border/50 overflow-hidden">
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>Name</TableHead>
                      <TableHead>Prefix</TableHead>
                      <TableHead>Last Used</TableHead>
                      <TableHead>Created</TableHead>
                      <TableHead className="w-10" />
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {apiKeysLoading ? (
                      Array.from({ length: 2 }).map((_, i) => (
                        <TableRow key={i}>
                          {Array.from({ length: 5 }).map((_, j) => (
                            <TableCell key={j}><Skeleton className="h-4 w-full" /></TableCell>
                          ))}
                        </TableRow>
                      ))
                    ) : !apiKeys || apiKeys.length === 0 ? (
                      <TableRow>
                        <TableCell colSpan={5}>
                          <EmptyState title="No API keys" description="Create a key for programmatic access." />
                        </TableCell>
                      </TableRow>
                    ) : (
                      apiKeys.map((k) => (
                        <TableRow key={k.id}>
                          <TableCell className="font-medium">{k.name}</TableCell>
                          <TableCell className="font-mono text-xs">{k.prefix}...</TableCell>
                          <TableCell>{k.lastUsedAt ? formatDistanceToNow(new Date(k.lastUsedAt), { addSuffix: true }) : "Never"}</TableCell>
                          <TableCell>{formatDistanceToNow(new Date(k.createdAt), { addSuffix: true })}</TableCell>
                          <TableCell>
                            <Button variant="ghost" size="icon" className="h-8 w-8 text-destructive" onClick={() => setDeleteKeyTarget(k)}>
                              <Trash2 className="h-4 w-4" />
                            </Button>
                          </TableCell>
                        </TableRow>
                      ))
                    )}
                  </TableBody>
                </Table>
              </div>
            </CardContent>
          </Card>
        </TabsContent>
      </Tabs>

      {/* Invite Member Dialog */}
      <Dialog open={inviteOpen} onOpenChange={setInviteOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Invite Member</DialogTitle>
            <DialogDescription>Add a new member to your organization.</DialogDescription>
          </DialogHeader>
          <div className="space-y-4">
            <div className="space-y-2">
              <Label htmlFor="invite-email">Email</Label>
              <Input id="invite-email" type="email" placeholder="name@example.com" value={inviteEmail} onChange={(e) => setInviteEmail(e.target.value)} />
            </div>
            <div className="space-y-2">
              <Label>Role</Label>
              <Select value={inviteRole} onValueChange={setInviteRole}>
                <SelectTrigger><SelectValue /></SelectTrigger>
                <SelectContent>
                  <SelectItem value="member">Member</SelectItem>
                  <SelectItem value="admin">Admin</SelectItem>
                </SelectContent>
              </Select>
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setInviteOpen(false)}>Cancel</Button>
            <Button onClick={() => addMemberMutation.mutate()} disabled={!inviteEmail || addMemberMutation.isPending}>
              {addMemberMutation.isPending ? "Inviting..." : "Invite"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Create Token Dialog */}
      <Dialog open={tokenOpen} onOpenChange={(v) => { setTokenOpen(v); if (!v) setCreatedToken(null); }}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Create Enrollment Token</DialogTitle>
            <DialogDescription>Create a token devices will use to register.</DialogDescription>
          </DialogHeader>
          {createdToken ? (
            <div className="space-y-3">
              <div className="rounded-lg border border-emerald-500/30 bg-emerald-500/5 p-3">
                <p className="text-sm font-medium mb-2">Token created! Copy it now — it won&apos;t be shown again.</p>
                <div className="flex items-center gap-2">
                  <code className="flex-1 rounded bg-muted px-2 py-1 text-xs break-all">{createdToken}</code>
                  <Button size="sm" variant="outline" onClick={() => { navigator.clipboard.writeText(createdToken); toast.success("Copied!"); }}>
                    Copy
                  </Button>
                </div>
              </div>
              <DialogFooter>
                <Button onClick={() => { setTokenOpen(false); setCreatedToken(null); }}>Done</Button>
              </DialogFooter>
            </div>
          ) : (
            <>
              <div className="space-y-4">
                <div className="space-y-2">
                  <Label htmlFor="token-desc">Name</Label>
                  <Input id="token-desc" placeholder="Production fleet" value={tokenDesc} onChange={(e) => setTokenDesc(e.target.value)} />
                </div>
                <div className="space-y-2">
                  <Label htmlFor="token-max">Max Uses</Label>
                  <Input id="token-max" type="number" value={tokenMaxUses} onChange={(e) => setTokenMaxUses(e.target.value)} />
                </div>
              </div>
              <DialogFooter>
                <Button variant="outline" onClick={() => setTokenOpen(false)}>Cancel</Button>
                <Button onClick={() => createTokenMutation.mutate()} disabled={!tokenDesc || createTokenMutation.isPending}>
                  {createTokenMutation.isPending ? "Creating..." : "Create"}
                </Button>
              </DialogFooter>
            </>
          )}
        </DialogContent>
      </Dialog>

      {/* Create API Key Dialog */}
      <Dialog open={apiKeyOpen} onOpenChange={(v) => { setApiKeyOpen(v); if (!v) setCreatedKey(null); }}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Create API Key</DialogTitle>
            <DialogDescription>Create a key for programmatic API access.</DialogDescription>
          </DialogHeader>
          <div className="space-y-4">
            <div className="space-y-2">
              <Label htmlFor="api-key-name">Name</Label>
              <Input id="api-key-name" placeholder="CI/CD Pipeline" value={apiKeyName} onChange={(e) => setApiKeyName(e.target.value)} />
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setApiKeyOpen(false)}>Cancel</Button>
            <Button onClick={() => createApiKeyMutation.mutate()} disabled={!apiKeyName || createApiKeyMutation.isPending}>
              {createApiKeyMutation.isPending ? "Creating..." : "Create"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Confirm dialogs */}
      <ConfirmDialog
        open={!!removeMemberTarget}
        onOpenChange={(v) => { if (!v) setRemoveMemberTarget(null); }}
        title="Remove Member"
        description={`Remove ${removeMemberTarget?.displayName ?? removeMemberTarget?.email?.split("@")[0] ?? "this member"} from the organization?`}
        confirmLabel="Remove"
        variant="destructive"
        onConfirm={() => { if (removeMemberTarget) removeMemberMutation.mutate(removeMemberTarget.userId); }}
        loading={removeMemberMutation.isPending}
      />
      <ConfirmDialog
        open={!!deleteTokenTarget}
        onOpenChange={(v) => { if (!v) setDeleteTokenTarget(null); }}
        title="Revoke Token"
        description="Devices using this token will no longer be able to enroll."
        confirmLabel="Revoke"
        variant="destructive"
        onConfirm={() => { if (deleteTokenTarget) deleteTokenMutation.mutate(deleteTokenTarget.id); }}
        loading={deleteTokenMutation.isPending}
      />
      <ConfirmDialog
        open={!!deleteKeyTarget}
        onOpenChange={(v) => { if (!v) setDeleteKeyTarget(null); }}
        title="Revoke API Key"
        description={`Revoke "${deleteKeyTarget?.name}"? Any integrations using this key will stop working.`}
        confirmLabel="Revoke"
        variant="destructive"
        onConfirm={() => { if (deleteKeyTarget) deleteKeyMutation.mutate(deleteKeyTarget.id); }}
        loading={deleteKeyMutation.isPending}
      />
    </div>
  );
}
