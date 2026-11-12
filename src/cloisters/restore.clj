(ns cloisters.restore
  "Putting a snapshot back, with the daemon stopped: take the lock (refused while a daemon holds it), check the
  snapshot's integrity and that its schema is one this daemon knows, move the current store aside into backups, and
  copy the snapshot in. Nothing is deleted, so a mistaken restore is itself restorable. The next start migrates the
  store as normal."
  (:require [cloisters.backup :as backup]
            [cloisters.lock :as lock]
            [cloisters.paths :as paths]
            [cloisters.store :as store]
            [clojure.java.io :as io]
            [next.jdbc :as jdbc])
  (:import (java.io File)
           (java.nio.file CopyOption Files StandardCopyOption)
           (java.time Instant ZoneOffset)
           (java.time.format DateTimeFormatter)))

(def ^:private stamp (.withZone (DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmss.SSS'Z'") ZoneOffset/UTC))

(defn- refuse [message data]
  (throw (ex-info message data)))

(defn- snapshot-file
  "The snapshot named by s: a path, or the name of a file in the backups directory."
  ^File [dirs s]
  (let [f (io/file s)
        in-backups (when-not (.isAbsolute f) (io/file (paths/backups-dir dirs) s))]
    (cond (.isFile f) f
          (some-> in-backups .isFile) in-backups
          :else (refuse (str "no snapshot at " s (when in-backups (str " or " in-backups))) {:snapshot s}))))

(defn- schema-version [^File f]
  (with-open [conn (jdbc/get-connection {:jdbcUrl (str "jdbc:sqlite:" (.getPath f) "?open_mode=1")})]
    (store/user-version conn)))

(defn- move! [^File from ^File to]
  (Files/move (.toPath from) (.toPath to) (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE])))

(defn restore!
  "Restores the snapshot s (a path, or a name in the backups directory) as the store in dirs, at now. Returns
  {:snapshot :store :moved-aside}, where moved-aside is nil when there was no store. Throws, changing nothing, when a
  daemon holds the lock or the snapshot fails its checks."
  [dirs s ^Instant now]
  (let [held (lock/acquire! (paths/lock-file dirs) {:port nil :version "restore"})]
    (try
      (let [snap (snapshot-file dirs s)
            known (count store/migration-files)
            target (paths/store-file dirs)
            backups (paths/backups-dir dirs)]
        (when-let [problem (backup/check-file snap)]
          (refuse (str snap " failed its integrity check: " problem) {:snapshot (str snap) :problem problem}))
        (let [v (schema-version snap)]
          (when (> v known)
            (refuse (str snap " is at schema v" v ", but this daemon knows only up to v" known "; run a newer daemon")
                    {:snapshot (str snap) :snapshot-version v :daemon-version known})))
        (.mkdirs backups)
        (let [aside (when (.exists target)
                      (let [base (str "pre-restore-" (.format stamp now) ".db")]
                        ;; the WAL and shared-memory files go with the store, so the copy aside loses nothing
                        (doseq [suffix ["-wal" "-shm"]
                                :let [f (io/file (str target suffix))]
                                :when (.exists f)]
                          (move! f (io/file backups (str base suffix))))
                        (let [f (io/file backups base)]
                          (move! target f)
                          f)))
              tmp (io/file (.getParentFile target) (str (.getName target) ".restoring"))]
          (Files/copy (.toPath snap) (.toPath tmp) (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))
          (move! tmp target)
          {:snapshot snap :store target :moved-aside aside}))
      (finally (lock/release! held)))))
