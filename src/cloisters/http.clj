(ns cloisters.http
  "The HTTP adapter and the server: Ring on Jetty 12, reitit for routing only, one JSON mapper.

  Routes, in order: /api/… (REST for CRUD, POST intent actions), /mcp, /assets/… (an unknown asset is 404), then
  the UI's (cloisters.ui), which answer HTML. The adapter only turns requests into argument maps and results into responses: the core
  validates, so a bad argument is the same invalid_argument here as anywhere.

  - JSON by default; Accept: text/plain returns the text rendering MCP's text block carries, and refusals as text led
    by their code. Either way a refusal keeps its status: 404, 400 or 409, by code. Clients switch on the code.
  - The bound project comes in the Cloisters-Project header. The actor is the Cloisters-Client header (the CLI sends
    cli), else http.
  - Localhost hardening on every route: Host must be localhost or 127.0.0.1 on the daemon's port, and an Origin, when
    sent, the daemon's own. Anything else is 403.
  - Every route has a catch-all: an unexpected exception answers 500 in the contract's shape, never Jetty's HTML."
  (:require [cloisters.core :as core]
            [cloisters.events :as events]
            [cloisters.issues :as issues]
            [cloisters.json :as json]
            [cloisters.mcp :as mcp]
            [cloisters.pages :as pages]
            [cloisters.projects :as projects]
            [cloisters.search :as search]
            [cloisters.text :as text]
            [cloisters.ui :as ui]
            [clojure.string :as str]
            [reitit.ring :as ring]
            [ring.adapter.jetty :as jetty]
            [ring.core.protocols :as protocols]
            [ring.middleware.params :as params])
  (:import (clojure.lang ExceptionInfo)
           (java.io IOException OutputStream OutputStreamWriter Writer)
           (java.nio.charset StandardCharsets)
           (java.util.concurrent LinkedBlockingQueue TimeUnit)
           (org.eclipse.jetty.server Server)))

;; ---------------------------------------------------------------- responses

(def ^:private statuses
  {"not_found" 404 "unknown_project" 404 "wrong_kind" 404
   "invalid_query" 400 "invalid_argument" 400 "edit_mismatch" 400
   "internal_error" 500 "forbidden" 403})

(defn status-of
  "The HTTP status for an error code: 404 for what doesn't exist, 400 for a malformed request, 409 for every invariant
  or concurrency refusal."
  [code]
  (get statuses code 409))

(defn- text? [request]
  (str/includes? (str (get-in request [:headers "accept"])) "text/plain"))

(defn- respond-with [request op status result]
  (if (text? request)
    {:status status :headers {"Content-Type" "text/plain; charset=utf-8"} :body (str (text/render op result) "\n")}
    {:status status :headers {"Content-Type" "application/json"} :body (json/write-str result)}))

(defn- refusal
  "The response refusing a request: {error, message, …facts}, or as text led by the code."
  [request code message facts]
  (let [status (status-of code)]
    (if (text? request)
      {:status status :headers {"Content-Type" "text/plain; charset=utf-8"} :body (str (text/error code message) "\n")}
      {:status status :headers {"Content-Type" "application/json"}
       :body (json/write-str (merge {:error code :message message} (dissoc facts :error)))})))

(defn- unexpected [request ^Throwable e]
  (binding [*out* *err*]
    (println "cloisters http: unexpected" (str e)))
  (.printStackTrace e)
  (refusal request "internal_error" (str "The daemon failed unexpectedly: " (or (ex-message e) (str e))) {}))

(defn- guarded
  "h with domain refusals answered in the contract's shape, and anything else as internal_error."
  [h]
  (fn [request]
    (try (h request)
         (catch ExceptionInfo e
           (if-let [code (:error (ex-data e))]
             (refusal request code (ex-message e) (ex-data e))
             (unexpected request e)))
         (catch Exception e (unexpected request e)))))

;; ---------------------------------------------------------------- requests

(defn- json-body
  "The request's JSON object as a kebab-case map; {} when empty. Malformed JSON is invalid_argument."
  [request]
  (let [s (some-> (:body request) slurp)]
    (if (str/blank? s)
      {}
      (let [v (try (json/read-str s)
                   (catch Exception _
                     (core/fail "invalid_argument" "The request body is not valid JSON."
                                :argument "body" :reason "malformed JSON")))]
        (if (map? v)
          v
          (core/fail "invalid_argument" "The request body must be a JSON object."
                     :argument "body" :reason "not a JSON object"))))))

(defn- number-or-string
  "A query parameter as a number when it is digits, else unchanged, so the core names a bad value."
  [s]
  (if (and (string? s) (re-matches #"-?\d{1,18}" s)) (parse-long s) s))

(defn- query-args
  "Query parameters as arguments: wire name to key, numbers where named, lists split on commas."
  [request spec]
  (into {} (for [[param k how] spec
                 :let [v (get-in request [:query-params param])]
                 :when (some? v)]
             [k (case how
                  :number (number-or-string v)
                  :list (vec (remove str/blank? (str/split v #",")))
                  v)])))

(defn- id-arg [request] {:id (get-in request [:path-params :id])})

(defn- ctx-for
  "The operation context for a request: the bound project from Cloisters-Project, refused when unknown, and the
  actor."
  [ctx request]
  (let [project (not-empty (get-in request [:headers "cloisters-project"]))]
    (when project (core/with-read ctx #(core/require-project % project)))
    (assoc ctx
           :project project
           :actor (or (not-empty (get-in request [:headers "cloisters-client"])) "http"))))

(defn- endpoint
  "A route calling f with the arguments args-of reads from the request, answering status with op's rendering."
  [ctx op f args-of & {:keys [status] :or {status 200}}]
  (guarded
   (fn [request]
     (respond-with request op status (f (ctx-for ctx request) (args-of request))))))

(defn- body+id [request] (merge (json-body request) (id-arg request)))

;; ---------------------------------------------------------------- the event stream

(defn- hub
  "Subscribers to new events: notify! wakes each, after every commit that recorded events."
  []
  (let [subscribers (atom #{})]
    {:subscribers subscribers
     :notify! (fn [_seqs] (doseq [^LinkedBlockingQueue q @subscribers] (.offer q :wake)))}))

(def keep-alive-ms 15000)

(defn- sse-event [e]
  (str "id: " (:seq e) "\ndata: " (json/write-str e) "\n\n"))

(defn- stream-events
  "Writes events after `after` to out as server-sent events, then each new one as it commits, with a comment line as
  keep-alive. Opens with a comment, since Jetty commits the headers only on the first write. Returns when the client
  goes."
  [ctx {:keys [subscribers]} after ^OutputStream out]
  (let [q (LinkedBlockingQueue.)
        w (OutputStreamWriter. out StandardCharsets/UTF_8)
        send! (fn [^String s] (.write ^Writer w s) (.flush ^Writer w))]
    ;; subscribe before reading, so nothing committed in between is missed
    (swap! subscribers conj q)
    (try
      (send! ": open\n\n")
      (loop [sent after]
        (.clear q)
        (let [{batch :events} (events/feed ctx {:after sent :limit 200})]
          (if (seq batch)
            (do (doseq [e batch] (send! (sse-event e)))
                (recur (:seq (peek batch))))
            (do (when-not (.poll q keep-alive-ms TimeUnit/MILLISECONDS)
                  (send! ": keep-alive\n\n"))
                (recur sent)))))
      (catch IOException _ nil)
      (finally
        (swap! subscribers disj q)
        (try (.close w) (catch IOException _ nil))))))

(defn- stream-response [ctx hub request]
  (let [after (or (number-or-string (get-in request [:headers "last-event-id"]))
                  (number-or-string (get-in request [:query-params "after"]))
                  (events/latest-seq ctx))]
    (when-not (and (int? after) (<= 0 after))
      (core/fail "invalid_argument" "after (or Last-Event-ID) must be an event sequence number."
                 :argument "after" :reason "must be a non-negative integer"))
    {:status 200
     :headers {"Content-Type" "text/event-stream" "Cache-Control" "no-cache"}
     :body (reify protocols/StreamableResponseBody
             (write-body-to-stream [_ _ out] (stream-events ctx hub after out)))}))

;; ---------------------------------------------------------------- hardening

(defn- harden
  "Refuses anything but a local request to this port: a Host other than localhost or 127.0.0.1 (DNS rebinding), or a
  foreign Origin."
  [handler port]
  (let [hosts #{(str "localhost:" port) (str "127.0.0.1:" port)}
        origins #{(str "http://localhost:" port) (str "http://127.0.0.1:" port)}
        local? (fn [{:keys [headers]}]
                 (and (hosts (get headers "host"))
                      (let [o (get headers "origin")] (or (nil? o) (origins o)))))
        refuse #(refusal % "forbidden" "Only local requests to this daemon are served." {})]
    (fn
      ([request] (if (local? request) (handler request) (refuse request)))
      ([request respond raise]
       (if (local? request) (handler request respond raise) (respond (refuse request)))))))

;; ---------------------------------------------------------------- the app

(defn- both
  "A synchronous handler that also answers the asynchronous arity, as Jetty calls it."
  [h]
  (fn
    ([request] (h request))
    ([request respond raise]
     (try (respond (h request)) (catch Throwable t (raise t))))))

(defn- streaming
  "A handler whose response streams: asynchronously, it runs on its own virtual thread, freeing Jetty's."
  [h]
  (fn
    ([request] (h request))
    ([request respond raise]
     (Thread/startVirtualThread
      (fn [] (try (respond (h request)) (catch Throwable t (raise t))))))))

(defn- not-found [request]
  (refusal request "not_found" (str "No route " (str/upper-case (name (:request-method request))) " " (:uri request) ".")
           {}))

(defn- method-not-allowed [request]
  {:status 405 :headers {"Content-Type" "text/plain; charset=utf-8"}
   :body (str "method " (str/upper-case (name (:request-method request))) " not allowed on " (:uri request) "\n")})

(defn app
  "The Ring handler for every route, over ctx (the store, :version, :dirs, :port). Returns the handler and the event
  hub, whose :notify! the context's :notify should call."
  [{:keys [port] :as ctx}]
  (let [{:keys [notify!] :as events-hub} (hub)
        ctx (assoc ctx :notify notify!)
        e (fn [op f args-of & opts] (both (apply endpoint ctx op f args-of opts)))
        action (fn [op f] {:post (e op f body+id)})
        mcp-handler (mcp/handler ctx (atom {}))
        ui-handler (ui/handler ctx status-of)]
    {:notify notify!
     :handler
     (-> (ring/ring-handler
          (ring/router
           [["/api"
             ["/meta" {:get (both (guarded (fn [request]
                                             (respond-with request :meta 200
                                                           {:version (:version ctx)
                                                            :data-dir (some-> ctx :dirs :data str)}))))}]
             ["/projects" {:get (e :list-projects projects/list-projects (constantly {}))
                           :post (e :create-project projects/create-project json-body :status 201)}]
             ["/search" {:get (e :search search/search
                                 #(query-args % [["q" :query] ["cursor" :cursor] ["limit" :limit :number]]))}]
             ["/issues" {:post (e :create-issue issues/create-issue json-body :status 201)}]
             ["/issues/:id"
              ["" {:get (e :get-issue issues/get-issue #(merge (id-arg %) (query-args % [["include" :include :list]])))
                   :patch (e :update-issue issues/update-issue body+id)}]
              ["/claim" (action :claim-issue issues/claim-issue)]
              ["/release" (action :release-issue issues/release-issue)]
              ["/reassign" (action :reassign-issue issues/reassign-issue)]
              ["/close" (action :close-issue issues/close-issue)]
              ["/reopen" (action :reopen-issue issues/reopen-issue)]
              ["/events" {:get (e :issue-events events/issue-events
                                  #(merge (id-arg %)
                                          (query-args % [["after" :after :number] ["cursor" :after :number]
                                                         ["limit" :limit :number]])))}]]
             ["/pages" {:post (e :create-page pages/create-page json-body :status 201)}]
             ["/pages/:id"
              ["" {:get (e :get-page pages/get-page
                           #(merge (id-arg %) (query-args % [["revision" :revision :number]
                                                             ["include" :include :list]])))
                   :patch (e :update-page pages/update-page body+id)}]
              ["/archive" (action :archive-page pages/archive-page)]
              ["/unarchive" (action :unarchive-page pages/unarchive-page)]
              ["/restore" (action :restore-page pages/restore-page)]
              ["/revisions" {:get (e :page-revisions pages/page-revisions id-arg)}]
              ["/diff" {:get (e :diff-page pages/diff-page
                                #(merge (id-arg %) (query-args % [["from" :from :number] ["to" :to :number]])))}]]
             ["/events" {:get (e :events events/feed
                                 #(query-args % [["after" :after :number] ["limit" :limit :number]]))}]
             ["/events/stream" {:get (streaming (guarded #(stream-response ctx events-hub %)))}]]
            ["/mcp" {:handler (both (fn [request] (mcp-handler request)))}]])
          (ring/routes
           (ring/create-resource-handler {:path "/assets" :root "assets"})
           (both (guarded ui-handler))
           (ring/create-default-handler {:not-found (both not-found)
                                         :method-not-allowed (both method-not-allowed)
                                         :not-acceptable (both not-found)})))
         params/wrap-params
         (harden port))}))

;; ---------------------------------------------------------------- the server

(defn start!
  "Serves ctx on 127.0.0.1:port and returns the Jetty server. The context's writes notify the event stream."
  ^Server [ctx port]
  (let [{:keys [handler]} (app (assoc ctx :port port))]
    (jetty/run-jetty handler {:host "127.0.0.1" :port port :join? false :async? true :async-timeout 0})))

(defn stop! [^Server server]
  (.stop server))
