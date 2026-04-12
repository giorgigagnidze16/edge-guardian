import NextAuth from "next-auth";
import Keycloak from "next-auth/providers/keycloak";

const externalIssuer =
  process.env.KEYCLOAK_ISSUER ?? "http://localhost:9090/realms/edgeguardian";
const internalIssuer =
  process.env.KEYCLOAK_INTERNAL_URL ?? externalIssuer;

export const { handlers, auth } = NextAuth({
  providers: [
    Keycloak({
      clientId: process.env.KEYCLOAK_CLIENT_ID ?? "edgeguardian-ui",
      clientSecret: process.env.KEYCLOAK_CLIENT_SECRET ?? "",
      issuer: externalIssuer,
      wellKnown: `${internalIssuer}/.well-known/openid-configuration`,
      authorization: `${externalIssuer}/protocol/openid-connect/auth`,
      token: `${internalIssuer}/protocol/openid-connect/token`,
      userinfo: `${internalIssuer}/protocol/openid-connect/userinfo`,
      jwks_endpoint: `${internalIssuer}/protocol/openid-connect/certs`,
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

      // Return early if the access token has not expired
      if (typeof token.expiresAt === "number" && Date.now() < token.expiresAt * 1000) {
        return token;
      }

      // Access token expired — try to refresh
      if (!token.refreshToken) return token;
      try {
        const response = await fetch(`${internalIssuer}/protocol/openid-connect/token`, {
          method: "POST",
          headers: { "Content-Type": "application/x-www-form-urlencoded" },
          body: new URLSearchParams({
            grant_type: "refresh_token",
            client_id: process.env.KEYCLOAK_CLIENT_ID ?? "edgeguardian-ui",
            client_secret: process.env.KEYCLOAK_CLIENT_SECRET ?? "",
            refresh_token: token.refreshToken as string,
          }),
        });

        const refreshed = await response.json();
        if (!response.ok) throw refreshed;

        token.accessToken = refreshed.access_token;
        token.refreshToken = refreshed.refresh_token ?? token.refreshToken;
        token.expiresAt = Math.floor(Date.now() / 1000) + refreshed.expires_in;
      } catch {
        // Refresh failed — force re-login
        token.error = "RefreshTokenError";
      }

      return token;
    },
    async session({ session, token }) {
      session.accessToken = token.accessToken as string;
      return session;
    },
  },
  pages: {
    signIn: "/auth/login",
  },
});
