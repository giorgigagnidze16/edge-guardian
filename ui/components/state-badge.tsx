import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";

const stateConfig: Record<string, { classes: string; dot: string }> = {
  ONLINE: {
    classes:
      "border-emerald-500/30 bg-emerald-500/10 text-emerald-600 dark:border-emerald-400/30 dark:bg-emerald-400/10 dark:text-emerald-400",
    dot: "bg-emerald-500 dark:bg-emerald-400",
  },
  OFFLINE: {
    classes:
      "border-zinc-400/30 bg-zinc-400/10 text-zinc-500 dark:border-zinc-500/30 dark:bg-zinc-500/10 dark:text-zinc-400",
    dot: "bg-zinc-400 dark:bg-zinc-500",
  },
  DEGRADED: {
    classes:
      "border-amber-500/30 bg-amber-500/10 text-amber-600 dark:border-amber-400/30 dark:bg-amber-400/10 dark:text-amber-400",
    dot: "bg-amber-500 dark:bg-amber-400",
  },
  PENDING: {
    classes:
      "border-blue-500/30 bg-blue-500/10 text-blue-600 dark:border-blue-400/30 dark:bg-blue-400/10 dark:text-blue-400",
    dot: "bg-blue-500 dark:bg-blue-400",
  },
  UPDATING: {
    classes:
      "border-violet-500/30 bg-violet-500/10 text-violet-600 dark:border-violet-400/30 dark:bg-violet-400/10 dark:text-violet-400",
    dot: "bg-violet-500 dark:bg-violet-400",
  },
};

interface StateBadgeProps {
  state: string;
  className?: string;
}

export function StateBadge({ state, className }: StateBadgeProps) {
  const config = stateConfig[state.toUpperCase()] ?? stateConfig.OFFLINE;
  const isOnline = state.toUpperCase() === "ONLINE";

  return (
    <Badge variant="outline" className={cn("gap-1.5", config.classes, className)}>
      <span className="relative flex h-2 w-2">
        {isOnline && (
          <span className={cn("absolute inline-flex h-full w-full animate-ping rounded-full opacity-50", config.dot)} />
        )}
        <span className={cn("relative inline-flex h-2 w-2 rounded-full", config.dot)} />
      </span>
      {state}
    </Badge>
  );
}
