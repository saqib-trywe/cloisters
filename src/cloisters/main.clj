(ns cloisters.main
  "The daemon entry point. M0 holds only the scaffold; `daemon run` arrives with the store in M1."
  (:gen-class))

(defn -main [& _]
  (binding [*out* *err*]
    (println "cloisters daemon: not built yet"))
  (System/exit 2))
