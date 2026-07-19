// calendar.etzhayyim.com thin edge facade.
//
// Event CRUD, RSVP, recurrence expansion, Google OAuth token exchange, Google
// Calendar sync, and graph writes run in BPMN + Python LangServer. This Worker only
// serves static UI assets and forwards XRPC/OAuth callback traffic.

interface SecretBinding {
  get(): Promise<string>;
}

interface Env {
  ASSETS?: { fetch(req: Request): Promise<Response> };
  DISPATCHER_URL?: string;
  DISPATCHER_INTERNAL_SECRET?: string | SecretBinding;
  APP_NANOID?: string;
}

const NSID_PREFIX = "com.etzhayyim.apps.calendar.";

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    const url = new URL(req.url);

    if (url.pathname === "/health" || url.pathname === "/_app/meta") {
      return json({
        ok: true,
        actor: "did:web:calendar.etzhayyim.com",
        nanoid: env.APP_NANOID ?? "calendar-mcp",
        execution: "edge-proxy+agentgateway-mcp+langserver",
        businessLogic: "40-engine/kotoba/crates/kotoba-kotodama/py/src/kotodama/ingest/calendar.py",
        bpmn: "etzhayyim-root/00-contracts/bpmn/com/etzhayyim/calendar",
      });
    }

    if (url.pathname === "/oauth/callback") {
      const result = await proxyToDispatcher(env, `${NSID_PREFIX}oauthCallback`, Object.fromEntries(url.searchParams));
      const text = await result.text();
      let html = text;
      try {
        const parsed = JSON.parse(text) as { html?: string };
        html = parsed.html ?? text;
      } catch {
        // Keep dispatcher response text as-is.
      }
      return new Response(html, {
        status: result.status,
        headers: { "content-type": "text/html; charset=utf-8", "cache-control": "no-store" },
      });
    }

    const nsid = url.pathname.startsWith("/xrpc/") ? url.pathname.slice("/xrpc/".length) : "";
    if (nsid.startsWith(NSID_PREFIX) && (req.method === "POST" || req.method === "GET")) {
      let body: Record<string, unknown> = {};
      if (req.method === "POST") {
        try {
          const text = await req.text();
          body = text ? JSON.parse(text) : {};
        } catch (e) {
          return json({ ok: false, error: `invalid JSON body: ${e instanceof Error ? e.message : String(e)}` }, 400);
        }
      }
      for (const [key, value] of url.searchParams) {
        if (!(key in body)) body[key] = value;
      }
      return proxyToDispatcher(env, nsid, body);
    }

    if (env.ASSETS) return env.ASSETS.fetch(req);
    return json({ error: "NotFound", message: "not found" }, 404);
  },

  async scheduled(_event: ScheduledEvent, env: Env): Promise<void> {
    await proxyToDispatcher(env, `${NSID_PREFIX}cronTick`, {});
  },
};

async function proxyToDispatcher(env: Env, nsid: string, body: Record<string, unknown>): Promise<Response> {
  const base = (env.DISPATCHER_URL ?? "https://dispatcher.etzhayyim.com").replace(/\/+$/, "");
  const headers: Record<string, string> = { "content-type": "application/json" };
  const trust = await internalTrustSecret(env);
  if (trust) headers["x-internal-trust"] = trust;
  const resp = await fetch(`${base}/xrpc/${nsid}`, { method: "POST", headers, body: JSON.stringify(body) });
  const text = await resp.text();
  return new Response(text, {
    status: resp.status,
    headers: {
      "content-type": resp.headers.get("content-type") ?? "application/json",
      "cache-control": "no-store",
    },
  });
}

async function internalTrustSecret(env: Env): Promise<string> {
  const binding = env.DISPATCHER_INTERNAL_SECRET;
  if (!binding) return "";
  try {
    return typeof binding === "string" ? binding : await binding.get();
  } catch {
    return "";
  }
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json", "cache-control": "no-store" },
  });
}
