import { auth } from "@/lib/auth";
import { NextResponse } from "next/server";

export default auth((req) => {
  // In development with SKIP_AUTH, bypass Keycloak entirely
  if (process.env.SKIP_AUTH === "true") {
    return NextResponse.next();
  }

  // Otherwise require a valid session
  if (!req.auth) {
    const loginUrl = new URL("/auth/login", req.url);
    loginUrl.searchParams.set("callbackUrl", req.url);
    return NextResponse.redirect(loginUrl);
  }

  return NextResponse.next();
});

export const config = {
  // Only protect app routes — landing page (/), auth, api, and static files are public
  matcher: ["/(dashboard|devices|ota|integrations|audit|settings)(.*)"],
};
