(ns cloisters.main
  "The daemon jar's entry point. With no arguments, or `run`, it is the daemon in the foreground: take the lock, open
  the store, snapshot it daily, serve HTTP and MCP on 127.0.0.1, and hold until stopped. `export <file>` and
  `restore <snapshot>` are the offline operations the CLI's `daemon export` and `daemon restore` run, which work
  whether or not a daemon is running."
  (:require [cloisters.export :as export]
            [cloisters.http :as http]
            [cloisters.lock :as lock]
            [cloisters.paths :as paths]
            [cloisters.restore :as restore]
            [cloisters.store :as store]
            [clojure.java.io :as io])
  (:import (java.time Instant)
           (java.util.concurrent Executors ScheduledExecutorService TimeUnit))
  (:gen-class))

(def version "0.1.0-SNAPSHOT")

(defn- fail [code & msg]
  (binding [*out* *err*]
    (apply println "cloisters daemon:" msg))
  (System/exit code))

(def ^:private usage
  "usage: cloisters-daemon.jar [run | export <file> | restore <snapshot>]")

(defn run
  "Serves until the process is stopped."
  []
  (let [dirs (paths/dirs)
        {:keys [port]} (paths/config dirs)
        held (lock/acquire! (paths/lock-file dirs) {:port port :version version})
        st (try (store/open (paths/store-file dirs) (paths/backups-dir dirs))
                (catch Throwable e (lock/release! held) (throw e)))
        ^ScheduledExecutorService daily (Executors/newSingleThreadScheduledExecutor)
        server (try (http/start! {:store st :version version :dirs dirs} port)
                    (catch Throwable e (store/close st) (lock/release! held) (throw e)))]
    (store/daily-snapshot! st)
    (.scheduleAtFixedRate daily #(store/daily-snapshot! st) 24 24 TimeUnit/HOURS)
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn []
                                 (http/stop! server)
                                 (.shutdownNow daily)
                                 (store/close st)
                                 (lock/release! held))))
    (println "cloisters daemon" version "on" (str "http://127.0.0.1:" port) "store" (str (:file st))
             (str "(schema v" (:version st) ")"))
    @(promise)))

(defn export-to
  "Writes the export of the store in dirs to out. Returns out."
  [dirs out]
  (let [store-file (paths/store-file dirs)
        out (io/file out)]
    (when-not (.isFile store-file)
      (throw (ex-info (str "no store at " store-file) {:store (str store-file)})))
    (io/make-parents out)
    (export/export-file! store-file out)
    out))

(defn -main [& [cmd arg & more]]
  (try
    (cond
      (and (contains? #{nil "run"} cmd) (nil? arg)) (run)

      (and (= "export" cmd) arg (empty? more))
      (println "exported" (str (paths/store-file (paths/dirs))) "to" (str (export-to (paths/dirs) arg)))

      (and (= "restore" cmd) arg (empty? more))
      (let [{:keys [snapshot store moved-aside]} (restore/restore! (paths/dirs) arg (Instant/now))]
        (when moved-aside (println "moved the store aside to" (str moved-aside)))
        (println "restored" (str snapshot) "as" (str store))
        (println "the next daemon start checks and migrates it"))

      :else (fail 2 usage))
    (catch Throwable e (fail 1 (or (ex-message e) (str e)))))
  (when (#{"export" "restore"} cmd) (shutdown-agents)))
