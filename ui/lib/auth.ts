import NextAuth from "next-auth";
import Keycloak from "next-auth/providers/keycloak";

export const { handlers, signIn, signOut, auth } = NextAuth({
  providers: [
    Keycloak({
      clientId: process.env.KEYCLOAK_CLIENT_ID ?? "edgeguardian-ui",
      clientSecret: process.env.KEYCLOAK_CLIENT_SECRET ?? "",
      issuer:
        process.env.KEYCLOAK_ISSUER ??
        "http://localhost:9090/realms/edgeguardian",
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
