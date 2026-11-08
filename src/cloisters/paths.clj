(ns cloisters.paths
  "Where cloisters' files live: the XDG base directories on macOS and Linux alike, or everything under one
  CLOISTERS_HOME. Also reads config.edn."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import (java.io File)))

(defn dirs
  "The config, data, state and cache directories, resolved from env (a map of environment variables) and the user's
  home directory. CLOISTERS_HOME, when set, overrides the XDG variables. A relative XDG path is ignored, as the XDG
  spec requires."
  ([] (dirs (System/getenv) (System/getProperty "user.home")))
  ([env home]
   (let [env #(not-empty (get env %))]
     (if-let [root (env "CLOISTERS_HOME")]
       (let [root (.getAbsoluteFile (io/file root))]
         {:config (io/file root "config")
          :data (io/file root "data")
          :state (io/file root "state")
          :cache (io/file root "cache")})
       (let [xdg (fn [var default]
                   (let [dir (env var)
                         dir (if (and dir (.isAbsolute (io/file dir))) dir (str home "/" default))]
                     (io/file dir "cloisters")))]
         {:config (xdg "XDG_CONFIG_HOME" ".config")
          :data (xdg "XDG_DATA_HOME" ".local/share")
          :state (xdg "XDG_STATE_HOME" ".local/state")
          :cache (xdg "XDG_CACHE_HOME" ".cache")})))))

(defn config-file ^File [dirs] (io/file (:config dirs) "config.edn"))
(defn store-file ^File [dirs] (io/file (:data dirs) "cloisters.db"))
(defn backups-dir ^File [dirs] (io/file (:data dirs) "backups"))
(defn lock-file ^File [dirs] (io/file (:data dirs) "daemon.lock"))
(defn lib-dir ^File [dirs] (io/file (:data dirs) "lib"))
(defn logs-dir ^File [dirs] (io/file (:state dirs) "logs"))

(def default-port 7227)

(defn- read-config [^File f]
  (let [cfg (try (edn/read-string (slurp f))
                 (catch Exception e
                   (throw (ex-info (str f " is not readable EDN: " (ex-message e)) {:config (str f)} e))))]
    (when-not (or (nil? cfg) (map? cfg))
      (throw (ex-info (str f " must hold one map, such as {:port 7227}") {:config (str f)})))
    cfg))

(defn config
  "config.edn merged over the defaults. Throws naming the file and key when a value is unusable."
  [dirs]
  (let [f (config-file dirs)
        {:keys [port] :as cfg} (merge {:port default-port} (when (.exists f) (read-config f)))]
    (when-not (and (int? port) (<= 1 port 65535))
      (throw (ex-info (str f ": :port must be a TCP port number, not " (pr-str port)) {:config (str f) :key :port})))
    cfg))
