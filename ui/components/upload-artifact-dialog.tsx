"use client";

import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useSession } from "next-auth/react";
import { useOrganization } from "@/lib/hooks/use-organization";
import { createArtifact } from "@/lib/api/ota";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { toast } from "sonner";

interface UploadArtifactDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

export function UploadArtifactDialog({ open, onOpenChange }: UploadArtifactDialogProps) {
  const { data: session } = useSession();
  const { orgId } = useOrganization();
  const queryClient = useQueryClient();

  const [name, setName] = useState("");
  const [version, setVersion] = useState("");
  const [architecture, setArchitecture] = useState("");

  const mutation = useMutation({
    mutationFn: () =>
      createArtifact(session?.accessToken ?? "", orgId!, {
        name,
        version,
        architecture,
      }),
    onSuccess: () => {
      toast.success("Artifact created");
      queryClient.invalidateQueries({ queryKey: ["ota-artifacts"] });
      onOpenChange(false);
      setName("");
      setVersion("");
      setArchitecture("");
    },
    onError: (err: Error) => toast.error(err.message),
  });

  const isValid = name.trim() && version.trim() && architecture;

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Upload Artifact</DialogTitle>
          <DialogDescription>
            Register a new OTA artifact for deployment.
          </DialogDescription>
        </DialogHeader>
        <div className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor="name">Name</Label>
            <Input
              id="name"
              placeholder="e.g. edgeguardian-agent"
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
          </div>
          <div className="space-y-2">
            <Label htmlFor="version">Version</Label>
            <Input
              id="version"
              placeholder="e.g. 1.2.0"
              value={version}
              onChange={(e) => setVersion(e.target.value)}
            />
          </div>
          <div className="space-y-2">
            <Label>Architecture</Label>
            <Select value={architecture} onValueChange={setArchitecture}>
              <SelectTrigger>
                <SelectValue placeholder="Select architecture" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="amd64">amd64</SelectItem>
                <SelectItem value="arm64">arm64</SelectItem>
                <SelectItem value="armv7">armv7</SelectItem>
                <SelectItem value="armv6">armv6</SelectItem>
              </SelectContent>
            </Select>
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button
            onClick={() => mutation.mutate()}
            disabled={!isValid || mutation.isPending}
          >
            {mutation.isPending ? "Creating..." : "Create Artifact"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
