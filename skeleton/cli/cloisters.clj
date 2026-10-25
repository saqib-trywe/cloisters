#!/usr/bin/env bb
;; PROTOTYPE: the cloisters CLI for the walking skeleton. Babashka with bundled libraries only; the daemon validates
;; and renders, the CLI only sends and prints. Install: bbin install cli/cloisters.clj --as cloisters
(ns cloisters.cli
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn- env [k] (not-empty (System/getenv k)))

(defn- config-file []
  (if-let [home (env "CLOISTERS_HOME")]
    (fs/file home "config" "config.edn")
    (fs/file (or (env "XDG_CONFIG_HOME") (str (fs/home) "/.config")) "cloisters" "config.edn")))

(defn- port [] (:port (merge {:port 7227} (when (fs/exists? (config-file)) (edn/read-string (slurp (config-file)))))))

(defn- binding-project []
  (loop [dir (fs/absolutize ".")]
    (when dir
      (let [f (fs/file dir ".cloisters")]
        (if (fs/exists? f) (:project (edn/read-string (slurp f))) (recur (fs/parent dir)))))))

(defn- die [code & msg] (binding [*out* *err*] (println (str/join " " msg))) (System/exit code))

(defn- request [method path & [body]]
  (let [url (str "http://127.0.0.1:" (port) path)]
    (try
      (http/request (cond-> {:method method :uri url :throw false
                             :headers (cond-> {"Accept" "text/plain"
                                               "Cloisters-Actor" (str "cli:" (or (env "CLOISTERS_ASSIGNEE") (System/getProperty "user.name")))}
                                        (binding-project) (assoc "Cloisters-Project" (binding-project)))}
                      body (-> (assoc :body (json/generate-string body))
                               (assoc-in [:headers "Content-Type"] "application/json"))))
      (catch java.net.ConnectException _
        (die 3 (str "cloisters daemon is not running on port " (port) "; start it with: cloisters daemon start"))))))

(defn- print-or-fail [{:keys [status body]}]
  (if (< status 300)
    (print body)
    (die 1 (str/trim body)))
  (flush))

(defn- show [id]
  (let [r (request :get (str "/api/issues/" id))]
    ;; no kind-agnostic route: try the issue route and follow wrong_kind
    (if (str/starts-with? (:body r) "wrong_kind:")
      (print-or-fail (request :get (str "/api/pages/" id)))
      (print-or-fail r))))

(def spec {:body {} :body-file {} :parent {} :m {:alias :summary} :summary {} :project {}})

(defn- body-of [{:keys [body body-file]}]
  (cond body body (= body-file "-") (slurp *in*) body-file (slurp body-file)))

(defn -main [& args]
  (let [{:keys [args opts]} (cli/parse-args args {:spec spec})
        [cmd a b] args
        body (body-of opts)]
    (case cmd
      "show" (if a (show a) (die 2 "usage: cloisters show ID"))
      "search" (print-or-fail (request :get (str "/api/search?q=" (java.net.URLEncoder/encode (str/join " " (rest args)) "UTF-8"))))
      "new" (if a
              (print-or-fail (request :post "/api/issues" (cond-> {:title a} body (assoc :body body)
                                                            (:project opts) (assoc :project (:project opts)))))
              (die 2 "usage: cloisters new \"<title>\" [--body text | --body-file f|-]"))
      "page" (case a
               "new" (if b
                       (print-or-fail (request :post "/api/pages"
                                               (cond-> {:title b}
                                                 body (assoc :body body)
                                                 (:parent opts) (assoc :parent (:parent opts))
                                                 (:summary opts) (assoc :summary (:summary opts)))))
                       (die 2 "usage: cloisters page new \"<title>\" [--parent id] [--body-file f|-] [-m summary]"))
               (die 2 "usage: cloisters page new ..."))
      (die 2 "usage: cloisters show|search|new|page new"))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
