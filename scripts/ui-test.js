// The jsdom UI check: drives the daemon's real web UI (server-rendered pages, vendored htmx 4, assets/live.js)
// against a live daemon whose store is empty. Seeds through the HTTP API, then checks every view, the safe renderer,
// inline edits, live updates over SSE, and the editor's stale-save guard with a hand merge.
// Usage: node scripts/ui-test.js http://127.0.0.1:<port>
"use strict";
const { JSDOM, VirtualConsole } = require("jsdom");

const B = process.argv[2];
if (!B) { console.error("usage: node scripts/ui-test.js <base url>"); process.exit(2); }

let failures = 0;
const check = (ok, what) => {
  console.log((ok ? "ok   " : "FAIL ") + what);
  if (!ok) failures++;
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function until(f, what, ms = 5000) {
  const end = Date.now() + ms;
  while (Date.now() < end) { if (await f()) return check(true, what); await sleep(50); }
  check(false, what);
}

// ---------------------------------------------------------------- the API, for seeding and writes underneath

async function api(method, path, body, project = "CLO") {
  const headers = { "Content-Type": "application/json", "Cloisters-Client": "ui-test" };
  if (project) headers["Cloisters-Project"] = project;
  const r = await fetch(B + path, { method, headers, body: body && JSON.stringify(body) });
  const json = await r.json();
  if (r.status >= 300) throw new Error(`${method} ${path}: ${r.status} ${JSON.stringify(json)}`);
  return json;
}

// ---------------------------------------------------------------- pages, static and live

const parse = (html, url) => new JSDOM(html, { url }).window.document;

async function get(path) {
  const r = await fetch(B + path, { redirect: "manual" });
  return { status: r.status, location: r.headers.get("location"), html: await r.text() };
}

async function doc(path) {
  const { status, html } = await get(path);
  return { status, d: parse(html, B + path) };
}

// jsdom has no EventSource: this one reads the real stream with fetch.
function eventSourceFor(w) {
  return class {
    constructor(url) {
      this.listeners = {};
      this.ctl = new AbortController();
      fetch(new URL(url, B), { signal: this.ctl.signal }).then(async (r) => {
        const dec = new TextDecoder();
        let buf = "";
        for await (const chunk of r.body) {
          buf += dec.decode(chunk, { stream: true });
          let i;
          while ((i = buf.indexOf("\n\n")) >= 0) {
            const block = buf.slice(0, i);
            buf = buf.slice(i + 2);
            const data = /^data: (.*)$/m.exec(block);
            if (!data) continue;
            const type = (/^event: (.*)$/m.exec(block) || [])[1] || "message";
            const ev = new w.MessageEvent(type, { data: data[1] });
            if (type === "message" && this.onmessage) this.onmessage(ev);
            (this.listeners[type] || []).forEach((f) => f(ev));
          }
        }
      }).catch(() => {});
    }
    addEventListener(t, f) { (this.listeners[t] ||= []).push(f); }
    close() { this.ctl.abort(); }
  };
}

let inflight = 0;

// Waits until htmx has had no request in flight for a while: the item a write refreshes, then the live refresh its
// own event triggers, so the next step never holds an element that is about to be swapped out.
async function settle(quiet = 400) {
  let since = Date.now();
  while (Date.now() - since < quiet) {
    if (inflight > 0) since = Date.now();
    await sleep(25);
  }
}

// A page with its scripts running: htmx and live.js, with the shims htmx 4 needs under jsdom.
async function live(path) {
  const { html } = await get(path);
  const vc = new VirtualConsole();
  vc.on("jsdomError", (e) => console.log("  jsdomError", e.message));
  const dom = new JSDOM(html, {
    url: B + path, runScripts: "dangerously", resources: "usable", virtualConsole: vc,
    beforeParse(w) {
      const evaluate = w.XPathExpression.prototype.evaluate;
      w.XPathExpression.prototype.evaluate = function (n, t, r) { return evaluate.call(this, n, t ?? 0, r ?? null); };
      w.document.adoptedStyleSheets = [];
      if (w.CSSStyleSheet && !w.CSSStyleSheet.prototype.replaceSync) w.CSSStyleSheet.prototype.replaceSync = function () {};
      w.EventSource = eventSourceFor(w);
      for (const k of ["Headers", "Request", "Response", "FormData"]) if (!w[k]) w[k] = globalThis[k];
      // htmx's fetch, bridged to Node's: bodies and headers cross realms as plain values
      w.fetch = async (u, o = {}) => {
        const { signal, body, headers, ...rest } = o;
        const h = headers ? Object.fromEntries(headers.entries ? headers.entries() : Object.entries(headers)) : {};
        const b = body && body.entries ? new URLSearchParams([...body.entries()]) : body;
        inflight++;
        try {
          const r = await fetch(new URL(u, B), { ...rest, headers: h, body: b, redirect: "manual" });
          return new w.Response(await r.text(), { status: r.status, headers: Object.fromEntries(r.headers) });
        } finally {
          inflight--;
        }
      };
    },
  });
  const w = dom.window;
  await new Promise((r) => w.addEventListener("load", r));
  await sleep(300); // the stream opens
  return w;
}

const text = (el) => (el ? el.textContent.replace(/\s+/g, " ").trim() : "");

// ---------------------------------------------------------------- the checks

(async () => {
  console.log("=== seed");
  await api("POST", "/api/projects", { key: "CLO", name: "Cloisters" }, null);
  await api("POST", "/api/issues", {
    title: "Map <b>bold</b>", labels: ["wayfinder:map"],
    body: "See CLO-5 and CLO-99, `CLO-2` in code, and CLO-7.\n\n<script>window.pwned = 1</script>\n\n" +
      "[bad](javascript:window.pwned=1) [ok](/CLO-5) ![img](data:image/png;base64,AAAA)",
  });
  await api("POST", "/api/issues", { title: "Blocker", parent: "CLO-1" });
  await api("POST", "/api/issues", { title: "Root blocker", parent: "CLO-1" });
  await api("POST", "/api/issues", { title: "Blocked", parent: "CLO-1", blocked_by: ["CLO-2"] });
  await api("PATCH", "/api/issues/CLO-2", { blocked_by_add: ["CLO-3"] });
  await api("POST", "/api/pages", { title: "Context", body: "# Context\n\nTracked by CLO-1.\n\nline two" });
  await api("POST", "/api/pages", { title: "Child", parent: "CLO-5" });
  await api("POST", "/api/pages", { title: "Old", parent: "CLO-5" });
  await api("POST", "/api/pages/CLO-7/archive", { summary: "superseded" });

  console.log("=== search");
  {
    const r = await get("/");
    check(r.status === 303 && r.location === "/?q=ready", "/ goes to ready on a first visit");
    const { d } = await doc("/?q=ready");
    const ids = [...d.querySelectorAll("table.rows td.id")].map(text);
    check(JSON.stringify(ids) === JSON.stringify(["CLO-1", "CLO-3"]), `ready lists CLO-1 and CLO-3 (${ids})`);
    check(text(d.querySelector("p.query")).includes("ready"), "the effective query shows");
    const bad = await doc("/?q=bogus:1");
    check(bad.status === 400 && text(bad.d.querySelector(".notice")).includes("invalid_query"), "a bad query is 400, named");
    const pages = await doc("/?q=" + encodeURIComponent("kind:page sort:updated-desc"));
    const states = [...pages.d.querySelectorAll("td.state")].map(text);
    check(states.length === 2 && states.every((s) => s.startsWith("page")), `recent changes lists live pages (${states})`);
  }

  console.log("=== projects and trees");
  {
    const { d } = await doc("/projects");
    const cells = [...d.querySelectorAll("table.projects tbody td")].map(text);
    check(cells[1] === "4" && cells[2] === "2" && cells[3] === "2", `projects counts open, ready and pages (${cells})`);
    const tree = (await doc("/tree/CLO")).d;
    check(text(tree.querySelector("ul.tree > li details summary")).startsWith("CLO-1"), "the issue tree's root is CLO-1");
    check(tree.querySelectorAll("ul.tree ul > li").length === 3, "with its three children nested");
    check(tree.querySelector("ul.tree .state-blocked") !== null, "blocked rows are marked");
    const pt = (await doc("/tree/CLO/pages")).d;
    const ptIds = [...pt.querySelectorAll("ul.tree a.ref")].map(text);
    check(JSON.stringify(ptIds) === JSON.stringify(["CLO-5", "CLO-6"]), `the page tree hides archived pages (${ptIds})`);
    const pta = (await doc("/tree/CLO/pages?archived=yes")).d;
    check([...pta.querySelectorAll("ul.tree a.ref")].map((a) => a.getAttribute("href")).includes("/CLO-7"),
      "and shows them on request");
    const sub = (await doc("/tree/CLO-5")).d;
    check(text(sub.querySelector("h1")).includes("CLO-5"), "a subtree starts at its item");
  }

  console.log("=== the safe renderer, on the issue view");
  {
    const w = await live("/CLO-1");
    const d = w.document;
    const prose = d.querySelector("#view .prose");
    const ctx = prose.querySelector('a[href="/CLO-5"]');
    check(ctx && ctx.title === "Context", "a mention links to its item, titled on hover");
    check(prose.querySelector("span.missing")?.textContent === "CLO-99", "an id not yet created is muted, not linked");
    check(prose.querySelector("code").textContent === "CLO-2" && !prose.querySelector("code a"), "code is never linked");
    check(prose.querySelector('a[href="/CLO-7"]')?.classList.contains("archived"), "a link to an archived page is muted");
    check(text(prose.querySelector('a[href="/CLO-7"]')).includes("archived"), "and marked archived");
    check(!prose.querySelector("script") && prose.textContent.includes("<script>"), "raw HTML shows as text");
    check(![...d.querySelectorAll("a")].some((a) => /^\s*javascript:/i.test(a.getAttribute("href"))), "no javascript: link");
    check(!prose.querySelector("img"), "a data: image is dropped");
    check(w.pwned === undefined, "nothing ran");
    check(text(d.querySelector("h1")).includes("Map <b>bold</b>"), "titles are escaped");
    check(d.querySelectorAll("#view section h2").length >= 3, "children, mentions and history show");

    console.log("=== inline edits");
    const form = (action) => d.querySelector(`#actions form[action="/CLO-1/${action}"]`);
    await settle();
    form("comment").querySelector("textarea").value = "Looks right to CLO-5.";
    form("comment").requestSubmit();
    await until(() => text(d.querySelector("ol.comments")).includes("Looks right to CLO-5."), "a comment posts and shows");
    check(d.querySelector('ol.comments a[href="/CLO-5"]') !== null, "the comment renders its mention");
    await settle();
    const add = d.querySelector('#actions input[name="add"]');
    add.value = "ux";
    add.form.requestSubmit();
    await until(() => [...d.querySelectorAll("#view .label")].map(text).includes("ux"), "a label is added");
    await settle();
    const reassign = form("reassign");
    reassign.querySelector('input[name="from"]').value = "someone-else";
    reassign.querySelector('input[name="to"]').value = "saqib";
    reassign.requestSubmit();
    await until(() => text(d.querySelector("#item .notice")).includes("not_holder"), "a refusal shows in the item, named");
    await settle();
    form("reassign").querySelector('input[name="to"]').value = "saqib";
    form("reassign").requestSubmit();
    await until(() => text(d.querySelector("#view .assignee")) === "saqib", "reassigning to a person");
    await settle();
    const feed = await (await fetch(B + "/api/events?after=0&limit=200")).json();
    check(feed.events.filter((e) => e.actor === "web").length === 3, "UI writes record web");

    console.log("=== live updates");
    const v = (await api("GET", "/api/issues/CLO-1")).version;
    await api("PATCH", "/api/issues/CLO-1", { expected_version: v, body: "Rewritten underneath, see CLO-6." });
    await until(() => text(d.querySelector("#view .prose")).includes("Rewritten underneath"), "the issue view refreshes in place");
    check(d.querySelector('#view .prose a[href="/CLO-6"]') !== null, "with mentions linked");
    await settle();
    form("comment").querySelector("textarea").value = "half-typed";
    await api("PATCH", "/api/issues/CLO-1", { labels_add: ["live"] });
    await until(() => [...d.querySelectorAll("#view .label")].map(text).includes("live"), "a related write refreshes the view");
    check(form("comment").querySelector("textarea").value === "half-typed", "but never the form being typed in");
    w.close();
  }

  console.log("=== blockers expand in place");
  {
    const w = await live("/CLO-4");
    const d = w.document;
    const det = d.querySelector("#view details");
    check(text(det.querySelector("summary")).startsWith("CLO-2"), "CLO-4 is blocked by CLO-2");
    det.open = true;
    await until(() => text(det.querySelector(".sub")).includes("CLO-3"), "which expands to its own blocker, CLO-3");
    w.close();
  }

  console.log("=== lists never reshuffle");
  {
    const w = await live("/?q=ready");
    const d = w.document;
    const before = [...d.querySelectorAll("td.id")].map(text).join();
    await api("POST", "/api/issues/CLO-3/claim", { assignee: "agent/1" });
    await until(() => !d.querySelector("p.updates").hidden && text(d.querySelector("p.updates")).startsWith("1 updates"),
      "a list counts updates in its bar");
    check([...d.querySelectorAll("td.id")].map(text).join() === before, "and keeps its rows");
    w.close();
  }

  console.log("=== the page view");
  {
    const w = await live("/CLO-5");
    const d = w.document;
    check([...d.querySelectorAll("#view section")].some((s) => text(s).startsWith("Children") && text(s).includes("CLO-6")),
      "children show");
    check([...d.querySelectorAll("#view section")].some((s) => text(s).startsWith("Backlinks") && text(s).includes("CLO-1")),
      "backlinks from issues show");
    const p = await api("GET", "/api/pages/CLO-5");
    await api("PATCH", "/api/pages/CLO-5", { expected_version: p.version, body: p.body + "\n\nAppended by an agent." });
    await until(() => text(d.querySelector("#view .prose")).includes("Appended by an agent."), "a page being read refreshes in place");
    w.close();

    const c = await live("/CLO-6");
    await settle();
    const archive = c.document.querySelector('#actions form[action="/CLO-6/archive"]');
    archive.querySelector('input[name="summary"]').value = "done with it";
    archive.requestSubmit();
    await until(() => text(c.document.querySelector("#view h1")).includes("archived"), "archiving from the page view");
    await settle();
    const un = c.document.querySelector('#actions form[action="/CLO-6/unarchive"]');
    check(un !== null, "which then offers unarchive");
    c.close();
  }

  console.log("=== the editor");
  {
    const w = await live("/CLO-5/edit");
    const d = w.document;
    const loaded = d.querySelector('input[name="expected_version"]').value;
    const ta = d.querySelector("textarea#body");
    ta.value = "# Context\n\nMy edit, linking CLO-1.";
    ta.dispatchEvent(new w.Event("input", { bubbles: true }));
    await until(() => d.querySelector('#preview a[href="/CLO-1"]') !== null, "typing updates the preview, mentions linked");
    check(text(d.querySelector("#banner")) === "", "no banner before anything changes");
    const p = await api("GET", "/api/pages/CLO-5");
    await api("PATCH", "/api/pages/CLO-5", { expected_version: p.version, body: p.body + "\n\nUnderneath.", summary: "agent" });
    await until(() => text(d.querySelector("#banner")).includes("Changed since you opened it, by ui-test"),
      "an edit underneath raises the banner, naming who");
    check(d.querySelector("#banner a")?.getAttribute("href") === `/CLO-5/diff?from=${loaded}&to=${p.version + 1}`,
      "linking the diff");
    check(ta.value === "# Context\n\nMy edit, linking CLO-1.", "the human's text is untouched");

    // the browser's own form post: refused as stale, the text kept, then saved by hand against the new version
    const fields = () => new URLSearchParams([...new w.FormData(d.querySelector(".editor form")).entries()]);
    const post = (body) => fetch(B + "/CLO-5/edit", { method: "POST", body, redirect: "manual" });
    const stale = await post(fields());
    const sd = parse(await stale.text(), B + "/CLO-5/edit");
    check(stale.status === 409, "a stale save is refused, 409");
    check(sd.querySelector("textarea#body").value === "# Context\n\nMy edit, linking CLO-1.", "the editor keeps the human's text");
    check(text(sd.querySelector(".stale")).includes("Underneath."), "and shows what changed underneath");
    const now = sd.querySelector('input[name="expected_version"]').value;
    check(Number(now) === p.version + 1, "the form moves to the current version");
    const merged = new URLSearchParams([...new w.FormData(sd.querySelector(".editor form")).entries()]);
    merged.set("body", "# Context\n\nMy edit, linking CLO-1.\n\nUnderneath.");
    merged.set("summary", "hand merge");
    const saved = await post(merged);
    check(saved.status === 303 && saved.headers.get("location") === "/CLO-5", "the hand-merged save goes through");
    const after = await api("GET", "/api/pages/CLO-5?include=revisions");
    check(after.body.endsWith("Underneath.") && after.revisions.at(-1).summary === "hand merge" &&
      after.revisions.at(-1).actor === "web", "as a web revision with its summary");
    w.close();
  }

  console.log("=== history, revisions, diff and restore");
  {
    const h = (await doc("/CLO-5/history")).d;
    check(h.querySelectorAll("table.revisions tr").length === 4, "history lists r1 to r4");
    check(text(h.querySelector("table.revisions tr")).includes("hand merge"), "newest first, with summaries");
    const r1 = (await doc("/CLO-5/r1")).d;
    check(text(r1.querySelector(".prose")).includes("line two") && r1.querySelector("form.restore"), "an old revision reads on its own");
    const df = (await doc("/CLO-5/diff")).d;
    check(df.querySelector("table.diff tr.insert, table.diff tr.change") !== null, "the diff defaults to the previous revision");
    check(df.querySelector("table.diff ins") !== null || df.querySelector("tr.insert") !== null, "with changes highlighted");
    const restore = await fetch(B + "/CLO-5/restore", {
      method: "POST", redirect: "manual",
      body: new URLSearchParams({ revision: "1", expected_version: "4", summary: "back to the start" }),
    });
    check(restore.status === 303, "restoring r1 redirects to the page");
    const p = await api("GET", "/api/pages/CLO-5?include=revisions");
    check(p.version === 5 && p.body.includes("line two") && p.revisions.at(-1).restored_from === 1, "as r5, restored from r1");
  }

  console.log("=== new pages");
  {
    const form = (await doc("/new?parent=CLO-5")).d;
    check(form.querySelector('input[name="parent"]').value === "CLO-5", "a child page form carries its parent");
    const bad = await fetch(B + "/new", { method: "POST", redirect: "manual", body: new URLSearchParams({ title: "", body: "kept", project: "CLO" }) });
    const bd = parse(await bad.text(), B + "/new");
    check(bad.status === 400 && bd.querySelector("textarea#body").value === "kept", "a refused create keeps the text");
    const ok = await fetch(B + "/new", {
      method: "POST", redirect: "manual",
      body: new URLSearchParams({ title: "Glossary", body: "Terms.", parent: "CLO-5", summary: "start" }),
    });
    check(ok.status === 303 && ok.headers.get("location") === "/CLO-8", "creating a child page lands on it");
  }

  console.log("=== missing items");
  {
    const m = await doc("/CLO-99");
    check(m.status === 404 && text(m.d.querySelector(".notice")).includes("not_found"), "an id not yet created is 404");
    const u = await doc("/NOPE-1");
    check(u.status === 404 && text(u.d.querySelector(".notice")).includes("unknown_project"), "an unknown project is 404");
  }

  console.log(failures ? `${failures} UI check(s) failed` : "UI check passed");
  process.exit(failures ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(1); });
