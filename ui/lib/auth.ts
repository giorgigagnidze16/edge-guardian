import NextAuth from "next-auth";
import Keycloak from "next-auth/providers/keycloak";

const issuer =
  process.env.KEYCLOAK_ISSUER ?? "http://localhost:3000/kc/realms/edgeguardian";

export const { handlers, auth } = NextAuth({
  debug: true,
  logger: {
    error(code, ...rest) {
      console.error("[auth-dbg] error", code);
      console.error("[auth-dbg] cause:", (code as any)?.cause?.message || (code as any)?.cause);
      console.error("[auth-dbg] cause-stack:", (code as any)?.cause?.stack);
    },
    warn(code) {
      console.warn("[auth-dbg] warn", code);
    },
    debug(code, metadata) {
      console.log("[auth-dbg]", code, JSON.stringify(metadata));
    },
  },
  providers: [
    Keycloak({
      clientId: process.env.KEYCLOAK_CLIENT_ID ?? "edgeguardian-ui",
      clientSecret: process.env.KEYCLOAK_CLIENT_SECRET ?? "",
      issuer,
    }),
  ],
  callbacks: {
    authorized({ auth }) {
      return !!auth;
    },
    async jwt({ token, account }) {
      if (account) {
        token.accessToken = account.access_token;
        token.refreshToken = account.refresh_token;
        token.expiresAt = account.expires_at;
      }

      if (typeof token.expiresAt === "number" && Date.now() < token.expiresAt * 1000) {
        return token;
      }

      if (!token.refreshToken) {
        token.error = "RefreshTokenError";
        return token;
      }

      try {
        const response = await fetch(`${issuer}/protocol/openid-connect/token`, {
          method: "POST",
          headers: { "Content-Type": "application/x-www-form-urlencoded" },
          body: new URLSearchParams({
            grant_type: "refresh_token",
            client_id: process.env.KEYCLOAK_CLIENT_ID ?? "edgeguardian-ui",
            client_secret: process.env.KEYCLOAK_CLIENT_SECRET ?? "",
            refresh_token: token.refreshToken as string,
          }),
        });

        if (response.ok) {
          const refreshed = await response.json();
          token.accessToken = refreshed.access_token;
          token.refreshToken = refreshed.refresh_token ?? token.refreshToken;
          token.expiresAt = Math.floor(Date.now() / 1000) + refreshed.expires_in;
          delete token.error;
          return token;
        }

        if (response.status >= 400 && response.status < 500) {
          const body = await response.json().catch(() => ({}));
          if (body.error === "invalid_grant") {
            token.error = "RefreshTokenError";
          }
        }
        return token;
      } catch {
        return token;
      }
    },
    async session({ session, token }) {
      session.accessToken = token.accessToken as string;
      if (token.error) {
        (session as { error?: string }).error = token.error as string;
      }
      return session;
    },
  },
  pages: {
    signIn: "/auth/login",
  },
});
