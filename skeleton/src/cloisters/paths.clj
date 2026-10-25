(ns cloisters.paths
  "PROTOTYPE. Where cloisters' files live: XDG on macOS and Linux, or everything under CLOISTERS_HOME."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(defn- env [k] (not-empty (System/getenv k)))

(defn- xdg [var default]
  (io/file (or (env var) (str (System/getProperty "user.home") "/" default)) "cloisters"))

(defn resolve-dirs []
  (if-let [home (env "CLOISTERS_HOME")]
    (let [home (.getAbsoluteFile (io/file home))]
      {:config (io/file home "config") :data (io/file home "data")
       :state (io/file home "state") :cache (io/file home "cache")})
    {:config (xdg "XDG_CONFIG_HOME" ".config") :data (xdg "XDG_DATA_HOME" ".local/share")
     :state (xdg "XDG_STATE_HOME" ".local/state") :cache (xdg "XDG_CACHE_HOME" ".cache")}))

(defn config [{:keys [config]}]
  (let [f (io/file config "config.edn")]
    (merge {:port 7227} (when (.exists f) (edn/read-string (slurp f))))))
