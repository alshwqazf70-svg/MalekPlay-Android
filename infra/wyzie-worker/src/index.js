const UPSTREAM = "https://sub.wyzie.io/search";
const ALLOWED = new Set([
  "id", "season", "episode", "language", "format", "limit", "page",
  "sort", "video", "filename", "file", "origin", "machine", "refresh", "hi"
]);

function json(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" }
  });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method !== "GET") return json({ message: "Method not allowed" }, 405);
    if (url.pathname === "/health") return json({ ok: true, service: "malekplay-wyzie-proxy" });
    if (url.pathname !== "/search") return json({ message: "Not found" }, 404);
    if (!env.WYZIE_API_KEY) return json({ message: "Wyzie server key is not configured" }, 503);
    if (!env.RATE_LIMITER) return json({ message: "Rate limiter is not configured" }, 503);

    const clientIp = request.headers.get("CF-Connecting-IP") || "unknown";
    const rate = await env.RATE_LIMITER.limit({ key: clientIp });
    if (!rate.success) return json({ message: "Too many subtitle searches; retry later" }, 429);

    const incoming = url.searchParams;
    for (const key of incoming.keys()) {
      if (!ALLOWED.has(key)) return json({ message: "Unsupported search parameter" }, 400);
    }
    const id = incoming.get("id") || "";
    if (!/^tt\d{5,12}$/.test(id) && !/^\d{1,12}$/.test(id)) {
      return json({ message: "A valid IMDb or TMDB id is required" }, 400);
    }
    const season = incoming.get("season");
    const episode = incoming.get("episode");
    if ((season === null) !== (episode === null)) {
      return json({ message: "Season and episode must be supplied together" }, 400);
    }
    if (season !== null &&
      (!/^\d{1,2}$/.test(season) || !/^\d{1,3}$/.test(episode))) {
      return json({ message: "Invalid season or episode" }, 400);
    }
    const languages = incoming.get("language") || "ar,en";
    if (!/^(ar|en)(,(ar|en))?$/.test(languages)) {
      return json({ message: "Only Arabic and English are enabled" }, 400);
    }

    const upstream = new URL(UPSTREAM);
    for (const [key, value] of incoming) upstream.searchParams.set(key, value);
    upstream.searchParams.set("key", env.WYZIE_API_KEY);

    try {
      const response = await fetch(upstream.toString(), {
        headers: { "accept": "application/json", "user-agent": "MalekPlayer-Wyzie-Proxy/1.0" },
        signal: AbortSignal.timeout(20000)
      });
      const body = await response.text();
      return new Response(body, {
        status: response.status,
        headers: {
          "content-type": response.headers.get("content-type") || "application/json; charset=utf-8",
          "cache-control": "no-store",
          "x-content-type-options": "nosniff"
        }
      });
    } catch {
      return json({ message: "Wyzie upstream is temporarily unavailable" }, 502);
    }
  }
};
