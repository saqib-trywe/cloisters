(ns cloisters.mcp-test
  "MCP through the daemon's handler: both protocol revisions, the binding, refusals as isError results, and every
  tool's result validated against the outputSchema it declares."
  (:require [cloisters.client :as client]
            [cloisters.events :as events]
            [cloisters.fixtures :as fx :refer [with-ctx]]
            [cloisters.json :as json]
            [cloisters.mcp :as mcp]
            [cloisters.projects :as projects]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import (com.networknt.schema InputFormat SchemaRegistry SpecificationVersion)))

(def ^:private P "io.modelcontextprotocol/")

(defn- modern-meta [client]
  {(str P "protocolVersion") mcp/modern
   (str P "clientInfo") {"name" client "version" "1"}
   (str P "clientCapabilities") {}})

(defn- post
  "POSTs a JSON-RPC message to /mcp: the response with :rpc, the body decoded with its keys as written."
  [h msg & {:keys [headers query]}]
  (let [{:keys [body] :as r} (h (client/request :post "/mcp" :body (json/write-str-keeping-nils msg)
                                                :headers headers :query query))]
    (cond-> r
      (and (string? body) (seq body)) (assoc :rpc (json/read-plain body)))))

(defn- modern
  "A 2026-07-28 request for method, its headers mirroring the body, on a connection bound to CLO unless :query says
  otherwise."
  [h method params & {:keys [client query headers] :or {client "claude-code" query {:project "CLO"}}}]
  (post h {"jsonrpc" "2.0" "id" 1 "method" method "params" (assoc params "_meta" (modern-meta client))}
        :query query
        :headers (merge {"mcp-protocol-version" mcp/modern "mcp-method" method}
                        (when (= "tools/call" method) {"mcp-name" (get params "name")})
                        headers)))

(defn- call-tool
  "A tools/call result over 2026-07-28."
  [h tool args & opts]
  (get-in (apply modern h "tools/call" {"name" tool "arguments" args} opts) [:rpc "result"]))

;; ---------------------------------------------------------------- schemas

(def ^:private registry (SchemaRegistry/withDefaultDialect SpecificationVersion/DRAFT_2020_12))

(defn- schema-errors
  "What is wrong with value (wire JSON data) against the JSON Schema schema (wire JSON data), as strings."
  [schema value]
  (let [s (.getSchema registry ^String (json/write-str schema) InputFormat/JSON)]
    (mapv str (.validate s ^String (json/write-str-keeping-nils value) InputFormat/JSON))))

(defn- tool-schemas
  "Each tool's declared schemas as the client receives them, by name."
  [h]
  (into {} (for [t (get-in (modern h "tools/list" {}) [:rpc "result" "tools"])]
             [(get t "name") t])))

(deftest the-tool-list
  (with-ctx [c (fx/ctx)]
    (let [h (client/handler c)
          tools (tool-schemas h)]
      (testing "sixteen tools, each with input and output schemas in snake_case"
        (is (= 16 (count tools)))
        (is (= (set (map :name mcp/tools)) (set (keys tools))))
        (is (every? #(and (get % "inputSchema") (get % "outputSchema") (get % "description")) (vals tools)))
        (is (contains? (get-in tools ["update_page" "inputSchema" "properties"]) "expected_version"))
        (is (= ["id"] (get-in tools ["update_page" "inputSchema" "required"])))
        (is (false? (get-in tools ["update_page" "inputSchema" "additionalProperties"]))))
      (testing "search leads with ready"
        (is (str/starts-with? (get-in tools ["search" "description"]) "Start here to find work: `ready`")))
      (testing "the validator the next test relies on rejects what doesn't match"
        (is (seq (schema-errors (get-in tools ["get_page" "outputSchema"]) {"id" "CLO-1"})))
        (is (seq (schema-errors (get-in tools ["get_page" "inputSchema"]) {"id" "clo-1"}))))
      (testing "entry descriptions reach their properties"
        (is (= "an edit summary: why the page changed"
               (get-in tools ["update_page" "inputSchema" "properties" "summary" "description"])))))))

(deftest every-tool-result-matches-its-declared-schema
  (with-ctx [c (fx/ctx)]
    (let [h (client/handler c)
          schemas (tool-schemas h)
          seen (atom #{})
          ok (fn [tool args]
               (let [{:strs [structuredContent isError content]} (call-tool h tool args)]
                 (is (false? isError) (str tool " " (pr-str structuredContent)))
                 (is (seq (get-in content [0 "text"])))
                 (is (= [] (schema-errors (get-in schemas [tool "outputSchema"]) structuredContent))
                     (str tool " returned " (pr-str structuredContent)))
                 (is (= [] (schema-errors (get-in schemas [tool "inputSchema"]) args)) (str tool " arguments"))
                 (swap! seen conj tool)
                 structuredContent))]
      (ok "create_issue" {"title" "Map" "labels" ["wayfinder:map"]})
      (ok "create_issue" {"title" "Child mentions CLO-3" "parent" "CLO-1" "body" "see CLO-3"})
      (ok "create_page" {"title" "Glossary" "body" "about CLO-2"})
      (ok "create_page" {"title" "Terms" "parent" "CLO-3" "rank" 1.5 "summary" "start"})
      (ok "create_issue" {"title" "Blocked" "parent" "CLO-1" "blocked_by" ["CLO-2"]})
      (ok "update_issue" {"id" "CLO-2" "body_edits" [{"old" "see" "new" "See"}] "labels_add" ["x"]
                          "comment" "tidy" "expected_version" 1})
      (ok "claim_issue" {"id" "CLO-2" "assignee" "saqib/wf-1"})
      (ok "release_issue" {"id" "CLO-2" "assignee" "saqib/wf-1"})
      (ok "reassign_issue" {"id" "CLO-2" "from" "none" "to" "saqib/wf-2"})
      (is (= ["CLO-5"] (map #(get % "id") (get (ok "close_issue" {"id" "CLO-2" "resolution" "done" "comment" "ok"})
                                               "newly_unblocked"))))
      (ok "reopen_issue" {"id" "CLO-2" "comment" "again"})
      (ok "get_issue" {"id" "CLO-2" "include" ["events"]})
      (ok "get_issue" {"id" "CLO-5"})
      (ok "update_page" {"id" "CLO-3" "body" "about CLO-2, edited" "expected_version" 1 "summary" "edit"})
      (ok "update_page" {"id" "CLO-4" "parent" nil})
      (ok "get_page" {"id" "CLO-3" "include" ["revisions"]})
      (ok "get_page" {"id" "CLO-3" "revision" 1})
      (ok "diff_page" {"id" "CLO-3"})
      (ok "diff_page" {"id" "CLO-3" "from" 1 "to" 1})
      (ok "restore_page" {"id" "CLO-3" "revision" 1 "summary" "back"})
      (ok "archive_page" {"id" "CLO-4" "summary" "gone"})
      (ok "unarchive_page" {"id" "CLO-4" "summary" "back"})
      (ok "search" {"query" "ready"})
      (ok "search" {"query" "" "limit" 2})
      (ok "search" {"query" "kind:page archived:any sort:updated-desc"})
      (is (= (set (keys schemas)) @seen) "every tool was called"))))

;; ---------------------------------------------------------------- refusals

(deftest refusals-are-tool-results
  (with-ctx [c (fx/ctx)]
    (let [h (client/handler c)]
      (call-tool h "create_page" {"title" "Glossary"})
      (testing "the wrong kind names what the id is"
        (let [{:strs [isError structuredContent content]} (call-tool h "get_issue" {"id" "CLO-1"})]
          (is (true? isError))
          (is (= {"error" "wrong_kind" "id" "CLO-1" "kind" "page"} (dissoc structuredContent "message")))
          (is (str/starts-with? (get-in content [0 "text"]) "wrong_kind: CLO-1 is a page"))))
      (testing "a bad argument is invalid_argument, naming it"
        (let [{:strs [isError structuredContent]} (call-tool h "create_issue" {"title" "x" "colour" "red"})]
          (is (true? isError))
          (is (= ["invalid_argument" "colour"] (map structuredContent ["error" "argument"])))))
      (testing "an unknown tool is a protocol error"
        (is (= -32602 (get-in (modern h "tools/call" {"name" "delete_issue" "arguments" {}}) [:rpc "error" "code"])))))))

;; ---------------------------------------------------------------- the binding and the actor

(deftest the-binding
  (with-ctx [c (assoc (fx/ctx) :project nil)]
    (let [h (client/handler c)]
      (projects/create-project c {:key "OTH" :name "Other"})
      (testing "a bound connection creates in its project"
        (is (= "OTH-1" (get-in (call-tool h "create_issue" {"title" "x"} :query {:project "OTH"})
                               ["structuredContent" "id"]))))
      (testing "an unbound connection must name one"
        (is (= "invalid_argument" (get-in (call-tool h "create_issue" {"title" "x"} :query {}) ["structuredContent" "error"]))))
      (testing "unscoped queries stay in the bound project"
        (is (= "project:OTH" (get-in (call-tool h "search" {"query" ""} :query {:project "OTH"})
                                     ["structuredContent" "effective_query"]))))
      (testing "an unknown project in the binding is refused before any JSON-RPC"
        (let [{:keys [status body]} (modern h "tools/list" {} :query {:project "NOPE"})]
          (is (= 404 status))
          (is (= ["CLO" "OTH"] (:projects (json/read-str body))))))
      (testing "events name the client"
        (is (= "mcp:claude-code" (:actor (peek (:events (events/feed c {:limit 200}))))))))))

;; ---------------------------------------------------------------- 2026-07-28

(deftest the-modern-revision
  (with-ctx [c (fx/ctx)]
    (let [h (client/handler c)]
      (testing "server/discover carries caching hints and the result type"
        (let [r (get-in (modern h "server/discover" {}) [:rpc "result"])]
          (is (= [mcp/modern] (get r "supportedVersions")))
          (is (= "complete" (get r "resultType")))
          (is (pos? (get r "ttlMs")))
          (is (= "public" (get r "cacheScope")))))
      (testing "headers must mirror the body; a missing Mcp-Method is a mismatch"
        (is (= [400 -32020] ((juxt :status #(get-in % [:rpc "error" "code"]))
                             (modern h "tools/list" {} :headers {"mcp-method" "tools/call"}))))
        (is (= [400 -32020] ((juxt :status #(get-in % [:rpc "error" "code"]))
                             (modern h "tools/list" {} :headers {"mcp-method" nil}))))
        (is (= -32020 (get-in (modern h "tools/call" {"name" "search" "arguments" {}} :headers {"mcp-name" "x"})
                              [:rpc "error" "code"]))))
      (testing "an unsupported version names what is supported"
        (let [r (post h {"jsonrpc" "2.0" "id" 1 "method" "tools/list"
                         "params" {"_meta" (assoc (modern-meta "x") (str P "protocolVersion") "2099-01-01")}}
                      :headers {"mcp-protocol-version" "2099-01-01" "mcp-method" "tools/list"})]
          (is (= 400 (:status r)))
          (is (= {"code" -32022 "message" "Unsupported protocol version"
                  "data" {"supported" [mcp/modern] "requested" "2099-01-01"}}
                 (get-in r [:rpc "error"])))))
      (testing "_meta must carry the version and capabilities"
        (is (= -32602 (get-in (post h {"jsonrpc" "2.0" "id" 1 "method" "tools/list"
                                       "params" {"_meta" {(str P "protocolVersion") mcp/modern}}}
                                    :headers {"mcp-protocol-version" mcp/modern "mcp-method" "tools/list"})
                              [:rpc "error" "code"]))))
      (testing "initialize and ping belong to 2025-11-25 only"
        (is (= [404 -32601] ((juxt :status #(get-in % [:rpc "error" "code"])) (modern h "ping" {}))))))))

;; ---------------------------------------------------------------- 2025-11-25

(deftest the-legacy-revision
  (with-ctx [c (fx/ctx)]
    (let [h (client/handler c)
          headers {"mcp-protocol-version" mcp/legacy}
          init (post h {"jsonrpc" "2.0" "id" 1 "method" "initialize"
                        "params" {"protocolVersion" mcp/legacy "capabilities" {}
                                  "clientInfo" {"name" "old-client" "version" "1"}}}
                     :query {:project "CLO"})
          sid (get-in init [:headers "Mcp-Session-Id"])
          with-session (assoc headers "mcp-session-id" sid)]
      (testing "initialize opens a session"
        (is (= mcp/legacy (get-in init [:rpc "result" "protocolVersion"])))
        (is (seq sid)))
      (testing "a notification is accepted with no body"
        (is (= 202 (:status (post h {"jsonrpc" "2.0" "method" "notifications/initialized"} :headers with-session)))))
      (testing "ping and tools work within the session"
        (is (= {} (get-in (post h {"jsonrpc" "2.0" "id" 2 "method" "ping"} :headers with-session) [:rpc "result"])))
        (is (= 16 (count (get-in (post h {"jsonrpc" "2.0" "id" 3 "method" "tools/list"} :headers with-session)
                                 [:rpc "result" "tools"])))))
      (testing "the session's client names the actor"
        (post h {"jsonrpc" "2.0" "id" 4 "method" "tools/call"
                 "params" {"name" "create_issue" "arguments" {"title" "x"}}}
              :headers with-session :query {:project "CLO"})
        (is (= "mcp:old-client" (:actor (peek (:events (events/feed c {:limit 200})))))))
      (testing "requests need a session, and an unknown one is gone"
        (is (= 400 (:status (post h {"jsonrpc" "2.0" "id" 5 "method" "ping"} :headers headers))))
        (is (= 404 (:status (post h {"jsonrpc" "2.0" "id" 5 "method" "ping"}
                                  :headers (assoc headers "mcp-session-id" "nope"))))))
      (testing "DELETE ends the session"
        (is (= 204 (:status (h (client/request :delete "/mcp" :headers with-session)))))
        (is (= 404 (:status (post h {"jsonrpc" "2.0" "id" 6 "method" "ping"} :headers with-session))))))))

(deftest malformed-messages
  (with-ctx [c (fx/ctx)]
    (let [h (client/handler c)]
      (is (= -32700 (get-in (json/read-plain (:body (h (client/request :post "/mcp" :body "{nope"))))
                            ["error" "code"])))
      (is (= -32600 (get-in (post h {"id" 1 "method" "ping"}) [:rpc "error" "code"])))
      (is (= 405 (:status (h (client/request :get "/mcp"))))))))
