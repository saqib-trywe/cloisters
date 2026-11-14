// Live updates from the API's one SSE stream (see cloisters.ui). Each event names an item and the items it relates.
// An element watching one of them ([data-live="CLO-7"]) gets a cloisters:change trigger, on which htmx re-fetches its
// fragment by ordinary GET; a guarded one ([data-guard]) waits while the reader is typing in it. Lists never
// reshuffle: a [data-count] bar counts updates instead. EventSource resends Last-Event-ID when it reconnects, so the
// daemon resumes the stream where it left off.
(() => {
  const busy = (el) =>
    el.contains(document.activeElement) ||
    [...el.querySelectorAll("textarea, input")].some((f) => f.type !== "hidden" && f.value !== f.defaultValue);
  const stream = new EventSource("/api/events/stream");
  stream.onmessage = (message) => {
    const event = JSON.parse(message.data);
    const ids = [event.id, ...(event.related || [])];
    for (const el of document.querySelectorAll("[data-live]")) {
      if (!ids.includes(el.dataset.live)) continue;
      if (el.hasAttribute("data-guard") && busy(el)) continue;
      htmx.trigger(el, "cloisters:change", event);
    }
    for (const bar of document.querySelectorAll("[data-count]")) {
      const n = bar.querySelector(".n");
      n.textContent = String(Number(n.textContent) + 1);
      bar.hidden = false;
    }
  };
})();
