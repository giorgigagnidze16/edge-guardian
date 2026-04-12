import type { NextConfig } from "next";

const apiUpstream = process.env.INTERNAL_API_URL ?? "http://controller:8443";
const keycloakUpstream = process.env.INTERNAL_KEYCLOAK_URL ?? "http://keycloak:9090";

const nextConfig: NextConfig = {
  output: "standalone",
  async rewrites() {
    return [
      { source: "/api/v1/:path*", destination: `${apiUpstream}/api/v1/:path*` },
      // Keycloak served under /kc so UI + Keycloak share one origin.
      // Requires Keycloak KC_HTTP_RELATIVE_PATH=/kc so its own paths match.
      { source: "/kc/:path*", destination: `${keycloakUpstream}/kc/:path*` },
    ];
  },
  experimental: {
    // Tree-shake barrel exports for heavy libraries
    optimizePackageImports: [
      "lucide-react",
      "recharts",
      "date-fns",
      "cmdk",
      "@radix-ui/react-avatar",
      "@radix-ui/react-checkbox",
      "@radix-ui/react-dialog",
      "@radix-ui/react-dropdown-menu",
      "@radix-ui/react-label",
      "@radix-ui/react-popover",
      "@radix-ui/react-scroll-area",
      "@radix-ui/react-select",
      "@radix-ui/react-separator",
      "@radix-ui/react-slot",
      "@radix-ui/react-switch",
      "@radix-ui/react-tabs",
      "@radix-ui/react-toast",
      "@radix-ui/react-tooltip",
    ],
  },
};

export default nextConfig;