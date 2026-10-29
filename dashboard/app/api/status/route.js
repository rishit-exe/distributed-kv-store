// Server-side proxy to the router's /cluster/status endpoint.
//
// The browser only talks to this Next.js server, never to the router directly.
// That way the router does not need CORS headers, and the router address can be
// changed at runtime with ROUTER_URL (inside Docker it is http://router:8080).

export const dynamic = "force-dynamic"; // never cache, always ask the router

export async function GET() {
  const routerUrl = process.env.ROUTER_URL || "http://localhost:8080";
  try {
    const response = await fetch(`${routerUrl}/cluster/status`, {
      cache: "no-store",
      signal: AbortSignal.timeout(3000),
    });
    if (!response.ok) {
      return Response.json({ error: `Router returned HTTP ${response.status}` }, { status: 502 });
    }
    return Response.json(await response.json());
  } catch {
    return Response.json({ error: `Cannot reach the router at ${routerUrl}` }, { status: 502 });
  }
}
