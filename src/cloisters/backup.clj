(ns cloisters.backup
  "Snapshots of the store. VACUUM INTO writes a consistent copy of the live store, where copying the file could catch
  the database and its WAL at different moments. Every snapshot is quick_checked after it is written. Migration
  snapshots are kept forever, and the last seven dailies."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import (java.io File)
           (java.nio.file CopyOption Files StandardCopyOption)
           (java.sql SQLException)
           (java.time Instant LocalDate ZoneOffset)
           (java.time.format DateTimeFormatter)))

(def dailies-kept 7)

(defn- log [& msg]
  (binding [*out* *err*] (println (str/join " " msg))))

(defn quick-check
  "PRAGMA quick_check through conn: nil when the store is intact, otherwise what it reports, as one string."
  [conn]
  (let [rows (mapv :quick_check (jdbc/execute! conn ["PRAGMA quick_check"] {:builder-fn rs/as-unqualified-maps}))]
    (when-not (= ["ok"] rows) (str/join "; " rows))))

(defn check-file
  "quick_check on the store file f: nil when intact, otherwise what is wrong, including a file SQLite cannot open."
  [^File f]
  (try (with-open [conn (jdbc/get-connection {:jdbcUrl (str "jdbc:sqlite:" (.getPath f))})]
         (quick-check conn))
       (catch SQLException e (ex-message e))))

(defn- snapshot!
  "VACUUM INTO dir/name through conn, by way of a temporary file, so a snapshot that fails quick_check never
  replaces a good one. Returns the snapshot, or throws after deleting the failed copy."
  ^File [conn ^File dir name]
  (.mkdirs dir)
  (let [f (io/file dir name)
        tmp (io/file dir (str name ".tmp"))]
    (Files/deleteIfExists (.toPath tmp))
    (jdbc/execute! conn ["VACUUM INTO ?" (.getPath tmp)])
    (if-let [problem (check-file tmp)]
      (do (Files/deleteIfExists (.toPath tmp))
          (throw (ex-info (str "snapshot " f " failed quick_check: " problem) {:snapshot (str f)})))
      (do (Files/move (.toPath tmp) (.toPath f)
                      (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))
          f))))

(def ^:private stamp (.withZone (DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmss.SSS'Z'") ZoneOffset/UTC))

(defn migration-snapshot!
  "The snapshot taken before migrating a store at schema version, named <UTC time>-v<version>.db. Kept forever.
  Throws when it fails its check, so nothing migrates without a good snapshot."
  ^File [conn dir version ^Instant now]
  (snapshot! conn dir (str (.format stamp now) "-v" version ".db")))

(def ^:private daily-name #"daily-\d{4}-\d{2}-\d{2}\.db")

(defn- prune-dailies! [^File dir]
  (doseq [^File f (->> (.listFiles dir)
                       (filter #(re-matches daily-name (.getName ^File %)))
                       (sort-by #(.getName ^File %) #(compare %2 %1))
                       (drop dailies-kept))]
    (.delete f)))

(defn daily-snapshot!
  "Today's snapshot, daily-<date>.db, replacing one already taken today, then keeps only the last seven dailies.
  A failed snapshot is deleted and logged, never thrown: the daemon keeps serving. Returns the snapshot or nil."
  [conn ^File dir ^LocalDate date]
  (try (let [f (snapshot! conn dir (str "daily-" date ".db"))]
         (prune-dailies! dir)
         f)
       (catch Exception e
         (log "cloisters daemon: daily snapshot failed:" (ex-message e))
         nil)))

(defn newest-passing
  "The most recently written snapshot in dir that passes quick_check, or nil."
  ^File [^File dir]
  (->> (.listFiles dir)
       (filter #(str/ends-with? (.getName ^File %) ".db"))
       (sort-by #(.lastModified ^File %) #(compare %2 %1))
       (remove check-file)
       first))
