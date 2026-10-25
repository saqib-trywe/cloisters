// PROTOTYPE. The one SSE stream, turned into an htmx trigger that fragments re-fetch on.
// EventSource resends Last-Event-ID on reconnect, so the daemon resumes where it left off.
new EventSource("/api/events/stream").addEventListener("change", (e) => {
  htmx.trigger(document.body, "cloisters:change", JSON.parse(e.data));
});
