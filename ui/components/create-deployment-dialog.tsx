"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useSession } from "next-auth/react";
import { useOrganization } from "@/lib/hooks/use-organization";
import { createDeployment, listArtifacts, type OtaArtifact } from "@/lib/api/ota";
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
import { Badge } from "@/components/ui/badge";
import { Separator } from "@/components/ui/separator";
import { toast } from "sonner";
import { ChevronRight, Plus, X } from "lucide-react";

interface CreateDeploymentDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

export function CreateDeploymentDialog({ open, onOpenChange }: CreateDeploymentDialogProps) {
  const { data: session } = useSession();
  const { orgId } = useOrganization();
  const queryClient = useQueryClient();
  const token = session?.accessToken ?? "";

  const [step, setStep] = useState(0);
  const [artifactId, setArtifactId] = useState<string>("");
  const [strategy, setStrategy] = useState("rolling");
  const [labelKey, setLabelKey] = useState("");
  const [labelValue, setLabelValue] = useState("");
  const [labels, setLabels] = useState<Record<string, string>>({});

  const { data: artifacts } = useQuery({
    queryKey: ["ota-artifacts", orgId],
    queryFn: () => listArtifacts(token),
    enabled: !!token && !!orgId && open,
  });

  const mutation = useMutation({
    mutationFn: () =>
      createDeployment(token, {
        artifactId: Number(artifactId),
        strategy,
        labelSelector: labels,
      }),
    onSuccess: () => {
      toast.success("Deployment created");
      queryClient.invalidateQueries({ queryKey: ["ota-deployments"] });
      onOpenChange(false);
      resetForm();
    },
    onError: (err: Error) => toast.error(err.message),
  });

  const resetForm = () => {
    setStep(0);
    setArtifactId("");
    setStrategy("rolling");
    setLabels({});
    setLabelKey("");
    setLabelValue("");
  };

  const selectedArtifact = artifacts?.find((a) => String(a.id) === artifactId);

  const stepTitles = ["Select Artifact", "Strategy", "Target Devices", "Review"];

  return (
    <Dialog open={open} onOpenChange={(v) => { onOpenChange(v); if (!v) resetForm(); }}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>Create Deployment</DialogTitle>
          <DialogDescription>
            Step {step + 1} of 4: {stepTitles[step]}
          </DialogDescription>
        </DialogHeader>

        {/* Step indicators */}
        <div className="flex items-center gap-1 text-xs text-muted-foreground">
          {stepTitles.map((title, i) => (
            <div key={title} className="flex items-center gap-1">
              <span className={i <= step ? "text-foreground font-medium" : ""}>
                {title}
              </span>
              {i < stepTitles.length - 1 && <ChevronRight className="h-3 w-3" />}
            </div>
          ))}
        </div>

        <Separator />

        {/* Step 0: Select Artifact */}
        {step === 0 && (
          <div className="space-y-2">
            <Label>Artifact</Label>
            <Select value={artifactId} onValueChange={setArtifactId}>
              <SelectTrigger>
                <SelectValue placeholder="Choose an artifact" />
              </SelectTrigger>
              <SelectContent>
                {(artifacts ?? []).map((a) => (
                  <SelectItem key={a.id} value={String(a.id)}>
                    {a.name} v{a.version} ({a.architecture})
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
        )}

        {/* Step 1: Strategy */}
        {step === 1 && (
          <div className="space-y-2">
            <Label>Deployment Strategy</Label>
            <Select value={strategy} onValueChange={setStrategy}>
              <SelectTrigger>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="rolling">Rolling Update</SelectItem>
                <SelectItem value="canary">Canary</SelectItem>
                <SelectItem value="all-at-once">All at Once</SelectItem>
              </SelectContent>
            </Select>
            <p className="text-xs text-muted-foreground">
              {strategy === "rolling" && "Updates devices in batches to minimize downtime."}
              {strategy === "canary" && "Updates a small subset first, then proceeds if successful."}
              {strategy === "all-at-once" && "Updates all targeted devices simultaneously."}
            </p>
          </div>
        )}

        {/* Step 2: Target Devices */}
        {step === 2 && (
          <div className="space-y-3">
            <Label>Label Selector (optional)</Label>
            <p className="text-xs text-muted-foreground">
              Leave empty to target all devices, or add label selectors.
            </p>
            <div className="flex flex-wrap gap-2">
              {Object.entries(labels).map(([k, v]) => (
                <Badge key={k} variant="outline" className="gap-1 font-mono text-xs">
                  {k}={v}
                  <button onClick={() => {
                    const next = { ...labels };
                    delete next[k];
                    setLabels(next);
                  }}>
                    <X className="h-3 w-3" />
                  </button>
                </Badge>
              ))}
            </div>
            <div className="flex gap-2">
              <Input
                placeholder="key"
                value={labelKey}
                onChange={(e) => setLabelKey(e.target.value)}
                className="w-32"
              />
              <Input
                placeholder="value"
                value={labelValue}
                onChange={(e) => setLabelValue(e.target.value)}
                className="w-32"
              />
              <Button
                variant="outline"
                size="icon"
                disabled={!labelKey}
                onClick={() => {
                  setLabels({ ...labels, [labelKey]: labelValue });
                  setLabelKey("");
                  setLabelValue("");
                }}
              >
                <Plus className="h-4 w-4" />
              </Button>
            </div>
          </div>
        )}

        {/* Step 3: Review */}
        {step === 3 && (
          <div className="space-y-3 text-sm">
            <div className="flex justify-between">
              <span className="text-muted-foreground">Artifact</span>
              <span className="font-medium">
                {selectedArtifact ? `${selectedArtifact.name} v${selectedArtifact.version}` : `#${artifactId}`}
              </span>
            </div>
            <Separator />
            <div className="flex justify-between">
              <span className="text-muted-foreground">Strategy</span>
              <span className="font-medium">{strategy}</span>
            </div>
            <Separator />
            <div className="flex justify-between">
              <span className="text-muted-foreground">Target</span>
              <span className="font-medium">
                {Object.keys(labels).length === 0
                  ? "All devices"
                  : Object.entries(labels).map(([k, v]) => `${k}=${v}`).join(", ")}
              </span>
            </div>
          </div>
        )}

        <DialogFooter>
          {step > 0 && (
            <Button variant="outline" onClick={() => setStep((s) => s - 1)}>
              Back
            </Button>
          )}
          {step < 3 ? (
            <Button
              onClick={() => setStep((s) => s + 1)}
              disabled={step === 0 && !artifactId}
            >
              Next
            </Button>
          ) : (
            <Button
              onClick={() => mutation.mutate()}
              disabled={mutation.isPending}
            >
              {mutation.isPending ? "Creating..." : "Create Deployment"}
            </Button>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
