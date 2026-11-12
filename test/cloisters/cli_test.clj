(ns cloisters.cli-test
  "The babashka CLI, run as a process (`bb cli/cloisters.clj`) against a daemon served in this JVM on a free port,
  under a temporary CLOISTERS_HOME: exit codes, the binding, every command family, the $EDITOR flow, bulk close and
  the stdio MCP proxy. The daemon subcommands are exercised against the uberjar by scripts/cli-gate.sh."
  (:require [cloisters.fixtures :as fx]
            [cloisters.http :as http]
            [cloisters.json :as json]
            [cloisters.paths :as paths]
            [cloisters.projects :as projects]
            [cloisters.store :as store]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import (java.io File)
           (java.util.concurrent TimeUnit)))

(def cli-version "0.1.0-SNAPSHOT")
(def script (.getAbsolutePath (io/file "cli/cloisters.clj")))

(defn- run-cli
  "Runs the CLI in dir under home: {:exit :out :err}. Options: :in (standard input), :env (extra variables)."
  [^File home ^File dir args & {:keys [in env]}]
  (let [pb (ProcessBuilder. ^java.util.List (into ["bb" script] args))
        e (.environment pb)]
    (doseq [k ["VISUAL" "EDITOR" "CLOISTERS_ASSIGNEE" "CLOISTERS_HOME"]] (.remove e k))
    (.put e "CLOISTERS_HOME" (str home))
    (doseq [[k v] env] (.put e k v))
    (.directory pb dir)
    (let [p (.start pb)
          out (future (slurp (.getInputStream p)))
          err (future (slurp (.getErrorStream p)))]
      (with-open [w (io/writer (.getOutputStream p))]
        (when in (.write w ^String in)))
      (when-not (.waitFor p 60 TimeUnit/SECONDS)
        (.destroyForcibly p)
        (throw (ex-info "the CLI did not finish in 60 s" {:args args})))
      {:exit (.exitValue p) :out @out :err @err})))

(defn- daemon
  "A home configured for a free port, its store with project CLO, and a function starting a server over it with
  version v (returning a stop function)."
  []
  (let [home (fx/tmp-dir)
        port (fx/configure! home (fx/free-port))
        st (fx/open home)
        ctx {:store st :dirs (paths/dirs {"CLOISTERS_HOME" (str home)} "/nowhere")}]
    (projects/create-project (assoc ctx :actor "test") {:key "CLO" :name "Cloisters"})
    {:home home
     :port port
     :store st
     :start (fn [& [v]]
              (let [server (http/start! (assoc ctx :version (or v cli-version)) port)]
                #(http/stop! server)))}))

(defn- with-daemon
  "Calls f with a running daemon (see daemon), stopping it and closing its store after."
  [f]
  (let [d (daemon)
        stop ((:start d))]
    (try (f d) (finally (stop) (store/close (:store d))))))

(defn- bound-dir
  "A directory bound to CLO."
  ^File []
  (let [d (fx/tmp-dir)]
    (spit (io/file d ".cloisters") "{:project \"CLO\"}\n")
    d))

(defn- script-file
  "An executable shell script holding body."
  [body]
  (let [f (io/file (fx/tmp-dir) "editor.sh")]
    (spit f (str "#!/bin/sh\n" body "\n"))
    (.setExecutable f true)
    (str f)))

;; ---------------------------------------------------------------- exits and usage

(deftest exit-codes
  (let [{:keys [home port start store]} (daemon)
        dir (bound-dir)
        cli #(run-cli home dir %&)]
    (try
      (testing "3 when no daemon answers, naming the fix"
        (let [{:keys [exit err]} (cli "search" "ready")]
          (is (= 3 exit))
          (is (= (str "the cloisters daemon is not running on port " port "; install it with: cloisters daemon "
                      "install, or run one with: cloisters daemon run\n")
                 err))))
      (testing "2 for usage, with help on stdout only when asked"
        (is (= 2 (:exit (cli))))
        (is (= [2 "unknown command: frob; see cloisters help\n"] ((juxt :exit :err) (cli "frob"))))
        (is (= [2 "show does not take --title\n"] ((juxt :exit :err) (cli "show" "CLO-1" "--title" "x"))))
        (is (= [2 "--parent needs a value\n"] ((juxt :exit :err) (cli "new" "x" "--parent"))))
        (is (str/starts-with? (:out (cli "help")) "usage: cloisters <command>"))
        (is (= "cloisters 0.1.0-SNAPSHOT\n" (:out (cli "version")))))
      (let [stop (start)]
        (try
          (testing "1 for a refusal, its code first on stderr, or its body with --json"
            (is (= [1 "not_found: CLO-9 does not exist.\n"] ((juxt :exit :err) (cli "show" "CLO-9"))))
            (let [{:keys [exit err]} (cli "show" "CLO-9" "--json")]
              (is (= 1 exit))
              (is (= {:error "not_found" :message "CLO-9 does not exist." :id "CLO-9"} (json/read-str err)))))
          (testing "an unknown binding is refused by the daemon, naming the projects there are"
            (is (= [1 "unknown_project: There is no project NOPE. Projects: CLO.\n"]
                   ((juxt :exit :err) (cli "search" "--project" "NOPE")))))
          (finally (stop))))
      (testing "one warning when the daemon's version differs"
        (let [stop (start "0.0.9")]
          (try (is (= "cloisters: the daemon is version 0.0.9 and this CLI 0.1.0-SNAPSHOT; reinstall whichever is older\n"
                      (:err (cli "search"))))
               (finally (stop)))))
      (finally (store/close store)))))

;; ---------------------------------------------------------------- binding

(deftest init-binds-a-directory
  (with-daemon
    (fn [{:keys [home]}]
      (let [dir (fx/tmp-dir)
            cli #(run-cli home dir %&)
            url (str "http://127.0.0.1:" (:port (paths/config (paths/dirs {"CLOISTERS_HOME" (str home)} "/x")))
                     "/mcp?project=NEW")]
        (spit (io/file dir ".mcp.json") "{\"mcpServers\":{\"other\":{\"command\":\"other\"}}}")
        (testing "an unknown key is refused, pointing at project new"
          (let [{:keys [exit err]} (cli "init" "NEW")]
            (is (= 1 exit))
            (is (str/starts-with? err "unknown_project: there is no project NEW. Create it with: cloisters project new"))
            (is (not (.exists (io/file dir ".cloisters"))))))
        (testing "--new creates and binds, merging the MCP entry into .mcp.json"
          (is (zero? (:exit (cli "init" "NEW" "--new" "New project"))))
          (is (= "{:project \"NEW\"}\n" (slurp (io/file dir ".cloisters"))))
          (is (= {"other" {"command" "other"} "cloisters" {"type" "http" "url" url}}
                 (get (json/read-plain (slurp (io/file dir ".mcp.json"))) "mcpServers"))))
        (testing "commands then use the binding"
          (is (str/starts-with? (:out (cli "new" "First")) "NEW-1  open  First\n"))
          (is (str/starts-with? (:out (cli "search")) "query: project:NEW\n")))
        (testing "binding to an existing project, from a directory bound elsewhere"
          (spit (io/file dir ".cloisters") "{:project \"GONE\"}")
          (is (str/starts-with? (:out (cli "init" "CLO")) "bound this directory to CLO"))
          (is (= ["CLO  Cloisters" "NEW  New project"] (str/split-lines (:out (cli "project" "list"))))))))))

;; ---------------------------------------------------------------- issues

(deftest issue-commands
  (with-daemon
    (fn [{:keys [home]}]
      (let [dir (bound-dir)
            cli (fn [& args] (run-cli home dir args :env {"CLOISTERS_ASSIGNEE" "saqib/t1"}))
            out (fn [& args] (let [{:keys [exit out err]} (apply cli args)]
                               (is (zero? exit) err)
                               out))]
        (testing "new, with a body from stdin, labels, parent and blockers"
          (is (= "CLO-1  open  Map  [wayfinder:map]\nversion 1\n" (out "new" "Map" "--label" "wayfinder:map")))
          (is (zero? (:exit (run-cli home dir ["new" "One" "--parent" "CLO-1" "--body-file" "-"] :in "see CLO-1"))))
          (is (= "CLO-3  open · blocked  Two\nversion 1\n" (out "new" "Two" "--parent" "CLO-1" "--blocked-by" "CLO-2"))))
        (testing "search, and its alias"
          (is (= "query: project:CLO kind:issue ready\nCLO-1  open  Map  [wayfinder:map]\nCLO-2  open  One\n"
                 (out "search" "ready")))
          (is (= (out "search" "ready") (out "ls" "ready"))))
        (testing "show, with events on request"
          (is (str/includes? (out "show" "CLO-2") "\n\nsee CLO-1\n"))
          (is (str/includes? (out "show" "CLO-2" "--events") "events (2 of 2, oldest first)")))
        (testing "claim defaults --as to $CLOISTERS_ASSIGNEE; a conflict is refused"
          (is (str/starts-with? (out "claim" "CLO-2") "CLO-2  open · claimed by saqib/t1  One\n"))
          (let [{:keys [exit err]} (cli "claim" "CLO-2" "--as" "other/t2")]
            (is (= 1 exit))
            (is (str/starts-with? err "claim_conflict: CLO-2 is already claimed by saqib/t1")))
          (is (str/starts-with? (out "release" "CLO-2") "CLO-2  open  One\n"))
          (is (str/starts-with? (out "reassign" "CLO-2" "--from" "none" "--to" "x/1") "CLO-2  open · claimed by x/1")))
        (testing "edit by flags: labels and blockers signed, parent none, a comment"
          (is (= "CLO-3  open  Two  [a, b]\nversion 2\n"
                 (out "edit" "CLO-3" "--label" "+a" "--label" "b" "--blocked-by" "-CLO-2" "--parent" "none"
                      "-m" "unblocked")))
          (is (= "CLO-3  open  Two  [a]\nversion 3\n" (out "edit" "CLO-3" "--label" "-b")))
          (is (str/includes? (out "show" "CLO-3") "comments:\n  #")))
        (testing "close and reopen; --json prints structuredContent"
          (is (str/starts-with? (out "close" "CLO-2" "done" "-m" "shipped") "CLO-2  closed done · x/1  One\n"))
          (is (= "open" (:status (json/read-str (out "reopen" "CLO-2" "-m" "again" "--json"))))))
        (testing "the actor is the CLI"
          (is (str/includes? (out "show" "CLO-1" "--events") " cli CLO-1")))))))

(deftest bulk-close
  (with-daemon
    (fn [{:keys [home]}]
      (let [dir (bound-dir)
            cli #(run-cli home dir %&)]
        (doseq [args [["new" "Top" "--label" "bulk"]
                      ["new" "Middle" "--label" "bulk" "--parent" "CLO-1"]
                      ["new" "Leaf" "--label" "bulk" "--parent" "CLO-2"]
                      ["new" "Other root" "--label" "bulk"]]]
          (apply cli args))
        (testing "leaves first, one close each"
          (let [{:keys [exit out]} (cli "close" "--query" "label:bulk" "dropped" "-m" "tidy")]
            (is (zero? exit))
            (is (= ["CLO-3" "CLO-2" "CLO-1" "CLO-4"]
                   (map #(first (str/split % #" ")) (filter #(re-find #"^CLO-\d+  closed" %) (str/split-lines out)))))))
        (testing "not atomic: it stops at the first refusal, having said what it closed"
          (cli "new" "Sibling" "--label" "two")
          (cli "new" "Parent" "--label" "two")
          (cli "new" "Open child" "--parent" "CLO-6")
          (let [{:keys [exit out err]} (cli "close" "--query" "label:two" "done" "-m" "x")]
            (is (= 1 exit))
            (is (str/starts-with? out "CLO-5  closed done") "in the search's order, both at one depth")
            (is (str/starts-with? err "open_children: CLO-6 has open children"))
            (is (str/ends-with? err "Stopped at CLO-6; the issues above were closed.\n"))))))))

;; ---------------------------------------------------------------- pages and the editor

(deftest page-commands
  (with-daemon
    (fn [{:keys [home]}]
      (let [dir (bound-dir)
            cli #(run-cli home dir %&)
            out (fn [& args] (let [{:keys [exit out err]} (apply cli args)]
                               (is (zero? exit) err)
                               out))]
        (run-cli home dir ["page" "new" "Glossary" "--body-file" "-" "-m" "first"] :in "Terms.\n")
        (out "page" "new" "Terms" "--parent" "CLO-1")
        (out "page" "new" "Earlier" "--parent" "CLO-1" "--before" "CLO-2")
        (out "page" "new" "Other root")
        (testing "the tree, and a subtree"
          (is (= "CLO-1  Glossary\n  CLO-3  Earlier\n  CLO-2  Terms\nCLO-4  Other root\n" (out "page" "tree")))
          (is (= "CLO-1  Glossary\n  CLO-3  Earlier\n  CLO-2  Terms\n" (out "page" "tree" "CLO-1"))))
        (testing "show follows wrong_kind to the page"
          (is (str/starts-with? (out "show" "CLO-1") "CLO-1  page  Glossary\nversion r1")))
        (testing "move, archive and unarchive"
          (out "page" "move" "CLO-2" "--parent" "none")
          (is (= "CLO-1  Glossary\n  CLO-3  Earlier\nCLO-2  Terms\nCLO-4  Other root\n" (out "page" "tree")))
          (is (= 1 (:exit (cli "page" "archive" "CLO-4"))) "a summary is required")
          (is (str/starts-with? (out "page" "archive" "CLO-4" "-m" "old") "CLO-4  archived"))
          (is (not (str/includes? (out "page" "tree") "CLO-4")))
          (out "page" "unarchive" "CLO-4" "-m" "back"))
        (testing "the editor saves against the version read, with a summary"
          (let [editor (script-file "printf 'Terms, edited.\\n' > \"$1\"")]
            (is (= "CLO-1  page  Glossary\nversion r2\n"
                   (:out (run-cli home dir ["edit" "CLO-1" "-m" "reword"] :env {"VISUAL" editor}))))))
        (testing "an unchanged file is a no-op"
          (is (= "CLO-1: no change\n" (:out (run-cli home dir ["edit" "CLO-1"] :env {"VISUAL" "true"})))))
        (testing "a refused save keeps the edited file and names it"
          (let [editor (script-file (str "curl -s -X PATCH -H 'Content-Type: application/json' -d '{\"body\":\"agent\"}' "
                                         "http://127.0.0.1:" (:port (paths/config (paths/dirs {"CLOISTERS_HOME" (str home)} "/x")))
                                         "/api/pages/CLO-1 > /dev/null\nprintf 'human\\n' > \"$1\""))
                {:keys [exit err]} (run-cli home dir ["edit" "CLO-1"] :env {"VISUAL" editor})
                kept (second (re-find #"Your edit is kept in (.+)\n" err))]
            (is (= 1 exit))
            (is (str/starts-with? err "stale_version: CLO-1 is at version 3, not 2"))
            (is (= "human\n" (slurp kept)))))
        (testing "history, diff and restore"
          (is (= ["r1" "r2" "r3"] (map #(first (str/split % #" ")) (str/split-lines (out "page" "history" "CLO-1")))))
          (is (str/includes? (out "page" "diff" "CLO-1" "1" "2") "+Terms, edited."))
          (is (str/includes? (out "page" "diff" "CLO-1") "CLO-1 r2 → r3"))
          (is (str/starts-with? (out "page" "restore" "CLO-1" "1" "-m" "undo") "CLO-1  page  Glossary\nversion r4")))))))

;; ---------------------------------------------------------------- the stdio MCP proxy

(def ^:private modern-meta
  {"io.modelcontextprotocol/protocolVersion" "2026-07-28"
   "io.modelcontextprotocol/clientCapabilities" {}
   "io.modelcontextprotocol/clientInfo" {"name" "probe" "version" "1"}})

(defn- lines [& msgs] (str/join (map #(str (json/write-str %) "\n") msgs)))

(deftest the-stdio-proxy
  (let [{:keys [home start store]} (daemon)
        dir (bound-dir)
        initialize {"jsonrpc" "2.0" "id" 1 "method" "initialize"
                    "params" {"protocolVersion" "2025-11-25" "capabilities" {} "clientInfo" {"name" "stdio" "version" "1"}}}
        search (fn [id & [meta]] {"jsonrpc" "2.0" "id" id "method" "tools/call"
                                  "params" (cond-> {"name" "search" "arguments" {"query" "kind:issue"}}
                                             meta (assoc "_meta" meta))})
        responses #(mapv json/read-plain (str/split-lines %))]
    (try
      (testing "it refuses to start while the daemon is down"
        (let [{:keys [exit err]} (run-cli home dir ["mcp"] :in (lines initialize))]
          (is (= 3 exit))
          (is (str/starts-with? err "the cloisters daemon is not running"))))
      (let [stop (start)]
        (try
          (testing "2025-11-25 through a session, and 2026-07-28 with its headers mirrored; a notification writes nothing"
            (let [{:keys [exit out]} (run-cli home dir ["mcp"]
                                              :in (lines initialize
                                                         {"jsonrpc" "2.0" "method" "notifications/initialized"}
                                                         (search 2)
                                                         (search 3 modern-meta)))
                  [init legacy modern :as rs] (responses out)]
              (is (zero? exit))
              (is (= 3 (count rs)))
              (is (= "2025-11-25" (get-in init ["result" "protocolVersion"])))
              (is (= "project:CLO kind:issue" (get-in legacy ["result" "structuredContent" "effective_query"])))
              (is (= "complete" (get-in modern ["result" "resultType"])))))
          (testing "across a daemon restart: an error naming the fix while it is down, then the session replayed"
            (let [pb (doto (ProcessBuilder. ^java.util.List ["bb" script "mcp"]) (.directory dir))
                  _ (doto (.environment pb) (.put "CLOISTERS_HOME" (str home)))
                  p (.start pb)
                  w (io/writer (.getOutputStream p))
                  r (io/reader (.getInputStream p))
                  ask (fn [msg]
                        (.write w (lines msg))
                        (.flush w)
                        (json/read-plain (deref (future (.readLine ^java.io.BufferedReader r)) 30000 "null")))]
              (try
                (ask initialize)
                (is (get-in (ask (search 2)) ["result" "structuredContent"]))
                (stop)
                (is (= {"code" -32000} (select-keys (get (ask (search 3)) "error") ["code"])))
                (let [stop (start)]
                  (try (is (= "project:CLO kind:issue"
                              (get-in (ask (search 4)) ["result" "structuredContent" "effective_query"])))
                       (finally (stop))))
                (finally (.close w) (.waitFor p 10 TimeUnit/SECONDS) (.destroy p)))))
          (finally (stop))))
      (finally (store/close store)))))
