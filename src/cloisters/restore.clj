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

(defn- check-destinations!
  "Refuses before anything is touched when a path we would write through already exists."
  [files]
  (doseq [^File f files]
    (when (.exists f)
      (refuse (str "the restore destination " f " already exists; move it aside first")
              {:destination (str f)}))))

(defn restore!
  "Restores the snapshot s (a path, or a name in the backups directory) as the store in dirs, at now. Returns
  {:snapshot :store :moved-aside}, where moved-aside is nil when there was no store. Refuses, changing nothing, when a
  daemon holds the lock, the snapshot fails its checks, or a destination is occupied. A failure partway through moves
  any files already put aside back and removes the staged copy."
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
        (let [base (str "pre-restore-" (.format stamp now) ".db")
              aside-file (io/file backups base)
              wal (io/file (str target "-wal"))
              shm (io/file (str target "-shm"))
              had-store (.exists target)
              ;; the WAL and shared-memory files go with the store, so the move aside loses nothing
              moving (cond-> []
                       (.exists wal) (conj [wal (io/file backups (str base "-wal"))])
                       (.exists shm) (conj [shm (io/file backups (str base "-shm"))])
                       had-store (conj [target aside-file]))
              tmp (io/file (.getParentFile target) (str (.getName target) ".restoring"))
              moved (atom [])
              undo! (fn []
                      (doseq [[from to] (reverse @moved)]
                        (when (and (.exists ^File to) (not (.exists ^File from)))
                          (move! to from)))
                      (when (.exists tmp) (io/delete-file tmp)))]
          (check-destinations! (into [tmp] (map second moving)))
          (try
            (Files/copy (.toPath snap) (.toPath tmp) (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))
            (catch Exception e
              (when (.exists tmp) (io/delete-file tmp))
              (throw e)))
          (try
            (doseq [[from to] moving]
              (move! from to)
              (swap! moved conj [from to]))
            (move! tmp target)
            {:snapshot snap :store target :moved-aside (when had-store aside-file)}
            (catch Exception e
              (try (undo!)
                   (catch Exception rollback (.addSuppressed e rollback)))
              (throw e)))))
      (finally (lock/release! held)))))
