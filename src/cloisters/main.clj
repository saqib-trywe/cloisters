(ns cloisters.main
  "The daemon, run in the foreground: take the lock, open the store, snapshot it daily, serve HTTP and MCP on
  127.0.0.1, and hold until stopped."
  (:require [cloisters.http :as http]
            [cloisters.lock :as lock]
            [cloisters.paths :as paths]
            [cloisters.store :as store])
  (:import (java.util.concurrent Executors ScheduledExecutorService TimeUnit))
  (:gen-class))

(def version "0.1.0-SNAPSHOT")

(defn- fail [^Throwable e]
  (binding [*out* *err*]
    (println "cloisters daemon:" (or (ex-message e) (str e))))
  (System/exit 1))

(defn -main [& _]
  (try
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
      @(promise))
    (catch Throwable e (fail e))))
