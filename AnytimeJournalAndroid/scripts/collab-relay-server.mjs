import http from "node:http";

const PORT = 49374;
const MAX_ENTRIES = 1000;
const entries = [];
const seen = new Set();
const presence = new Map();

function keyOf(entry) {
  return `${entry.sourceId}|${entry.createdAtMillis}|${entry.text}`;
}

function sendJson(res, status, value) {
  res.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "access-control-allow-origin": "*",
  });
  res.end(JSON.stringify(value));
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url ?? "/", `http://${req.headers.host}`);
  if (req.method === "GET" && url.pathname === "/health") {
    sendJson(res, 200, { ok: true, count: entries.length, online: presence.size });
    return;
  }
  if (req.method === "GET" && url.pathname === "/entries") {
    const since = Number(url.searchParams.get("since") ?? "0");
    sendJson(
      res,
      200,
      entries.filter((entry) => Number(entry.createdAtMillis) > since),
    );
    return;
  }
  if (req.method === "GET" && url.pathname === "/presence") {
    const now = Date.now();
    sendJson(
      res,
      200,
      [...presence.values()].filter((item) => now - Number(item.lastSeenMillis) <= 10000),
    );
    return;
  }
  if (req.method === "POST" && url.pathname === "/presence") {
    let body = "";
    req.setEncoding("utf8");
    req.on("data", (chunk) => {
      body += chunk;
      if (body.length > 4096) req.destroy();
    });
    req.on("end", () => {
      try {
        const item = JSON.parse(body);
        if (item.app !== "anytime-journal-collab-v1") throw new Error("bad app");
        if (!item.sourceId || !item.profile || !item.lastSeenMillis) throw new Error("bad presence");
        presence.set(item.sourceId, item);
        sendJson(res, 200, { ok: true, online: presence.size });
      } catch (error) {
        sendJson(res, 400, { ok: false, error: String(error?.message ?? error) });
      }
    });
    return;
  }
  if (req.method === "POST" && url.pathname === "/entries") {
    let body = "";
    req.setEncoding("utf8");
    req.on("data", (chunk) => {
      body += chunk;
      if (body.length > 8192) req.destroy();
    });
    req.on("end", () => {
      try {
        const entry = JSON.parse(body);
        if (entry.app !== "anytime-journal-collab-v1") throw new Error("bad app");
        if (entry.kind !== "collab") throw new Error("bad kind");
        if (!entry.sourceId || !entry.text || !entry.createdAtMillis) throw new Error("bad entry");
        const key = keyOf(entry);
        if (!seen.has(key)) {
          seen.add(key);
          entries.push(entry);
          while (entries.length > MAX_ENTRIES) {
            const removed = entries.shift();
            if (removed) seen.delete(keyOf(removed));
          }
          console.log(`[relay] ${entry.sourceId}: ${entry.text}`);
        }
        sendJson(res, 200, { ok: true, count: entries.length });
      } catch (error) {
        sendJson(res, 400, { ok: false, error: String(error?.message ?? error) });
      }
    });
    return;
  }
  sendJson(res, 404, { ok: false });
});

server.listen(PORT, "0.0.0.0", () => {
  console.log(`[relay] listening on http://0.0.0.0:${PORT}`);
});
