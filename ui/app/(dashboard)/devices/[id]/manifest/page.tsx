"use client";

import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useSession } from "next-auth/react";
import { useParams, useRouter } from "next/navigation";
import { getDeviceManifest, updateDeviceManifest } from "@/lib/api/devices";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { ArrowLeft, Save } from "lucide-react";
import { useState, useCallback } from "react";
import Editor from "@monaco-editor/react";

export default function ManifestEditorPage() {
  const { id } = useParams<{ id: string }>();
  const { data: session } = useSession();
  const router = useRouter();
  const queryClient = useQueryClient();

  const { data: manifest, isLoading } = useQuery({
    queryKey: ["device-manifest", id],
    queryFn: () => getDeviceManifest(session?.accessToken ?? "", id),
    enabled: !!session?.accessToken && !!id,
  });

  const initialYaml =
    typeof manifest === "string"
      ? manifest
      : manifest
        ? JSON.stringify(manifest, null, 2)
        : "";

  const [editorValue, setEditorValue] = useState<string | undefined>(undefined);

  const currentValue = editorValue ?? initialYaml;

  const mutation = useMutation({
    mutationFn: (yamlContent: string) =>
      updateDeviceManifest(session?.accessToken ?? "", id, yamlContent),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["device-manifest", id] });
    },
  });

  const handleSave = useCallback(() => {
    if (currentValue) {
      mutation.mutate(currentValue);
    }
  }, [currentValue, mutation]);

  const hasChanges = editorValue !== undefined && editorValue !== initialYaml;

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-4">
          <Button
            variant="ghost"
            size="icon"
            onClick={() => router.push(`/devices/${id}`)}
          >
            <ArrowLeft className="h-4 w-4" />
          </Button>
          <div>
            <h1 className="text-2xl font-bold">Manifest Editor</h1>
            <p className="text-sm text-muted-foreground font-mono">{id}</p>
          </div>
        </div>
        <Button
          onClick={handleSave}
          disabled={!hasChanges || mutation.isPending}
        >
          <Save className="mr-2 h-4 w-4" />
          {mutation.isPending ? "Saving..." : "Save Manifest"}
        </Button>
      </div>

      {mutation.isSuccess && (
        <div className="rounded-md border border-green-200 bg-green-50 p-3 text-sm text-green-800 dark:border-green-800 dark:bg-green-900/30 dark:text-green-200">
          Manifest saved successfully. Changes will be applied on next
          reconciliation cycle.
        </div>
      )}

      {mutation.isError && (
        <div className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800 dark:border-red-800 dark:bg-red-900/30 dark:text-red-200">
          Failed to save manifest: {mutation.error.message}
        </div>
      )}

      <Card>
        <CardHeader>
          <CardTitle>YAML Manifest</CardTitle>
        </CardHeader>
        <CardContent>
          {isLoading ? (
            <Skeleton className="h-[500px] w-full" />
          ) : (
            <Editor
              height="500px"
              defaultLanguage="yaml"
              defaultValue={initialYaml}
              onChange={(value) => setEditorValue(value ?? "")}
              theme="vs-dark"
              options={{
                minimap: { enabled: false },
                fontSize: 13,
                lineNumbers: "on",
                scrollBeyondLastLine: false,
                wordWrap: "on",
                tabSize: 2,
              }}
            />
          )}
        </CardContent>
      </Card>
    </div>
  );
}
