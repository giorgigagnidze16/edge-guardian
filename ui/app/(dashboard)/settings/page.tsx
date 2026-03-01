"use client";

import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useSession } from "next-auth/react";
import {
  getMe,
  listMembers,
  listEnrollmentTokens,
  listApiKeys,
  createEnrollmentToken,
  createApiKey,
  addMember,
  type OrgMember,
  type EnrollmentToken,
  type ApiKeyEntry,
} from "@/lib/api/organizations";
import { DataTable, type Column } from "@/components/data-table";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
  CardDescription,
} from "@/components/ui/card";
import {
  Users,
  Key,
  KeyRound,
  Plus,
  Copy,
} from "lucide-react";
import { useState } from "react";
import { formatDistanceToNow } from "date-fns";

const memberColumns: Column<OrgMember>[] = [
  { header: "Email", accessorKey: "email" },
  { header: "Name", accessorKey: "displayName" },
  {
    header: "Role",
    cell: (row) => (
      <Badge variant={row.role === "owner" ? "default" : "secondary"}>
        {row.role}
      </Badge>
    ),
  },
  {
    header: "Joined",
    cell: (row) =>
      formatDistanceToNow(new Date(row.joinedAt), { addSuffix: true }),
  },
];

const tokenColumns: Column<EnrollmentToken>[] = [
  {
    header: "Token",
    cell: (row) => (
      <span className="font-mono text-xs">
        {row.token.slice(0, 12)}...
      </span>
    ),
  },
  { header: "Description", accessorKey: "description" },
  {
    header: "Usage",
    cell: (row) => `${row.currentUses} / ${row.maxUses}`,
  },
  {
    header: "Expires",
    cell: (row) =>
      row.expiresAt
        ? formatDistanceToNow(new Date(row.expiresAt), { addSuffix: true })
        : "Never",
  },
];

const apiKeyColumns: Column<ApiKeyEntry>[] = [
  { header: "Name", accessorKey: "name" },
  {
    header: "Prefix",
    cell: (row) => (
      <span className="font-mono text-xs">{row.prefix}...</span>
    ),
  },
  {
    header: "Last Used",
    cell: (row) =>
      row.lastUsedAt
        ? formatDistanceToNow(new Date(row.lastUsedAt), { addSuffix: true })
        : "Never",
  },
  {
    header: "Created",
    cell: (row) =>
      formatDistanceToNow(new Date(row.createdAt), { addSuffix: true }),
  },
];

export default function SettingsPage() {
  const { data: session } = useSession();
  const queryClient = useQueryClient();
  const orgId = 1; // Default org; dynamic with org-switcher

  const [newTokenDesc, setNewTokenDesc] = useState("");
  const [newApiKeyName, setNewApiKeyName] = useState("");
  const [createdKey, setCreatedKey] = useState<string | null>(null);

  const { data: me } = useQuery({
    queryKey: ["me"],
    queryFn: () => getMe(session?.accessToken ?? ""),
    enabled: !!session?.accessToken,
  });

  const { data: members, isLoading: membersLoading } = useQuery({
    queryKey: ["org-members", orgId],
    queryFn: () => listMembers(session?.accessToken ?? "", orgId),
    enabled: !!session?.accessToken,
  });

  const { data: tokens, isLoading: tokensLoading } = useQuery({
    queryKey: ["enrollment-tokens", orgId],
    queryFn: () => listEnrollmentTokens(session?.accessToken ?? "", orgId),
    enabled: !!session?.accessToken,
  });

  const { data: apiKeys, isLoading: apiKeysLoading } = useQuery({
    queryKey: ["api-keys", orgId],
    queryFn: () => listApiKeys(session?.accessToken ?? "", orgId),
    enabled: !!session?.accessToken,
  });

  const createTokenMutation = useMutation({
    mutationFn: () =>
      createEnrollmentToken(session?.accessToken ?? "", orgId, {
        description: newTokenDesc,
        maxUses: 100,
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["enrollment-tokens", orgId] });
      setNewTokenDesc("");
    },
  });

  const createApiKeyMutation = useMutation({
    mutationFn: () =>
      createApiKey(session?.accessToken ?? "", orgId, {
        name: newApiKeyName,
      }),
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: ["api-keys", orgId] });
      setNewApiKeyName("");
      setCreatedKey(data.rawKey);
    },
  });

  return (
    <div className="space-y-8">
      <h1 className="text-2xl font-bold">Settings</h1>

      {/* Organization info */}
      <Card>
        <CardHeader>
          <CardTitle>Organization</CardTitle>
          <CardDescription>
            Manage your organization settings and access.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <dl className="grid grid-cols-2 gap-4 text-sm max-w-md">
            <dt className="text-muted-foreground">Name</dt>
            <dd>{me?.organizations?.[0]?.name ?? "--"}</dd>
            <dt className="text-muted-foreground">Slug</dt>
            <dd className="font-mono">{me?.organizations?.[0]?.slug ?? "--"}</dd>
            <dt className="text-muted-foreground">Your Role</dt>
            <dd>
              <Badge>{me?.organizations?.[0]?.role ?? "--"}</Badge>
            </dd>
          </dl>
        </CardContent>
      </Card>

      {/* Members */}
      <Card>
        <CardHeader className="flex flex-row items-center justify-between">
          <div>
            <CardTitle className="flex items-center gap-2">
              <Users className="h-5 w-5" />
              Members
            </CardTitle>
          </div>
        </CardHeader>
        <CardContent>
          <DataTable
            columns={memberColumns}
            data={members ?? []}
            isLoading={membersLoading}
            emptyTitle="No members"
            emptyDescription="Invite team members to collaborate."
          />
        </CardContent>
      </Card>

      {/* Enrollment Tokens */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <Key className="h-5 w-5" />
            Enrollment Tokens
          </CardTitle>
          <CardDescription>
            Tokens used by devices to register with the fleet.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="flex gap-2">
            <Input
              placeholder="Token description..."
              value={newTokenDesc}
              onChange={(e) => setNewTokenDesc(e.target.value)}
              className="max-w-sm"
            />
            <Button
              onClick={() => createTokenMutation.mutate()}
              disabled={!newTokenDesc || createTokenMutation.isPending}
            >
              <Plus className="mr-2 h-4 w-4" />
              Create Token
            </Button>
          </div>
          <DataTable
            columns={tokenColumns}
            data={tokens ?? []}
            isLoading={tokensLoading}
            emptyTitle="No enrollment tokens"
            emptyDescription="Create a token to allow devices to enroll."
          />
        </CardContent>
      </Card>

      {/* API Keys */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <KeyRound className="h-5 w-5" />
            API Keys
          </CardTitle>
          <CardDescription>
            Keys for programmatic access to the EdgeGuardian API.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="flex gap-2">
            <Input
              placeholder="API key name..."
              value={newApiKeyName}
              onChange={(e) => setNewApiKeyName(e.target.value)}
              className="max-w-sm"
            />
            <Button
              onClick={() => createApiKeyMutation.mutate()}
              disabled={!newApiKeyName || createApiKeyMutation.isPending}
            >
              <Plus className="mr-2 h-4 w-4" />
              Create Key
            </Button>
          </div>
          {createdKey && (
            <div className="rounded-md border border-green-200 bg-green-50 p-3 dark:border-green-800 dark:bg-green-900/30">
              <p className="text-sm font-medium text-green-800 dark:text-green-200">
                API Key created! Copy it now — it won't be shown again.
              </p>
              <div className="mt-2 flex items-center gap-2">
                <code className="rounded bg-green-100 px-2 py-1 text-xs font-mono dark:bg-green-800">
                  {createdKey}
                </code>
                <Button
                  variant="ghost"
                  size="icon"
                  onClick={() => navigator.clipboard.writeText(createdKey)}
                >
                  <Copy className="h-4 w-4" />
                </Button>
              </div>
            </div>
          )}
          <DataTable
            columns={apiKeyColumns}
            data={apiKeys ?? []}
            isLoading={apiKeysLoading}
            emptyTitle="No API keys"
            emptyDescription="Create a key for programmatic access."
          />
        </CardContent>
      </Card>
    </div>
  );
}
