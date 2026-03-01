import { cn } from "@/lib/utils";

interface LogoIconProps {
  className?: string;
  size?: number;
}

/**
 * EdgeGuardian "Sentinel Node" logomark.
 *
 * An octagonal frame with an open corner (the "edge") and
 * three concentric beacon arcs radiating outward — guarding
 * meets broadcasting. The negative space between arcs implies
 * a shield without drawing one.
 */
export function LogoIcon({ className, size = 32 }: LogoIconProps) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 48 48"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      className={cn("shrink-0", className)}
    >
      {/* Octagonal frame — open at top-right corner */}
      <path
        d="M18 4h12l10 10v12l-10 10H18L8 26V14L18 4Z"
        stroke="currentColor"
        strokeWidth="2.5"
        strokeLinecap="round"
        strokeLinejoin="round"
        fill="none"
        strokeDasharray="0"
        className="text-primary"
      />

      {/* The "open edge" — gap in top-right corner with glow hint */}
      <path
        d="M30 4l10 10"
        stroke="var(--color-background, #06060a)"
        strokeWidth="4"
        strokeLinecap="round"
      />

      {/* Beacon arcs — radiating from bottom-left vertex */}
      <path
        d="M18.5 29.5a8 8 0 0 1 0-11.3"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        fill="none"
        className="text-primary opacity-90"
      />
      <path
        d="M14.5 33.5a14 14 0 0 1 0-19.8"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        fill="none"
        className="text-primary opacity-60"
      />
      <path
        d="M10.5 37.5a20 20 0 0 1 0-28.3"
        stroke="currentColor"
        strokeWidth="1.5"
        strokeLinecap="round"
        fill="none"
        className="text-primary opacity-30"
      />

      {/* Central node dot */}
      <circle
        cx="23"
        cy="24"
        r="3"
        fill="currentColor"
        className="text-primary"
      />

      {/* Small accent dot — the "edge device" */}
      <circle
        cx="35"
        cy="24"
        r="1.5"
        fill="currentColor"
        className="text-primary opacity-50"
      />
    </svg>
  );
}

interface LogoFullProps {
  className?: string;
  size?: "sm" | "md" | "lg";
  collapsed?: boolean;
}

const sizes = {
  sm: { icon: 24, text: "text-base" },
  md: { icon: 32, text: "text-lg" },
  lg: { icon: 40, text: "text-2xl" },
};

/**
 * Full logo: icon + "EdgeGuardian" wordmark.
 */
export function LogoFull({ className, size = "md", collapsed = false }: LogoFullProps) {
  const s = sizes[size];

  return (
    <div className={cn("flex items-center gap-2.5", className)}>
      <div className="relative flex items-center justify-center">
        {/* Ambient glow behind icon */}
        <div
          className="absolute inset-0 rounded-xl bg-primary/10 blur-lg"
          aria-hidden
        />
        <LogoIcon size={s.icon} />
      </div>

      {!collapsed && (
        <span className={cn(s.text, "font-bold tracking-tight text-foreground")}>
          Edge
          <span className="text-primary">Guardian</span>
        </span>
      )}
    </div>
  );
}