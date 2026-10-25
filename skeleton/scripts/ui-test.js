// Drive the skeleton's real htmx UI against the live daemon: preview on input, banner on a live SSE event.
const { JSDOM, VirtualConsole } = require("jsdom");
const B = "http://localhost:7228";
const log = [];
const vc = new VirtualConsole(); vc.on("jsdomError", e => console.log("jsdomError", e.message)); vc.on("error", e => console.log("console.error", e));
class FetchEventSource {  // jsdom has no EventSource; this one reads the real stream with fetch
  constructor(url) { console.log("EventSource", url); this.listeners = {}; this.ctl = new AbortController();
    fetch(B + url, { signal: this.ctl.signal }).then(async r => {
      const dec = new TextDecoder(); let buf = "";
      for await (const chunk of r.body) { buf += dec.decode(chunk);
        let i; while ((i = buf.indexOf("\n\n")) >= 0) { const block = buf.slice(0, i); buf = buf.slice(i + 2);
          const ev = /^event: (.*)$/m.exec(block), data = /^data: (.*)$/m.exec(block);
          if (ev && data) console.log("sse event", ev[1], data[1]); (this.listeners[ev[1]] || []).forEach(f => f({ data: data[1] })); } } }).catch((e) => console.log("sse err", e)); }
  addEventListener(t, f) { (this.listeners[t] ||= []).push(f); } close() { this.ctl.abort(); } }
(async () => {
  const html = await (await fetch(B + "/CLO-2/edit")).text();
  const dom = new JSDOM(html, { url: B + "/CLO-2/edit", runScripts: "dangerously", resources: "usable", virtualConsole: vc,
    beforeParse(w) { const ev = w.XPathExpression.prototype.evaluate; w.XPathExpression.prototype.evaluate = function (n, t, r) { return ev.call(this, n, t ?? 0, r ?? null); }; w.document.adoptedStyleSheets = []; if (w.CSSStyleSheet && !w.CSSStyleSheet.prototype.replaceSync) w.CSSStyleSheet.prototype.replaceSync = function () {}; w.EventSource = FetchEventSource; for (const k of ["Headers", "Request", "Response", "FormData"]) if (!w[k]) w[k] = globalThis[k];
      w.fetch = async (u, o = {}) => { log.push((o.method || "GET") + " " + u); const { signal, body, headers, ...rest } = o; const h = headers ? Object.fromEntries(headers.entries ? headers.entries() : Object.entries(headers)) : {}; const b = body && body.entries ? new URLSearchParams([...body.entries()]) : body; const r = await fetch(new URL(u, B), { ...rest, headers: h, body: b }); return new w.Response(await r.text(), { status: r.status, headers: Object.fromEntries(r.headers) }); }; } });
  const w = dom.window, d = w.document;
  await new Promise(r => w.addEventListener("load", r)); await new Promise(r => setTimeout(r, 500));
  console.log("htmx loaded:", typeof w.htmx); w.document.body.addEventListener("cloisters:change", e => console.log("body got trigger", JSON.stringify(e.detail)));
  const ta = d.querySelector("textarea"); ta.value = "Preview of CLO-1 here"; ta.dispatchEvent(new w.Event("input", { bubbles: true }));
  await new Promise(r => setTimeout(r, 1200));
  console.log("preview:", d.querySelector("#preview").innerHTML);
  console.log("banner before:", JSON.stringify(d.querySelector("#banner").textContent));
  const v = (await (await fetch(B + "/api/pages/CLO-2")).json()).version;
  await fetch(B + "/api/pages/CLO-2", { method: "PATCH", headers: { "Content-Type": "application/json", "Cloisters-Actor": "jsdom-test" },
    body: JSON.stringify({ expected_version: v, body: "# Glossary\n\nTracked by [the ticket](/CLO-1). Changed underneath the editor at " + Date.now() + ".", summary: "underneath" }) });
  await new Promise(r => setTimeout(r, 1500));
  console.log("banner after:", JSON.stringify(d.querySelector("#banner").textContent));
  console.log("requests:", log.join(" | "));
  w.close(); process.exit(0);
})();
