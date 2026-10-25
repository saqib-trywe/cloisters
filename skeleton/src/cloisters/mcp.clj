(ns cloisters.mcp
  "PROTOTYPE. Hand-rolled MCP as one Ring handler at /mcp (ADR 0001): JSON-RPC request map in, response map out.
  2026-07-28 is stateless with per-request _meta; 2025-11-25 keeps the initialize handshake and a session holding only
  the client name."
  (:require [clojure.string :as str]
            [cloisters.contract :as contract]
            [cloisters.core :as core]
            [cloisters.text :as text]
            [jsonista.core :as json])
  (:import (java.util UUID)))

(def modern "2026-07-28")
(def legacy "2025-11-25")
(def legacy-versions #{"2025-11-25" "2025-06-18" "2025-03-26"})
(def server-info {"name" "cloisters" "version" "0.0.0-skeleton"})

(def ^:private P "io.modelcontextprotocol/")

;; ---------------------------------------------------------------- tools

(def tools
  [{:name "search" :op :search :fn core/search
    :description "Start here to find work. Search issues and pages with the query grammar; rows carry their kind."}
   {:name "get_issue" :op :get-issue :fn core/get-issue
    :description "Read one issue with its body, mentions and backlinks (each entry carries its kind)."}
   {:name "create_issue" :op :create-issue :fn core/create-issue :write true
    :description "Create an issue. Not idempotent: search before retrying."}
   {:name "get_page" :op :get-page :fn core/get-page
    :description "Read one page: full body verbatim, version, children, mentions and backlinks."}
   {:name "create_page" :op :create-page :fn core/create-page :write true
    :description "Create a page, as a root or beneath parent. Not idempotent: search before retrying."}
   {:name "update_page" :op :update-page :fn core/update-page :write true
    :description "Edit a page's title or body. Pass expected_version from get_page to refuse a stale save."}])

(def ^:private by-name (into {} (map (juxt :name identity)) tools))

(defn- tool-list []
  (mapv (fn [{:keys [name op description]}]
          {"name" name "description" description
           "inputSchema" (contract/json-schema (get-in contract/ops [op :args]))
           "outputSchema" (contract/json-schema (get-in contract/ops [op :result]))})
        tools))

(defn- drop-nulls [x]
  (cond (map? x) (into {} (keep (fn [[k v]] (when (some? v) [(contract/snake k) (drop-nulls v)]))) x)
        (sequential? x) (mapv drop-nulls x)
        :else x))

(defn- call-tool [ctx {:strs [name arguments]} actor]
  (let [{f :fn :keys [op write]} (by-name name)
        args (update-keys (or arguments {}) contract/kebab)]
    (try
      (let [result (if write (f ctx args actor) (f ctx args))]
        {"content" [{"type" "text" "text" (text/render op result)}]
         "structuredContent" (drop-nulls result)
         "isError" false})
      (catch clojure.lang.ExceptionInfo e
        (let [d (ex-data e)]
          (if (:error d)
            {"content" [{"type" "text" "text" (text/error d (ex-message e))}]
             "structuredContent" (drop-nulls (-> d (dissoc :status) (assoc :message (ex-message e))))
             "isError" true}
            (throw e)))))))

;; ---------------------------------------------------------------- JSON-RPC plumbing

(defn- reply
  ([status body] (reply status body {}))
  ([status body headers]
   {:status status :headers (merge {"Content-Type" "application/json"} headers)
    :body (json/write-value-as-string body)}))

(defn- ok [id result & [headers]] (reply 200 {"jsonrpc" "2.0" "id" id "result" result} headers))
(defn- err [status id code message & [data]]
  (reply status {"jsonrpc" "2.0" "id" id "error" (cond-> {"code" code "message" message} data (assoc "data" data))}))

(defn- modern-result [m] (assoc m "resultType" "complete" "_meta" {(str P "serverInfo") server-info}))

(defn- modern-request [ctx {:keys [headers]} {:strs [id method params]}]
  (let [meta (get params "_meta")
        version (get meta (str P "protocolVersion"))
        client (get-in meta [(str P "clientInfo") "name"] "unknown")
        name-field (when (= method "tools/call") (get params "name"))]
    (cond
      (or (not (map? meta)) (nil? version) (not (map? (get meta (str P "clientCapabilities")))))
      (err 400 id -32602 "Invalid params: _meta needs protocolVersion and clientCapabilities")

      (or (not= (get headers "mcp-protocol-version") version) (not= (get headers "mcp-method") method)
          (and name-field (not= (get headers "mcp-name") name-field)))
      (err 400 id -32020 "Header mismatch: MCP-Protocol-Version, Mcp-Method and Mcp-Name must mirror the body")

      (not= version modern)
      (err 400 id -32022 "Unsupported protocol version" {"supported" [modern] "requested" (str version)})

      :else
      (case method
        "server/discover" (ok id (modern-result {"supportedVersions" [modern] "capabilities" {"tools" {}}
                                                 "serverInfo" server-info "ttlMs" 3600000 "cacheScope" "public"}))
        "tools/list" (ok id (modern-result {"tools" (tool-list) "ttlMs" 3600000 "cacheScope" "public"}))
        "tools/call" (if (by-name name-field)
                       (ok id (modern-result (call-tool ctx params (str "mcp:" client " (" modern ")"))))
                       (err 200 id -32602 (str "unknown tool " name-field)))
        (err 404 id -32601 (str "Method not found: " method))))))

(defonce sessions (atom {}))

(defn- legacy-request [ctx {:keys [headers]} {:strs [id method params]}]
  (let [sid (get headers "mcp-session-id")
        client (get @sessions sid)]
    (cond
      (= method "initialize")
      (let [sid (str (UUID/randomUUID))]
        (swap! sessions assoc sid (get-in params ["clientInfo" "name"] "unknown"))
        (ok id {"protocolVersion" legacy "capabilities" {"tools" {}} "serverInfo" server-info}
            {"Mcp-Session-Id" sid}))
      (nil? sid) (err 400 id -32600 "Bad request: missing Mcp-Session-Id; initialize first")
      (nil? client) (err 404 id -32600 "Session not found; initialize again")
      :else
      (case method
        "ping" (ok id {})
        "tools/list" (ok id {"tools" (tool-list)})
        "tools/call" (if (by-name (get params "name"))
                       (ok id (call-tool ctx params (str "mcp:" client " (" legacy ")")))
                       (err 200 id -32602 (str "unknown tool " (get params "name"))))
        (err 404 id -32601 (str "Method not found: " method))))))

(defn- modern? [{:keys [headers]} {:strs [params]}]
  (let [v (get headers "mcp-protocol-version")]
    (or (some #(str/starts-with? % P) (keys (get params "_meta")))
        (and v (not (legacy-versions v))))))

(defn handler [ctx request]
  (case (:request-method request)
    :post (let [msg (try (json/read-value (slurp (:body request))) (catch Exception _ ::bad))]
            (cond
              (= msg ::bad) (err 400 nil -32700 "Parse error")
              (not (and (map? msg) (= "2.0" (get msg "jsonrpc")))) (err 400 nil -32600 "Invalid request")
              (not (contains? msg "id")) {:status 202 :headers {} :body ""}
              (modern? request msg) (modern-request ctx request msg)
              :else (legacy-request ctx request msg)))
    :delete (if (get @sessions (get-in request [:headers "mcp-session-id"]))
              (do (swap! sessions dissoc (get-in request [:headers "mcp-session-id"])) {:status 204 :body ""})
              {:status 404 :body ""})
    {:status 405 :headers {"Allow" "POST, DELETE"} :body ""}))
