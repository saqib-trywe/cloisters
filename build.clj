(ns build
  "The daemon uberjar: `clojure -T:build uber` writes target/cloisters-daemon.jar."
  (:require [clojure.tools.build.api :as b]))

(def class-dir "target/classes")
(def uber-file "target/cloisters-daemon.jar")

(defn clean [_]
  (b/delete {:path "target"}))

(defn uber [_]
  (clean nil)
  (let [basis (b/create-basis {:project "deps.edn"})]
    (b/copy-dir {:src-dirs ["src" "resources"] :target-dir class-dir})
    (b/compile-clj {:basis basis :ns-compile '[cloisters.main] :class-dir class-dir})
    (b/uber {:class-dir class-dir :uber-file uber-file :basis basis :main 'cloisters.main})))
