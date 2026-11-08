(ns cloisters.mcp
  "MCP, hand-rolled as one Ring handler at /mcp (ADR 0001): a JSON-RPC request in, a response out, JSON only, tools
  only, no server push.

  Two protocol revisions, chosen per request. 2026-07-28 is stateless: every request carries _meta (protocol version,
  client info and capabilities) and mirrors its method, version and tool name into headers. 2025-11-25 keeps the
  initialize handshake, and its session holds only the client name, so events name their actor on both revisions.

  A connection may be bound to a project in its URL (/mcp?project=CLO): creates default to it and unscoped queries
  stay in it. An unknown project in the binding is refused at the HTTP level, since the model cannot fix its own
  config. Domain refusals are isError tool results carrying the contract's {error, …facts}; JSON-RPC errors are only
  for malformed calls, unknown tools and the unexpected."
  (:require [cloisters.contract :as contract]
            [cloisters.core :as core]
            [cloisters.issues :as issues]
            [cloisters.json :as json]
            [cloisters.pages :as pages]
            [cloisters.search :as search]
            [cloisters.text :as text]
            [clojure.string :as str])
  (:import (clojure.lang ExceptionInfo)
           (java.util UUID)))

(def modern "2026-07-28")
(def legacy "2025-11-25")
(def ^:private legacy-versions #{"2025-11-25" "2025-06-18" "2025-03-26"})
(def ^:private P "io.modelcontextprotocol/")
(def ^:private cache {"ttlMs" 3600000 "cacheScope" "public"})

;; ---------------------------------------------------------------- tools

(def ^:private search-description
  "Start here to find work: `ready` lists open, unblocked, unclaimed issues. One search over issues and pages; each
row carries its kind. Filters AND together, comma values OR (`label:bug,ux`), and a leading `-` negates
(`-assignee:none`). Filters: kind:issue|page, project:KEY (or project:* for all), id:, parent:ID, under:ID (subtree),
mentions:ID, mentioned-by:ID, text:substring (quote spaces: text:\"two words\"), status:open|closed,
resolution:done|dropped, assignee:NAME|none|any|prefix*, label:NAME|prefix*, blocked, unblocked, blocks:ID,
blocked-by:ID, archived:no|yes|any (pages; no by default), and created-, updated-, closed-, claimed- with before: or
after: (3d, 2h, 30m, 2026-10-01). sort:rank|created|updated|closed, each -asc or -desc. Unscoped queries stay in the
bound project. Paging: pass next_cursor back as cursor; items unchanged while you page appear exactly once.")

(def tools
  "The sixteen tools: name, the operation whose schemas they declare, the core function, and a description."
  [{:name "search" :op :search :fn search/search :read true
    :description search-description}
   {:name "get_issue" :op :get-issue :fn issues/get-issue :read true
    :description (str "Read one issue: fields, body verbatim, version, parent, children, blockers, mentions and "
                      "backlinks (each entry with its kind), and comments. include [\"events\"] adds the latest 50 "
                      "events, body changes as diffs.")}
   {:name "create_issue" :op :create-issue :fn issues/create-issue
    :description (str "Create an issue. project defaults to the parent's, then the binding. Rank places it: a number, "
                      "or rank_before/rank_after an issue id. Not idempotent: search before retrying.")}
   {:name "update_issue" :op :update-issue :fn issues/update-issue
    :description (str "Edit an issue: title, body (whole) or body_edits (exact unique {old, new} replacements), "
                      "labels_add/labels_remove, parent (null clears), blocked_by_add/blocked_by_remove, rank, and a "
                      "comment. A plain comment is update_issue with only id and comment. Pass expected_version to "
                      "refuse a stale write. The assignee changes only through claim, release and reassign.")}
   {:name "claim_issue" :op :claim-issue :fn issues/claim-issue
    :description (str "Take an issue: succeeds only if it has no assignee (claim_conflict names the holder). The "
                      "assignee must name your session, not just you (e.g. saqib/wf-7f3a): claiming what you hold "
                      "succeeds, so two sessions sharing a name would both win.")}
   {:name "release_issue" :op :release-issue :fn issues/release-issue
    :description (str "Give an issue back, e.g. when blocked. Only its holder can; assignee is your session name. "
                      "Releasing an unassigned issue changes nothing.")}
   {:name "reassign_issue" :op :reassign-issue :fn issues/reassign-issue
    :description (str "Move an issue from the holder you expect (from, or none) to another (to, or none), e.g. to "
                      "clear a stale claim. Name sessions, not just people.")}
   {:name "close_issue" :op :close-issue :fn issues/close-issue
    :description (str "Close an issue as done or dropped, with a comment saying why. Refused while it has open "
                      "children, or open blockers when done. Returns the issues it newly unblocked. Closing again "
                      "with the same resolution changes nothing.")}
   {:name "reopen_issue" :op :reopen-issue :fn issues/reopen-issue
    :description (str "Reopen a closed issue, with a comment saying why. Refused while its parent is closed or an "
                      "issue it blocks is closed done.")}
   {:name "get_page" :op :get-page :fn pages/get-page :read true
    :description (str "Read one page: fields, full body verbatim, version, parent, children as rows, mentions and "
                      "backlinks. revision reads an earlier revision's title and body. include [\"revisions\"] adds "
                      "the newest 50 revisions.")}
   {:name "create_page" :op :create-page :fn pages/create-page
    :description (str "Create a page, as a root or beneath parent (never beneath an archived page). project defaults "
                      "to the parent's, then the binding. Not idempotent: search before retrying.")}
   {:name "update_page" :op :update-page :fn pages/update-page
    :description (str "Edit, move or reorder a page: title, body (whole) or body_edits (exact unique {old, new} "
                      "replacements), parent (null makes a root), rank, and an edit summary. Pass expected_version "
                      "from get_page to refuse a stale save.")}
   {:name "archive_page" :op :archive-page :fn pages/archive-page
    :description (str "Archive a page, with a summary saying why. Refused while it has live children, which it names: "
                      "archive or move them first. Archiving an archived page changes nothing.")}
   {:name "unarchive_page" :op :unarchive-page :fn pages/unarchive-page
    :description (str "Bring an archived page back, with a summary saying why. Refused beneath an archived parent. "
                      "Unarchiving a live page changes nothing.")}
   {:name "restore_page" :op :restore-page :fn pages/restore-page
    :description (str "Make a new revision with an earlier revision's title and body, recording which it restored. "
                      "Leaves parent, rank and archiving alone. Restoring what the page already says changes nothing.")}
   {:name "diff_page" :op :diff-page :fn pages/diff-page :read true
    :description (str "Compare two revisions of a page: title before and after, and a unified line diff of the body. "
                      "Defaults to the latest against the one before.")}])

(def ^:private by-name (into {} (map (juxt :name identity)) tools))

(defn tool-list
  "tools/list's tools, with schemas derived from the contract."
  []
  (mapv (fn [{:keys [name op description read]}]
          {"name" name
           "description" description
           "inputSchema" (contract/json-schema (get-in contract/ops [op :args]))
           "outputSchema" (contract/json-schema (contract/results op))
           "annotations" (if read
                           {"readOnlyHint" true}
                           {"readOnlyHint" false "destructiveHint" false "openWorldHint" false})})
        tools))

(defn- call-tool
  "A tools/call result: the text block and structuredContent rendered from one value, or an isError result for a
  refusal."
  [ctx {:strs [name arguments]}]
  (let [{f :fn :keys [op]} (by-name name)]
    (try
      (let [result (f ctx (json/kebab-keys (or arguments {})))]
        {"content" [{"type" "text" "text" (text/render op result)}]
         "structuredContent" (json/drop-nils result)
         "isError" false})
      (catch ExceptionInfo e
        (let [{:keys [error] :as facts} (ex-data e)]
          (if error
            {"content" [{"type" "text" "text" (text/error error (ex-message e))}]
             "structuredContent" (json/drop-nils (merge {:error error :message (ex-message e)} facts))
             "isError" true}
            (throw e)))))))

;; ---------------------------------------------------------------- JSON-RPC

(defn- reply [status body & [headers]]
  {:status status
   :headers (merge {"Content-Type" "application/json"} headers)
   :body (json/write-str-keeping-nils body)})

(defn- ok [id result & [headers]]
  (reply 200 {"jsonrpc" "2.0" "id" id "result" result} headers))

(defn- err [status id code message & [data]]
  (reply status {"jsonrpc" "2.0" "id" id "error" (cond-> {"code" code "message" message} data (assoc "data" data))}))

(defn- server-info [ctx] {"name" "cloisters" "version" (or (:version ctx) "dev")})

(defn- modern-result [ctx m]
  (assoc m "resultType" "complete" "_meta" {(str P "serverInfo") (server-info ctx)}))

(defn- internal [id ^Throwable e]
  (binding [*out* *err*]
    (println "cloisters mcp: unexpected" (str e)))
  (.printStackTrace e)
  (err 500 id -32603 (str "Internal error: " (or (ex-message e) (str e)))))

(defn- tools-call [ctx id params client wrap]
  (let [tool (get params "name")]
    (if (by-name tool)
      (try (ok id (wrap (call-tool (assoc ctx :actor (str "mcp:" client)) params)))
           (catch Exception e (internal id e)))
      (err 200 id -32602 (str "Unknown tool: " tool)))))

(defn- modern-request
  "A 2026-07-28 request: _meta first, then the headers mirroring the body, then the version."
  [ctx {:keys [headers]} {:strs [id method params]}]
  (let [meta (get params "_meta")
        version (get meta (str P "protocolVersion"))
        client (or (get-in meta [(str P "clientInfo") "name"]) "unknown")
        tool (when (= "tools/call" method) (get params "name"))]
    (cond
      (not (and (map? meta) (string? version) (map? (get meta (str P "clientCapabilities")))))
      (err 400 id -32602 "Invalid params: _meta needs protocolVersion and clientCapabilities")

      (or (not= (get headers "mcp-protocol-version") version)
          (not= (get headers "mcp-method") method)
          (and tool (not= (get headers "mcp-name") tool)))
      (err 400 id -32020 "Header mismatch: MCP-Protocol-Version, Mcp-Method and Mcp-Name must mirror the body")

      (not= modern version)
      (err 400 id -32022 "Unsupported protocol version" {"supported" [modern] "requested" version})

      :else
      (case method
        "server/discover" (ok id (modern-result ctx (merge {"supportedVersions" [modern]
                                                            "capabilities" {"tools" {}}
                                                            "serverInfo" (server-info ctx)}
                                                           cache)))
        "tools/list" (ok id (modern-result ctx (merge {"tools" (tool-list)} cache)))
        "tools/call" (tools-call ctx id params client #(modern-result ctx %))
        (err 404 id -32601 (str "Method not found: " method))))))

(defn- legacy-request
  "A 2025-11-25 request: initialize opens a session holding the client name; every later request names it."
  [ctx sessions {:keys [headers]} {:strs [id method params]}]
  (let [sid (get headers "mcp-session-id")
        client (get @sessions sid)
        version (get headers "mcp-protocol-version")]
    (cond
      (= "initialize" method)
      (let [sid (str (UUID/randomUUID))]
        (swap! sessions assoc sid (or (get-in params ["clientInfo" "name"]) "unknown"))
        (ok id {"protocolVersion" legacy "capabilities" {"tools" {}} "serverInfo" (server-info ctx)}
            {"Mcp-Session-Id" sid}))

      (nil? sid) (err 400 id -32600 "Bad request: no Mcp-Session-Id; initialize first")
      (nil? client) (err 404 id -32600 "Session not found; initialize again")
      (and version (not (legacy-versions version)))
      (err 400 id -32600 (str "Unsupported MCP-Protocol-Version: " version))

      :else
      (case method
        "ping" (ok id {})
        "tools/list" (ok id {"tools" (tool-list)})
        "tools/call" (tools-call ctx id params client identity)
        (err 404 id -32601 (str "Method not found: " method))))))

(defn- modern?
  "Any io.modelcontextprotocol/ _meta key, or a version header outside the legacy ones, selects 2026-07-28."
  [{:keys [headers]} {:strs [params]}]
  (let [v (get headers "mcp-protocol-version")
        meta (when (map? params) (get params "_meta"))]
    (boolean (or (and (map? meta) (some #(str/starts-with? % P) (keys meta)))
                 (and v (not (legacy-versions v)))))))

(defn- read-message [request]
  (try (json/read-plain (slurp (:body request)))
       (catch Exception _ ::malformed)))

(defn- dispatch [ctx sessions request]
  (case (:request-method request)
    :post (let [msg (read-message request)]
            (cond
              (= ::malformed msg) (err 400 nil -32700 "Parse error")
              (not (and (map? msg) (= "2.0" (get msg "jsonrpc")) (string? (get msg "method"))))
              (err 400 (when (map? msg) (get msg "id")) -32600 "Invalid request")
              (not (contains? msg "id")) {:status 202 :headers {} :body ""}
              (modern? request msg) (modern-request ctx request msg)
              :else (legacy-request ctx sessions request msg)))
    :delete (let [sid (get-in request [:headers "mcp-session-id"])]
              (if (and sid (contains? @sessions sid))
                (do (swap! sessions dissoc sid) {:status 204 :headers {} :body ""})
                {:status 404 :headers {} :body ""}))
    {:status 405 :headers {"Allow" "POST, DELETE"} :body ""}))

(defn handler
  "The /mcp handler over ctx. sessions is an atom holding the 2025-11-25 sessions. The binding comes from the URL's
  ?project=, refused with 404 naming the valid keys when no such project exists."
  [ctx sessions]
  (fn [request]
    (let [project (get-in request [:query-params "project"])
          keys (when project (core/with-read ctx core/project-keys))]
      (cond
        (and project (not (some #{project} keys)))
        {:status 404
         :headers {"Content-Type" "application/json"}
         :body (json/write-str {:error "unknown_project"
                                :message (str "This MCP endpoint is bound to " project ", which does not exist. "
                                              "Projects: " (if (seq keys) (str/join ", " keys) "none") ".")
                                :project project :projects keys})}

        :else
        (try (dispatch (assoc ctx :project project) sessions request)
             (catch Exception e (internal nil e)))))))
