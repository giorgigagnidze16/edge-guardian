import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";

const stateStyles: Record<string, string> = {
  ONLINE:
    "border-green-200 bg-green-100 text-green-800 dark:border-green-800 dark:bg-green-900 dark:text-green-200",
  OFFLINE:
    "border-gray-200 bg-gray-100 text-gray-800 dark:border-gray-700 dark:bg-gray-800 dark:text-gray-300",
  DEGRADED:
    "border-yellow-200 bg-yellow-100 text-yellow-800 dark:border-yellow-800 dark:bg-yellow-900 dark:text-yellow-200",
  PENDING:
    "border-blue-200 bg-blue-100 text-blue-800 dark:border-blue-800 dark:bg-blue-900 dark:text-blue-200",
  UPDATING:
    "border-purple-200 bg-purple-100 text-purple-800 dark:border-purple-800 dark:bg-purple-900 dark:text-purple-200",
};

interface StateBadgeProps {
  state: string;
  className?: string;
}

export function StateBadge({ state, className }: StateBadgeProps) {
  const style = stateStyles[state.toUpperCase()] ?? stateStyles.OFFLINE;
  return (
    <Badge variant="outline" className={cn(style, className)}>
      {state}
    </Badge>
  );
}
