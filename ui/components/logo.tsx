import { cn } from "@/lib/utils";

interface LogoIconProps {
  className?: string;
  size?: number;
  outlined?: boolean;
}

/**
 * EdgeGuardian logomark.
 *
 * A geometric shield enclosing a hub-and-spoke network topology —
 * the controller (hub) manages edge devices (nodes). Shield = guardian,
 * network = fleet management.
 */
export function LogoIcon({ className, size = 32, outlined = false }: LogoIconProps) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 48 48"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      className={cn("shrink-0 text-primary", className)}
    >
      {/* Shield */}
      <path
        d="M24 4 L40 12 V28 L24 44 L8 28 V12 Z"
        stroke="currentColor"
        strokeWidth={outlined ? "2" : "2.5"}
        strokeLinejoin="round"
        fill="none"
      />

      {/* Spokes — hub to device nodes */}
      <path
        d="M24 20 L15 32 M24 20 L33 32 M24 20 V10"
        stroke="currentColor"
        strokeWidth={outlined ? "1.8" : "1.5"}
        strokeLinecap="round"
      />

      {/* Hub (controller) */}
      <circle
        cx="24" cy="20" r={outlined ? "4" : "3.5"}
        fill={outlined ? "none" : "currentColor"}
        stroke={outlined ? "currentColor" : "none"}
        strokeWidth={outlined ? "2" : undefined}
      />

      {/* Device nodes */}
      {outlined ? (
        <>
          <circle cx="24" cy="10" r="2.5" stroke="currentColor" strokeWidth="1.8" />
          <circle cx="15" cy="32" r="2.5" stroke="currentColor" strokeWidth="1.8" />
          <circle cx="33" cy="32" r="2.5" stroke="currentColor" strokeWidth="1.8" />
        </>
      ) : (
        <>
          <circle cx="24" cy="10" r="2" fill="currentColor" />
          <circle cx="15" cy="32" r="2" fill="currentColor" />
          <circle cx="33" cy="32" r="2" fill="currentColor" />
        </>
      )}
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
