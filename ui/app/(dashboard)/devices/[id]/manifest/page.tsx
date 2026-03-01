"use client";

import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useSession } from "next-auth/react";
import { useParams, useRouter } from "next/navigation";
import { useTheme } from "next-themes";
import { getDeviceManifest, updateDeviceManifest } from "@/lib/api/devices";
import { PageHeader } from "@/components/page-header";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { ArrowLeft, Save } from "lucide-react";
import { useState, useEffect, useCallback } from "react";
import { toast } from "sonner";
import dynamic from "next/dynamic";

const Editor = dynamic(() => import("@monaco-editor/react"), { ssr: false });

export default function ManifestEditorPage() {
  const { id } = useParams<{ id: string }>();
  const { data: session } = useSession();
  const router = useRouter();
  const queryClient = useQueryClient();
  const { resolvedTheme } = useTheme();

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
      toast.success("Manifest saved. Changes will be applied on next reconciliation.");
      queryClient.invalidateQueries({ queryKey: ["device-manifest", id] });
      setEditorValue(undefined);
    },
    onError: (err: Error) => toast.error(`Failed to save: ${err.message}`),
  });

  const handleSave = useCallback(() => {
    if (currentValue) {
      mutation.mutate(currentValue);
    }
  }, [currentValue, mutation]);

  // Ctrl+S shortcut
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key === "s") {
        e.preventDefault();
        handleSave();
      }
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, [handleSave]);

  const hasChanges = editorValue !== undefined && editorValue !== initialYaml;

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-4">
          <Button
            variant="ghost"
            size="icon"
            onClick={() => router.push(`/devices/${id}`)}
          >
            <ArrowLeft className="h-4 w-4" />
          </Button>
          <PageHeader title="Manifest Editor" description={`${id} | Ctrl+S to save`} />
        </div>
        <Button
          onClick={handleSave}
          disabled={!hasChanges || mutation.isPending}
        >
          <Save className="mr-2 h-4 w-4" />
          {mutation.isPending ? "Saving..." : "Save Manifest"}
        </Button>
      </div>

      <Card>
        <CardContent className="p-0">
          {isLoading ? (
            <Skeleton className="h-[500px] w-full" />
          ) : (
            <Editor
              height="500px"
              defaultLanguage="yaml"
              defaultValue={initialYaml}
              onChange={(value) => setEditorValue(value ?? "")}
              theme={resolvedTheme === "dark" ? "vs-dark" : "vs"}
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
