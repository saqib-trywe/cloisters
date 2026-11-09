#!/usr/bin/env bb
;; The cloisters CLI. Babashka with bundled libraries only: the daemon validates and renders, the CLI only sends and
;; prints. Install: bbin install cli/cloisters.clj --as cloisters (bbin copies the script, so reinstall after a change).
(ns cloisters.cli
  ;; bbin installs this one file as `cloisters`, so its path cannot follow the namespace
  {:clj-kondo/config '{:linters {:namespace-name-mismatch {:level :off}}}}
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def version "0.1.0-SNAPSHOT")

;; ---------------------------------------------------------------- exits

(defn- fail!
  "Stops the command: message on stderr, exit code. 1 a refusal, 2 a usage error, 3 the daemon is unreachable."
  [code message]
  (throw (ex-info (str message) {::exit code})))

(defn- usage! [message] (fail! 2 message))

(defn- warn [& parts]
  (binding [*out* *err*] (println (str/join " " (cons "cloisters:" parts)))))

(defn- out!
  "Prints s, ending it with a newline."
  [s]
  (let [s (str s)]
    (print s)
    (when-not (str/ends-with? s "\n") (println))
    (flush)))

;; ---------------------------------------------------------------- where things are

(defn- env [k] (not-empty (System/getenv k)))

(defn dirs
  "The config, data and state directories, resolved as the daemon resolves them: CLOISTERS_HOME, else the XDG
  variables (a relative one ignored), else their defaults."
  []
  (if-let [root (env "CLOISTERS_HOME")]
    (let [root (fs/absolutize root)]
      {:config (fs/path root "config") :data (fs/path root "data") :state (fs/path root "state")})
    (let [xdg (fn [var default]
                (let [d (env var)]
                  (fs/path (if (and d (fs/absolute? d)) d (str (fs/home) "/" default)) "cloisters")))]
      {:config (xdg "XDG_CONFIG_HOME" ".config")
       :data (xdg "XDG_DATA_HOME" ".local/share")
       :state (xdg "XDG_STATE_HOME" ".local/state")})))

(defn- config []
  (let [f (fs/path (:config (dirs)) "config.edn")
        cfg (when (fs/exists? f)
              (try (edn/read-string (slurp (fs/file f)))
                   (catch Exception e (usage! (str f " is not readable EDN: " (ex-message e))))))]
    (merge {:port 7227} cfg)))

(defn- port [] (:port (config)))
(defn- base-url [] (str "http://127.0.0.1:" (port)))

(defn- binding-file
  "The nearest .cloisters walking up from the working directory, or nil."
  []
  (loop [dir (fs/absolutize ".")]
    (when dir
      (let [f (fs/path dir ".cloisters")]
        (if (fs/exists? f) f (recur (fs/parent dir)))))))

(defn- bound-project []
  (when-let [f (binding-file)]
    (let [{:keys [project]} (try (edn/read-string (slurp (fs/file f))) (catch Exception _ nil))]
      (or project (usage! (str f " must hold a map naming the project, such as {:project \"CLO\"}"))))))

;; ---------------------------------------------------------------- the service unit

(def ^:private label "cloisters.daemon")

(defn- os []
  (let [n (System/getProperty "os.name")]
    (cond (str/includes? n "Mac") :macos
          (str/includes? n "Linux") :linux
          :else (fail! 1 (str "cloisters runs on macOS and Linux, not " n)))))

(defn- unit-file []
  (case (os)
    :macos (fs/path (fs/home) "Library" "LaunchAgents" (str label ".plist"))
    :linux (fs/path (let [d (env "XDG_CONFIG_HOME")] (if (and d (fs/absolute? d)) d (str (fs/home) "/.config")))
                    "systemd" "user" "cloisters.service")))

(defn- installed? [] (fs/exists? (unit-file)))

;; ---------------------------------------------------------------- talking to the daemon

(def ^:dynamic *json*
  "True when --json was given: JSON in and out, and the error body on stderr."
  false)

(def ^:dynamic *project*
  "The bound project: --project, else the repository's .cloisters."
  nil)

(defn- unreachable! []
  (fail! 3 (str "the cloisters daemon is not running on port " (port) "; "
                (if (installed?)
                  "start it with: cloisters daemon start"
                  "install it with: cloisters daemon install, or run one with: cloisters daemon run"))))

(defn- send!
  "One request to the daemon: the raw response. :json? asks for JSON whatever --json says."
  [method path & {:keys [body query json? timeout]}]
  (try
    (http/request {:method method
                   :uri (str (base-url) path)
                   :query-params (into {} (for [[k v] query :when (some? v)] [k (str v)]))
                   :headers (cond-> {"Accept" (if (or json? *json*) "application/json" "text/plain")
                                     "Cloisters-Client" "cli"}
                              *project* (assoc "Cloisters-Project" *project*)
                              body (assoc "Content-Type" "application/json"))
                   :body (some-> body json/generate-string)
                   :timeout (or timeout 30000)
                   :throw false})
    (catch java.io.IOException _ (unreachable!))))

(defn- refused? [{:keys [status]}] (>= status 400))

(defn- parse [{:keys [body]}] (json/parse-string body true))

(defn- code-of
  "The contract error code of a refusal, as text or JSON."
  [{:keys [body headers] :as response}]
  (if (str/starts-with? (str (get headers "content-type")) "application/json")
    (:error (parse response))
    (first (str/split (str body) #":" 2))))

(defn- refuse! [{:keys [body]}]
  (fail! 1 (str/trimr body)))

(defn- print! [response]
  (if (refused? response) (refuse! response) (out! (:body response))))

(defn- refuse-json!
  "Stops on a refusal asked for as JSON: its body with --json, else as text, led by its code."
  [response]
  (refuse! (if *json*
             response
             (update response :body #(let [{:keys [error message]} (json/parse-string % true)]
                                       (str error ": " message))))))

(defn- data!
  "A JSON request whose refusal stops the command: the parsed body."
  [method path & opts]
  (let [response (apply send! method path :json? true opts)]
    (when (refused? response) (refuse-json! response))
    (parse response)))

(defn- check-daemon!
  "One stderr warning each when the daemon's version or data directory differs from this CLI's."
  []
  (let [response (send! :get "/api/meta" :json? true :timeout 5000)]
    (when-not (refused? response)
      (let [{daemon :version data :data_dir} (parse response)
            mine (str (fs/canonicalize (:data (dirs))))]
        (when (and daemon (not= daemon version))
          (warn "the daemon is version" daemon "and this CLI" (str version ";") "reinstall whichever is older"))
        (when (and data (not= (str (fs/canonicalize data)) mine))
          (warn "the daemon's data directory is" data "but this CLI's is" (str mine ";")
                "daemon export and restore open this CLI's"))))))

(defn- assignee [opts]
  (or (:as opts) (env "CLOISTERS_ASSIGNEE") (System/getProperty "user.name")))

(defn- all-rows
  "Every row the query matches, following the cursor."
  [query]
  (loop [rows [] cursor nil]
    (let [{:keys [results has_more next_cursor]} (data! :get "/api/search" :query {"q" query "cursor" cursor
                                                                                   "limit" 200})
          rows (into rows results)]
      (if has_more (recur rows next_cursor) rows))))

;; ---------------------------------------------------------------- arguments

(def ^:private options
  "Every option: its key, and whether it takes a value or repeats."
  {"--json" {:k :json}
   "--project" {:k :project :arg true}
   "--help" {:k :help} "-h" {:k :help}
   "--events" {:k :events}
   "--revision" {:k :revision :arg true}
   "--body-file" {:k :body-file :arg true}
   "--label" {:k :label :arg true :many true}
   "--parent" {:k :parent :arg true}
   "--blocked-by" {:k :blocked-by :arg true :many true}
   "--title" {:k :title :arg true}
   "--rank" {:k :rank :arg true}
   "--before" {:k :before :arg true}
   "--after" {:k :after :arg true}
   "-m" {:k :m :arg true} "--message" {:k :m :arg true}
   "--as" {:k :as :arg true}
   "--from" {:k :from :arg true}
   "--to" {:k :to :arg true}
   "--query" {:k :query :arg true}
   "--cursor" {:k :cursor :arg true}
   "--limit" {:k :limit :arg true}
   "--new" {:k :new :arg true}
   "--jar" {:k :jar :arg true}
   "--dry-run" {:k :dry-run}
   "-f" {:k :follow} "--follow" {:k :follow}
   "-n" {:k :lines :arg true}})

(defn parse-args
  "argv as {:pos [positional…] :opts {key value}}. An option's value is the next word verbatim, so `--label -x` and
  `-m \"-1\"` work; `--opt=value` too. `--` ends the options."
  [args]
  (loop [[a & more :as args] args pos [] opts {}]
    (cond
      (empty? args) {:pos pos :opts opts}
      (= "--" a) {:pos (into pos more) :opts opts}
      :else
      (let [[flag inline] (if (and (str/starts-with? a "--") (str/includes? a "=")) (str/split a #"=" 2) [a nil])
            {:keys [k arg many] :as spec} (get options flag)]
        (cond
          (and spec arg) (let [v (or inline (first more))]
                           (when (nil? v) (usage! (str flag " needs a value")))
                           (recur (if inline more (rest more)) pos
                                  (if many (update opts k (fnil conj []) v) (assoc opts k v))))
          spec (recur more pos (assoc opts k true))
          (and (str/starts-with? a "-") (not= "-" a)) (usage! (str "unknown option " a "; see cloisters help"))
          :else (recur more (conj pos a) opts))))))

(defn- number! [flag s]
  (or (parse-long (str s)) (usage! (str flag " takes a whole number, not " s))))

(defn- rank-args
  "--rank, --before or --after as the contract's arguments."
  [{:keys [rank before after]}]
  (when (< 1 (count (remove nil? [rank before after])))
    (usage! "give one of --rank, --before and --after"))
  (cond rank {"rank" (or (parse-double rank) (usage! (str "--rank takes a number, not " rank)))}
        before {"rank_before" before}
        after {"rank_after" after}))

(defn- body-of
  "The body from --body-file: a file, or - for standard input."
  [{:keys [body-file]}]
  (cond (nil? body-file) nil
        (= "-" body-file) (slurp *in*)
        (fs/exists? body-file) (slurp (fs/file body-file))
        :else (usage! (str "no file " body-file))))

(defn- signed
  "Values of a repeatable +x/-x option split into [added removed]; a bare value adds."
  [vs]
  [(vec (for [v vs :when (not (str/starts-with? v "-"))] (str/replace v #"^\+" "")))
   (vec (for [v vs :when (str/starts-with? v "-")] (subs v 1)))])

(defn- none-or [v] (when-not (= "none" v) v))

(defn- compact [m]
  (into {} (remove (fn [[_ v]] (or (nil? v) (and (coll? v) (empty? v))))) m))

;; ---------------------------------------------------------------- issues and pages

(defn- kind-of
  "Whether id is an issue or a page, read as JSON: [kind record]."
  [id]
  (let [response (send! :get (str "/api/issues/" id) :json? true)]
    (cond
      (not (refused? response)) [:issue (parse response)]
      (= "wrong_kind" (code-of response)) [:page (data! :get (str "/api/pages/" id))]
      :else (refuse-json! response))))

(defn- show [[id & more] {:keys [events revision]}]
  (when (or (nil? id) (seq more)) (usage! "usage: cloisters show ID [--events] [--revision N]"))
  (let [page (fn [] (print! (send! :get (str "/api/pages/" id)
                                   :query {"revision" (some->> revision (number! "--revision"))
                                           "include" (when events "revisions")})))]
    (if revision
      (page)
      (let [response (send! :get (str "/api/issues/" id) :query {"include" (when events "events")})]
        (if (and (refused? response) (= "wrong_kind" (code-of response)))
          (page)
          (print! response))))))

(defn- search [words {:keys [cursor limit]}]
  (print! (send! :get "/api/search" :query {"q" (str/join " " words) "cursor" cursor
                                            "limit" (some->> limit (number! "--limit"))})))

(defn- new-issue [[title & more] {:keys [label parent blocked-by m] :as opts}]
  (when (or (nil? title) (seq more))
    (usage! "usage: cloisters new \"<title>\" [--body-file f|-] [--label l]… [--parent id] [--blocked-by id]… [-m comment]"))
  (print! (send! :post "/api/issues"
                 :body (compact (merge {"title" title "body" (body-of opts) "labels" label "parent" parent
                                        "blocked_by" blocked-by "comment" m}
                                       (rank-args opts))))))

;; the $EDITOR flow

(defn- edit-in-editor!
  "Opens $VISUAL or $EDITOR on text in a temporary file named for id: [path edited-text], or nil when the editor
  failed, keeping the file."
  [id text]
  (let [f (fs/create-temp-file {:prefix (str id "-") :suffix ".md"})
        editor (or (env "VISUAL") (env "EDITOR") "vi")]
    (spit (fs/file f) text)
    (let [{:keys [exit]} @(p/process {:in :inherit :out :inherit :err :inherit}
                                     "sh" "-c" (str editor " \"$1\"") "sh" (str f))]
      (when-not (zero? exit)
        (fail! 1 (str "the editor exited " exit "; nothing was saved. The text is in " f)))
      [f (slurp (fs/file f))])))

(defn- save-edit!
  "PATCHes changes to id's route; on refusal keeps the edited file at path, naming it."
  [route id changes path]
  (let [response (send! :patch (str "/api/" route "/" id) :body changes)]
    (if (refused? response)
      (fail! 1 (str (str/trimr (:body response)) (when path (str "\nYour edit is kept in " path))))
      (do (when path (fs/delete-if-exists path))
          (out! (:body response))))))

(defn- edit [[id & more] {:keys [title label parent blocked-by m] :as opts}]
  (when (or (nil? id) (seq more)) (usage! "usage: cloisters edit ID [options]; see cloisters help"))
  (let [[kind record] (kind-of id)
        route (if (= :page kind) "pages" "issues")
        moves (merge (when parent {"parent" (none-or parent)}) (rank-args opts))
        _ (when (= :page kind)
            (when (or label blocked-by) (usage! "--label and --blocked-by apply to issues"))
            (when (seq moves) (usage! "move a page with: cloisters page move ID --parent id|none")))
        [labels-add labels-remove] (signed label)
        [blockers-add blockers-remove] (signed blocked-by)
        fields (compact (merge {"title" title "labels_add" labels-add "labels_remove" labels-remove
                                "blocked_by_add" blockers-add "blocked_by_remove" blockers-remove}
                               moves))
        note (if (= :page kind) "summary" "comment")
        file-body (body-of opts)]
    (cond
      file-body (save-edit! route id (compact (merge fields {"body" file-body note m})) nil)
      (and (= :issue kind) (seq fields)) (save-edit! route id (compact (merge fields {note m})) nil)
      :else
      (let [[path text] (edit-in-editor! id (:body record ""))
            changed (not= text (:body record ""))
            changes (compact (merge fields
                                    (when changed {"body" text "expected_version" (:version record)})
                                    {note m}))]
        (if (empty? changes)
          (do (fs/delete-if-exists path) (out! (str id ": no change")))
          (save-edit! route id changes (when changed path)))))))

;; the intent actions

(defn- action [id path body]
  (print! (send! :post (str "/api/issues/" id "/" path) :body (compact body))))

(defn- claim [[id & more] {:keys [m] :as opts}]
  (when (or (nil? id) (seq more)) (usage! "usage: cloisters claim ID [--as name] [-m comment]"))
  (action id "claim" {"assignee" (assignee opts) "comment" m}))

(defn- release [[id & more] {:keys [m] :as opts}]
  (when (or (nil? id) (seq more)) (usage! "usage: cloisters release ID [--as name] [-m comment]"))
  (action id "release" {"assignee" (assignee opts) "comment" m}))

(defn- reassign [[id & more] {:keys [from to m]}]
  (when (or (nil? id) (seq more) (nil? from) (nil? to))
    (usage! "usage: cloisters reassign ID --from holder|none --to holder|none [-m comment]"))
  (action id "reassign" {"from" from "to" to "comment" m}))

(defn- depth
  "How many of row's ancestors are among by-id."
  [by-id row]
  (loop [n 0 parent (:parent row) seen #{}]
    (if (and parent (by-id parent) (not (seen parent)))
      (recur (inc n) (:parent (by-id parent)) (conj seen parent))
      n)))

(defn- bulk-close
  "Closes every open issue the query matches, leaves first, one ordinary close each. Not atomic: it stops at the
  first refusal, having said what it closed."
  [query resolution m]
  (let [rows (filter #(and (= "issue" (:kind %)) (= "open" (:status %))) (all-rows query))
        by-id (into {} (map (juxt :id identity)) rows)
        ordered (sort-by #(- (depth by-id %)) rows)]
    (when (empty? ordered) (out! "(no open issues match)"))
    (doseq [{:keys [id]} ordered]
      (let [response (send! :post (str "/api/issues/" id "/close") :body (compact {"resolution" resolution
                                                                                   "comment" m}))]
        (if (refused? response)
          (fail! 1 (str (str/trimr (:body response)) "\nStopped at " id "; the issues above were closed."))
          (out! (:body response)))))))

(defn- close [[a b & more] {:keys [query m]}]
  (if query
    (do (when (or (nil? a) b) (usage! "usage: cloisters close --query '<query>' done|dropped -m \"…\""))
        (bulk-close query a m))
    (do (when (or (nil? a) (nil? b) (seq more)) (usage! "usage: cloisters close ID done|dropped -m \"…\""))
        (action a "close" {"resolution" b "comment" m}))))

(defn- reopen [[id & more] {:keys [m]}]
  (when (or (nil? id) (seq more)) (usage! "usage: cloisters reopen ID -m \"…\""))
  (action id "reopen" {"comment" m}))

;; ---------------------------------------------------------------- pages

(defn- page-new [[title & more] {:keys [parent m] :as opts}]
  (when (or (nil? title) (seq more))
    (usage! "usage: cloisters page new \"<title>\" [--parent id] [--body-file f|-] [-m summary]"))
  (print! (send! :post "/api/pages"
                 :body (compact (merge {"title" title "body" (body-of opts) "parent" parent "summary" m}
                                       (rank-args opts))))))

(defn tree-lines
  "Page rows as an indented tree: each under its parent, siblings by rank then number. A row whose parent is not
  among rows is a root."
  [rows]
  (let [by-id (into {} (map (juxt :id identity)) rows)
        number #(parse-long (re-find #"\d+$" (:id %)))
        order #(sort-by (juxt :rank number) %)
        children (group-by #(when (by-id (:parent %)) (:parent %)) rows)
        walk (fn walk [row indent]
               (cons (str indent (:id row) "  " (:title row) (when (:archived row) "  (archived)"))
                     (mapcat #(walk % (str indent "  ")) (order (children (:id row))))))]
    (mapcat #(walk % "") (order (children nil)))))

(defn- page-tree [[scope & more] _]
  (when (seq more) (usage! "usage: cloisters page tree [KEY|ID]"))
  (let [subtree? (and scope (re-matches #".+-\d+" scope))
        rows (cond subtree? (into [(data! :get (str "/api/pages/" scope))] (all-rows (str "kind:page under:" scope)))
                   scope (all-rows (str "kind:page project:" scope))
                   :else (all-rows "kind:page"))]
    (if *json*
      (out! (json/generate-string rows))
      (out! (if (seq rows) (str/join "\n" (tree-lines rows)) "(no pages)")))))

(defn- page-history [[id & more] _]
  (when (or (nil? id) (seq more)) (usage! "usage: cloisters page history ID"))
  (print! (send! :get (str "/api/pages/" id "/revisions"))))

(defn- page-diff [[id from to & more] _]
  (when (or (nil? id) (seq more)) (usage! "usage: cloisters page diff ID [FROM TO]"))
  (let [[from to] (if to [from to] [nil from])]
    (print! (send! :get (str "/api/pages/" id "/diff")
                   :query {"from" (some->> from (number! "FROM")) "to" (some->> to (number! "TO"))}))))

(defn- page-move [[id & more] {:keys [parent] :as opts}]
  (let [changes (merge (when parent {"parent" (none-or parent)}) (rank-args opts))]
    (when (or (nil? id) (seq more) (empty? changes))
      (usage! "usage: cloisters page move ID --parent id|none [--rank n|--before id|--after id]"))
    (print! (send! :patch (str "/api/pages/" id) :body changes))))

(defn- page-action [path]
  (fn [[id & more] {:keys [m]}]
    (when (or (nil? id) (seq more)) (usage! (str "usage: cloisters page " path " ID -m \"…\"")))
    (print! (send! :post (str "/api/pages/" id "/" path) :body (compact {"summary" m})))))

(defn- page-restore [[id revision & more] {:keys [m]}]
  (when (or (nil? id) (nil? revision) (seq more)) (usage! "usage: cloisters page restore ID REVISION [-m summary]"))
  (print! (send! :post (str "/api/pages/" id "/restore")
                 :body (compact {"revision" (number! "REVISION" revision) "summary" m}))))

;; ---------------------------------------------------------------- projects and binding

(defn- project-new [[k project-name & more] _]
  (when (or (nil? k) (nil? project-name) (seq more)) (usage! "usage: cloisters project new KEY \"Name\""))
  (print! (send! :post "/api/projects" :body {"key" k "name" project-name})))

(defn- project-list [args _]
  (when (seq args) (usage! "usage: cloisters project list"))
  (print! (send! :get "/api/projects")))

(defn- mcp-url [k] (str (base-url) "/mcp?project=" k))

(defn- init [[k & more] opts]
  (when (or (nil? k) (seq more)) (usage! "usage: cloisters init KEY [--new \"Name\"]"))
  (let [exists (some #(= k (:key %)) (data! :get "/api/projects"))]
    (cond
      exists nil
      (:new opts) (data! :post "/api/projects" :body {"key" k "name" (:new opts)})
      :else (fail! 1 (str "unknown_project: there is no project " k ". Create it with: cloisters project new " k
                          " \"Name\", or create and bind in one step: cloisters init " k " --new \"Name\"")))
    (spit ".cloisters" (str (pr-str {:project k}) "\n"))
    (let [f (io/file ".mcp.json")
          current (when (.exists f)
                    (try (json/parse-string (slurp f))
                         (catch Exception _ (fail! 1 ".mcp.json is not valid JSON; fix it and run init again"))))]
      (spit f (str (json/generate-string (assoc-in current ["mcpServers" "cloisters"] {"type" "http" "url" (mcp-url k)})
                                         {:pretty true})
                   "\n")))
    (out! (str (if exists "bound" "created and bound") " this directory to " k ":\n"
               "  .cloisters   {:project \"" k "\"}, read by the CLI and `cloisters mcp`\n"
               "  .mcp.json    the `cloisters` MCP server at " (mcp-url k) " for Claude Code\n"
               "Commit both. Clients that only start stdio servers can run `cloisters mcp` from this directory."))))

;; ---------------------------------------------------------------- the stdio MCP proxy

(def ^:private P "io.modelcontextprotocol/")

(defn- protocol-headers
  "The headers a 2026-07-28 message mirrors from its body, which a stdio client cannot send: nil for 2025-11-25,
  whose version and session come back from the daemon."
  [msg]
  (let [params (get msg "params")
        v (when (map? params) (get-in params ["_meta" (str P "protocolVersion")]))]
    (when v
      (cond-> {"MCP-Protocol-Version" v "Mcp-Method" (str (get msg "method"))}
        (= "tools/call" (get msg "method")) (assoc "Mcp-Name" (str (get params "name")))))))

(defn- post-mcp
  "POSTs one line to the daemon's /mcp, or nil when it cannot be reached."
  [url line headers]
  (try (http/post url {:headers (merge {"Content-Type" "application/json"
                                        "Accept" "application/json, text/event-stream"}
                                       headers)
                       :body line
                       :throw false
                       :timeout 120000})
       (catch java.io.IOException _ nil)))

(defn- session-lost?
  "Whether the daemon no longer knows the 2025-11-25 session, as after a restart."
  [{:keys [status body]}]
  (and (= 404 status)
       (= -32600 (get-in (try (json/parse-string body) (catch Exception _ nil)) ["error" "code"]))))

(defn mcp
  "Forwards newline-delimited JSON-RPC from stdin to the daemon's /mcp with the repository's binding, writing each
  response body back as a line; a 202 writes nothing. Framing only, with two exceptions: it mirrors a 2026-07-28
  message's protocol fields into the headers HTTP requires, and while the daemon is down it answers each request with
  an error naming the fix (extracting only the id). After a daemon restart it replays the client's initialize, so a
  2025-11-25 client keeps working without reconnecting."
  [args _]
  (when (seq args) (usage! "usage: cloisters mcp"))
  (let [url (str (base-url) "/mcp" (when *project* (str "?project=" *project*)))
        down (fn [id] (json/generate-string
                       {"jsonrpc" "2.0" "id" id
                        "error" {"code" -32000
                                 "message" (try (unreachable!) (catch clojure.lang.ExceptionInfo e (ex-message e)))}}))
        state (atom {})]
    (check-daemon!)
    (doseq [line (line-seq (io/reader *in*))
            :when (not (str/blank? line))]
      (let [msg (try (json/parse-string line) (catch Exception _ nil))
            id (when (map? msg) (get msg "id"))
            request? (and (map? msg) (contains? msg "id"))
            initialize? (and (map? msg) (= "initialize" (get msg "method")))
            headers #(merge (when-let [sid (:session @state)] (when-not initialize? {"Mcp-Session-Id" sid}))
                            (when-let [v (:version @state)] {"MCP-Protocol-Version" v})
                            (when (map? msg) (protocol-headers msg)))
            forward #(post-mcp url line (headers))
            remember! (fn [response]
                        (when-let [sid (get-in response [:headers "mcp-session-id"])]
                          (swap! state assoc :session sid))
                        response)
            reinitialize! (fn []
                            (when-let [init (:initialize @state)]
                              (swap! state dissoc :session)
                              (when-let [r (post-mcp url init {})]
                                (remember! r)
                                (post-mcp url (json/generate-string {"jsonrpc" "2.0"
                                                                     "method" "notifications/initialized"})
                                          {"Mcp-Session-Id" (:session @state)}))))
            response (some-> (forward) remember!)
            response (if (and response (:session @state) (not initialize?) (session-lost? response))
                       (do (reinitialize!) (some-> (forward) remember!))
                       response)]
        (when initialize?
          (swap! state assoc :initialize line)
          (when-let [v (some-> response :body (json/parse-string) (get-in ["result" "protocolVersion"]))]
            (swap! state assoc :version v)))
        (cond
          (nil? response) (when request? (out! (down id)))
          (= 202 (:status response)) nil
          (str/blank? (:body response)) nil
          :else (out! (str/trim (:body response))))))))

;; ---------------------------------------------------------------- the daemon

(defn- lib [] (fs/path (:data (dirs)) "lib"))
(defn- installed-jar [] (fs/path (lib) "cloisters-daemon.jar"))
(defn- install-record [] (fs/path (lib) "install.edn"))
(defn- log-file [] (fs/path (:state (dirs)) "logs" "daemon.log"))
(defn- lock-file [] (fs/path (:data (dirs)) "daemon.lock"))

(defn- installed []
  (when (fs/exists? (install-record)) (edn/read-string (slurp (fs/file (install-record))))))

(defn- java-home
  "What the java at path reports: {:java <java.home>/bin/java :version <feature version>}."
  [path]
  (let [{:keys [err exit]} (try (p/sh path "-XshowSettings:properties" "-version")
                                (catch Exception _ {:exit -1}))
        prop #(second (re-find (re-pattern (str "(?m)^\\s*" % " = (.*)$")) (str err)))]
    (when (and (zero? exit) (prop "java.home"))
      {:java (str (fs/path (prop "java.home") "bin" "java"))
       :version (parse-long (str (prop "java.specification.version")))})))

(defn- find-java
  "The JDK to run the daemon: $JAVA_HOME's, else the one on PATH, resolved to its real location so a version
  manager's shim never ends up in the service unit. Refuses one older than 21."
  []
  (let [candidate (or (some-> (env "JAVA_HOME") (fs/path "bin" "java") str) (some-> (fs/which "java") str))
        info (some-> candidate java-home)]
    (cond (nil? info) (fail! 1 "no JDK found: set JAVA_HOME or put java on PATH (JDK 21 or later)")
          (< (:version info) 21) (fail! 1 (str (:java info) " is Java " (:version info) "; the daemon needs 21 or later"))
          :else (:java info))))

(defn- runtime
  "[java jar] for running the daemon jar: --jar and the JDK on PATH, else what install recorded."
  [opts]
  (let [rec (installed)]
    (cond
      (:jar opts) (if (fs/exists? (:jar opts))
                    [(or (:java rec) (find-java)) (str (fs/absolutize (:jar opts)))]
                    (usage! (str "no daemon jar at " (:jar opts))))
      rec [(:java rec) (:jar rec)]
      :else (fail! 1 (str "no daemon installed in " (lib) ": run cloisters daemon install, or pass --jar "
                          "target/cloisters-daemon.jar")))))

(defn- jar-command [opts & args]
  (let [[java jar] (runtime opts)]
    (into [java "--enable-native-access=ALL-UNNAMED" "-jar" jar] args)))

(defn- holder
  "The live daemon the lock records, as {:pid :port :version}, or nil."
  []
  (let [f (lock-file)
        rec (when (fs/exists? f) (try (edn/read-string (slurp (fs/file f))) (catch Exception _ nil)))]
    (when-let [pid (:pid rec)]
      (when (some-> (java.lang.ProcessHandle/of pid) (.orElse nil) (.isAlive)) rec))))

(defn- answering []
  (try (let [r (http/get (str (base-url) "/api/meta") {:headers {"Accept" "application/json"} :throw false
                                                       :timeout 2000})]
         (when (= 200 (:status r)) (json/parse-string (:body r) true)))
       (catch Exception _ nil)))

(defn- wait-until [pred seconds]
  (loop [n (* 10 seconds)]
    (or (pred) (when (pos? n) (Thread/sleep 100) (recur (dec n))))))

(defn- uid [] (str/trim (:out (p/sh "id" "-u"))))

(defn- sh! [& args]
  (let [{:keys [exit err out]} (apply p/sh args)]
    (when-not (zero? exit)
      (fail! 1 (str (str/join " " args) " failed: " (str/trim (str err out)))))))

(defn- service-loaded? []
  (case (os)
    :macos (zero? (:exit (p/sh "launchctl" "print" (str "gui/" (uid) "/" label))))
    :linux (zero? (:exit (p/sh "systemctl" "--user" "is-active" "--quiet" "cloisters.service")))))

(defn- unit-env
  "The locations the service needs, which launchd and systemd do not inherit from the shell."
  []
  (if-let [home (env "CLOISTERS_HOME")]
    {"CLOISTERS_HOME" (str (fs/absolutize home))}
    (let [{:keys [config data state]} (dirs)]
      {"XDG_CONFIG_HOME" (str (fs/parent config))
       "XDG_DATA_HOME" (str (fs/parent data))
       "XDG_STATE_HOME" (str (fs/parent state))})))

(defn- xml [s] (-> (str s) (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;")))

(defn unit
  "The service unit's text for os, running java on jar with environment e, logging to log."
  [os java jar e log]
  (case os
    :macos
    (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
         "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
         "<plist version=\"1.0\">\n<dict>\n"
         "  <key>Label</key><string>" label "</string>\n"
         "  <key>ProgramArguments</key>\n  <array>\n"
         (str/join (for [a [java "--enable-native-access=ALL-UNNAMED" "-jar" jar]]
                     (str "    <string>" (xml a) "</string>\n")))
         "  </array>\n"
         "  <key>EnvironmentVariables</key>\n  <dict>\n"
         (str/join (for [[k v] (sort e)] (str "    <key>" k "</key><string>" (xml v) "</string>\n")))
         "  </dict>\n"
         "  <key>RunAtLoad</key><true/>\n"
         "  <key>KeepAlive</key><dict><key>SuccessfulExit</key><false/></dict>\n"
         "  <key>StandardOutPath</key><string>" (xml log) "</string>\n"
         "  <key>StandardErrorPath</key><string>" (xml log) "</string>\n"
         "</dict>\n</plist>\n")
    :linux
    (str "[Unit]\nDescription=cloisters daemon\n\n"
         "[Service]\n"
         "ExecStart=\"" java "\" --enable-native-access=ALL-UNNAMED -jar \"" jar "\"\n"
         (str/join (for [[k v] (sort e)] (str "Environment=\"" k "=" v "\"\n")))
         "Restart=on-failure\n"
         "StandardOutput=append:" log "\n"
         "StandardError=append:" log "\n\n"
         "[Install]\nWantedBy=default.target\n")))

(defn- service! [verb]
  (case (os)
    :macos (let [domain (str "gui/" (uid))
                 target (str domain "/" label)]
             (case verb
               ;; a loaded job whose process has exited is started again with kickstart
               :start (if (service-loaded?)
                        (sh! "launchctl" "kickstart" target)
                        (sh! "launchctl" "bootstrap" domain (str (unit-file))))
               :stop (sh! "launchctl" "bootout" target)))
    :linux (case verb
             :start (sh! "systemctl" "--user" "start" "cloisters.service")
             :stop (sh! "systemctl" "--user" "stop" "cloisters.service"))))

(defn- status-line []
  (if-let [{:keys [version data_dir]} (answering)]
    (str "running: version " version " on " (base-url) (when-let [{:keys [pid]} (holder)] (str ", pid " pid))
         "\ndata: " data_dir)
    (if-let [{:keys [pid]} (holder)]
      (str "starting or stuck: pid " pid " holds the lock, but nothing answers on " (base-url))
      (str "not running: nothing answers on " (base-url)))))

(defn- wait-for-start []
  (if (wait-until answering 30)
    (out! (status-line))
    (fail! 1 (str "the daemon did not answer on " (base-url) " within 30 s; see: cloisters daemon logs"))))

(defn- stop-daemon
  "Stops the daemon of this home: through the service manager when it runs one, then by the pid in its lock."
  []
  (let [was (or (holder) (answering))]
    (when (and (installed?) (service-loaded?)) (service! :stop))
    (when-let [{:keys [pid]} (holder)]
      (some-> (java.lang.ProcessHandle/of pid) (.orElse nil) (.destroy)))
    (if (wait-until #(not (holder)) 15)
      (out! (if was "stopped" (str "not running: nothing answers on " (base-url))))
      (fail! 1 (str "the daemon (pid " (:pid (holder)) ") did not stop within 15 s")))))

(defn- daemon-install [args {:keys [jar dry-run]}]
  (when (seq args) (usage! "usage: cloisters daemon install [--jar path] [--dry-run]"))
  (let [source (or jar "target/cloisters-daemon.jar")
        _ (when-not (fs/exists? source)
            (usage! (str "no daemon jar at " source ": build one with `clojure -T:build uber` in the cloisters "
                         "checkout, or pass --jar")))
        java (find-java)
        target (installed-jar)
        text (unit (os) java (str target) (unit-env) (str (log-file)))]
    (if dry-run
      (out! (str "would copy " source " to " target "\nwould write " (unit-file) ":\n\n" text))
      (do
        (when-let [{:keys [pid]} (and (not (and (installed?) (service-loaded?))) (holder))]
          (fail! 1 (str "a daemon not run by the service holds this home (pid " pid "); stop it first: "
                        "cloisters daemon stop")))
        (when (and (installed?) (service-loaded?)) (service! :stop) (wait-until #(not (holder)) 15))
        (fs/create-dirs (lib))
        (fs/create-dirs (fs/parent (log-file)))
        (let [tmp (fs/path (lib) "cloisters-daemon.jar.installing")]
          (fs/copy source tmp {:replace-existing true})
          (fs/move tmp target {:replace-existing true :atomic-move true}))
        (spit (fs/file (install-record)) (str (pr-str {:java java :jar (str target)}) "\n"))
        (fs/create-dirs (fs/parent (unit-file)))
        (spit (fs/file (unit-file)) text)
        (when (= :linux (os))
          (sh! "systemctl" "--user" "daemon-reload")
          (sh! "systemctl" "--user" "enable" "cloisters.service"))
        (out! (str "installed " target ", run by " java "\nservice: " (unit-file)))
        (service! :start)
        (wait-for-start)))))

(defn- daemon-uninstall [args _]
  (when (seq args) (usage! "usage: cloisters daemon uninstall"))
  (if-not (installed?)
    (out! "no service installed")
    (do (when (service-loaded?) (service! :stop))
        (when (= :linux (os)) (p/sh "systemctl" "--user" "disable" "cloisters.service"))
        (fs/delete (unit-file))
        (when (= :linux (os)) (p/sh "systemctl" "--user" "daemon-reload"))
        (out! (str "removed " (unit-file) "; the store, snapshots and jar in " (:data (dirs)) " stay")))))

(defn- daemon-start [args _]
  (when (seq args) (usage! "usage: cloisters daemon start"))
  (cond (answering) (out! (status-line))
        (not (installed?)) (fail! 1 (str "no service installed: run cloisters daemon install, "
                                         "or run one in this terminal with: cloisters daemon run"))
        :else (do (service! :start)
                  (wait-for-start))))

(defn- daemon-stop [args _]
  (when (seq args) (usage! "usage: cloisters daemon stop"))
  (stop-daemon))

(defn- daemon-restart [args opts]
  (when (seq args) (usage! "usage: cloisters daemon restart"))
  (when-not (installed?) (fail! 1 "no service installed: run cloisters daemon install"))
  (stop-daemon)
  (daemon-start [] opts))

(defn- daemon-status [args _]
  (when (seq args) (usage! "usage: cloisters daemon status"))
  (let [up (answering)]
    (out! (str (status-line)
               "\nservice: " (cond (not (installed?)) "not installed"
                                   (service-loaded?) (str "installed and loaded (" (unit-file) ")")
                                   :else (str "installed, not loaded (" (unit-file) ")"))
               (when-let [{:keys [jar]} (installed)] (str "\njar: " jar))))
    (when-not up (fail! 3 ""))))

(defn- daemon-run [args opts]
  (when (seq args) (usage! "usage: cloisters daemon run [--jar path]"))
  (apply p/exec (jar-command opts)))

(defn- daemon-logs [args {:keys [follow lines]}]
  (when (seq args) (usage! "usage: cloisters daemon logs [-f] [-n lines]"))
  (when-not (fs/exists? (log-file)) (fail! 1 (str "no log yet at " (log-file))))
  (apply p/exec (concat ["tail" "-n" (str (if lines (number! "-n" lines) 50))] (when follow ["-f"])
                        [(str (log-file))])))

(defn- jar-operation [op usage]
  (fn [[arg & more] opts]
    (when (or (nil? arg) (seq more)) (usage! usage))
    (let [{:keys [exit]} @(p/process (apply jar-command opts [op arg]) {:inherit true})]
      (when-not (zero? exit) (fail! exit "")))))

;; ---------------------------------------------------------------- the command table

(def ^:private globals #{:json :project :help})

(defn- flag-name [k]
  (first (sort-by count (for [[flag spec] options :when (= k (:k spec))] flag))))

(def commands
  "Each command: what it runs, the options it takes beyond --json, --project and --help, whether it talks to the
  daemon's API (and so checks its version first), and whether it ignores the binding (so a .cloisters naming a
  project that does not exist yet never blocks creating or binding one)."
  {"search" {:run search :opts #{:cursor :limit} :api true}
   "ls" {:run search :opts #{:cursor :limit} :api true}
   "show" {:run show :opts #{:events :revision} :api true}
   "new" {:run new-issue :opts #{:body-file :label :parent :blocked-by :m :rank :before :after} :api true}
   "edit" {:run edit :opts #{:title :body-file :label :parent :blocked-by :rank :before :after :m} :api true}
   "claim" {:run claim :opts #{:as :m} :api true}
   "release" {:run release :opts #{:as :m} :api true}
   "reassign" {:run reassign :opts #{:from :to :m} :api true}
   "close" {:run close :opts #{:query :m} :api true}
   "reopen" {:run reopen :opts #{:m} :api true}
   "init" {:run init :opts #{:new} :api true :unbound true}
   "mcp" {:run mcp :opts #{}}
   "project" {:sub {"new" {:run project-new :opts #{} :api true :unbound true}
                    "list" {:run project-list :opts #{} :api true :unbound true}}}
   "page" {:sub {"new" {:run page-new :opts #{:parent :body-file :m :rank :before :after} :api true}
                 "tree" {:run page-tree :opts #{} :api true}
                 "history" {:run page-history :opts #{} :api true}
                 "diff" {:run page-diff :opts #{} :api true}
                 "move" {:run page-move :opts #{:parent :rank :before :after} :api true}
                 "archive" {:run (page-action "archive") :opts #{:m} :api true}
                 "unarchive" {:run (page-action "unarchive") :opts #{:m} :api true}
                 "restore" {:run page-restore :opts #{:m} :api true}}}
   "daemon" {:sub {"install" {:run daemon-install :opts #{:jar :dry-run}}
                   "uninstall" {:run daemon-uninstall :opts #{}}
                   "run" {:run daemon-run :opts #{:jar}}
                   "start" {:run daemon-start :opts #{}}
                   "stop" {:run daemon-stop :opts #{}}
                   "restart" {:run daemon-restart :opts #{}}
                   "status" {:run daemon-status :opts #{}}
                   "logs" {:run daemon-logs :opts #{:follow :lines}}
                   "export" {:run (jar-operation "export" "usage: cloisters daemon export <file> [--jar path]")
                             :opts #{:jar}}
                   "restore" {:run (jar-operation "restore" "usage: cloisters daemon restore <snapshot> [--jar path]")
                              :opts #{:jar}}}}})

(def help
  "usage: cloisters <command> [options]

Issues and pages share ids; output is the daemon's text, or JSON with --json.
  search [QUERY…] [--cursor c] [--limit n]       one search over issues and pages (alias: ls)
  show ID [--events] [--revision n]              an issue or a page (--events: its history or revisions)
  edit ID                                        $EDITOR on the body, saved against the version read
       [--title t] [-m comment|summary] [--body-file f|-]
       [--label +a|-b]… [--blocked-by +ID|-ID]… [--parent ID|none] [--rank n|--before ID|--after ID]   (issues)

Issues
  new \"<title>\" [--body-file f|-] [--label l]… [--parent ID] [--blocked-by ID]… [-m comment]
  claim|release ID [--as name] [-m comment]     --as defaults to $CLOISTERS_ASSIGNEE, then your username
  reassign ID --from holder|none --to holder|none [-m comment]
  close ID done|dropped -m \"…\"
  close --query '<query>' done|dropped -m \"…\"   every open match, leaves first; stops at the first refusal
  reopen ID -m \"…\"

Pages
  page new \"<title>\" [--parent ID] [--body-file f|-] [-m summary]
  page tree [KEY|ID]                             the page tree, or one page's subtree
  page history ID                                revisions with their summaries
  page diff ID [FROM TO | TO]                    defaults to the previous revision
  page move ID --parent ID|none [--rank n|--before ID|--after ID]
  page archive|unarchive ID -m \"…\"
  page restore ID REVISION [-m summary]

Projects and binding
  project new KEY \"Name\"     project list
  init KEY [--new \"Name\"]    bind this directory: writes .cloisters and the .mcp.json entry
  mcp                        stdio MCP server, proxied to the daemon with this directory's binding

The daemon
  daemon install [--jar path] [--dry-run]    copy the jar, write and start the login service
  daemon start|stop|restart|status|uninstall
  daemon run [--jar path]                    in the foreground
  daemon logs [-f] [-n lines]
  daemon export <file>                       JSON Lines, offline, whether or not the daemon runs
  daemon restore <snapshot>                  with the daemon stopped; the current store is moved aside

Everywhere: --project KEY (else the nearest .cloisters), --json, --help.
Exit codes: 0 done, 1 refused (its code first on stderr), 2 usage, 3 daemon unreachable.")

(defn- resolve-command
  "[spec args] for the positional words, or nil."
  [[cmd sub & more :as pos]]
  (when-let [spec (get commands cmd)]
    (if-let [subs (:sub spec)]
      (when-let [s (get subs sub)] [s more])
      [spec (rest pos)])))

(defn run
  "Runs argv: returns normally on success, throws ex-info with ::exit otherwise."
  [args]
  (let [{:keys [pos opts]} (parse-args args)
        [cmd sub] pos]
    (cond
      (or (= "help" cmd) (and (:help opts) (nil? cmd))) (out! help)
      (contains? #{"version" "--version"} cmd) (out! (str "cloisters " version))
      (nil? cmd) (usage! help)
      :else
      (let [[spec rest-args] (or (resolve-command pos)
                                 (usage! (if (:sub (get commands cmd))
                                           (str "unknown command: cloisters " cmd (when sub (str " " sub))
                                                "; see cloisters help")
                                           (str "unknown command: " cmd "; see cloisters help"))))
            extra (remove (into globals (:opts spec)) (keys opts))]
        (cond
          (:help opts) (out! help)
          (seq extra) (usage! (str (str/join " " (take (if (:sub (get commands cmd)) 2 1) pos)) " does not take "
                                   (str/join ", " (map flag-name extra))))
          :else
          (binding [*json* (boolean (:json opts))
                    *project* (when-not (:unbound spec) (or (:project opts) (bound-project)))]
            (when (:api spec) (check-daemon!))
            ((:run spec) (vec rest-args) opts)))))))

(defn -main [& args]
  (try
    (run args)
    (catch clojure.lang.ExceptionInfo e
      (if-let [code (::exit (ex-data e))]
        (do (when-not (str/blank? (ex-message e))
              (binding [*out* *err*] (println (ex-message e))))
            (System/exit code))
        (throw e)))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
