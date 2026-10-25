#!/usr/bin/env bb
;; The cloisters CLI. Babashka with bundled libraries only: the daemon validates and renders, the CLI only sends and
;; prints. Install: bbin install cli/cloisters.clj --as cloisters (bbin copies the script, so reinstall after a change).
(ns cloisters.cli
  ;; bbin installs this one file as `cloisters`, so its path cannot follow the namespace
  {:clj-kondo/config '{:linters {:namespace-name-mismatch {:level :off}}}}
  (:require [clojure.string :as str]))

(defn- die [code & msg]
  (binding [*out* *err*] (println (str/join " " msg)))
  (System/exit code))

(defn -main [& _]
  (die 2 "usage: cloisters <command>; no commands yet"))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
