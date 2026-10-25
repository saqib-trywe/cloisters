(ns cloisters.main
  "PROTOTYPE. `daemon run` in the foreground: lock, store, one core, one Jetty."
  (:require [cloisters.http :as http]
            [cloisters.paths :as paths]
            [cloisters.store :as store]
            [ring.adapter.jetty :as jetty])
  (:gen-class))

(defn -main [& _]
  (let [dirs (paths/resolve-dirs)
        {:keys [port]} (paths/config dirs)
        _ (.mkdirs (:data dirs))
        lock (try (store/take-lock! (:data dirs) port "0.0.0-skeleton")
                  (catch clojure.lang.ExceptionInfo e (binding [*out* *err*] (println (ex-message e))) (System/exit 1)))
        st (store/open (:data dirs))
        ctx {:store st :dirs dirs :publish http/publish}
        server (jetty/run-jetty (http/app ctx port)
                                {:host "127.0.0.1" :port port :join? false :async? true :async-timeout 0})]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. #(do (.stop server) (store/close st) (.release lock))))
    (println "cloisters skeleton on" (str "http://127.0.0.1:" port) "data" (str (:data dirs)))
    (.join server)))
