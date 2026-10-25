(ns cloisters.store
  "PROTOTYPE. The SQLite store: a HikariCP read pool, one write connection behind a lock, the daemon lock and the
  hand-rolled user_version migration runner."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.connection :as connection]
            [next.jdbc.result-set :as rs])
  (:import (com.zaxxer.hikari HikariDataSource)
           (java.io File RandomAccessFile)
           (java.nio.channels FileChannel FileLock)
           (java.time Instant)))

(def migrations ["001-init.sql" "002-text.sql"])

(def opts {:builder-fn rs/as-unqualified-kebab-maps})

(defn- migration-limit []
  ;; PROTOTYPE hook: lets a run stop short, so a later run migrates a non-empty store.
  (if-let [n (System/getenv "CLOISTERS_SKEL_MIGRATIONS")] (parse-long n) (count migrations)))

;; ---------------------------------------------------------------- daemon lock

(defn take-lock! ^FileLock [^File data port version]
  (let [f (io/file data "daemon.lock")
        raf (RandomAccessFile. f "rw")
        ^FileChannel ch (.getChannel raf)]
    (if-let [lock (.tryLock ch)]
      (do (.setLength raf 0)
          (.write raf (.getBytes (pr-str {:pid (.pid (java.lang.ProcessHandle/current)) :port port :version version})))
          lock)
      (do (.close raf)
          (throw (ex-info (str "another daemon holds " f ": " (str/trim (slurp f))) {:lock (str f)}))))))

;; ---------------------------------------------------------------- migrations

(defn- user-version [conn]
  ;; execute-one! closes its statement and result set, so VACUUM INTO can run afterwards.
  (:user-version (jdbc/execute-one! conn ["PRAGMA user_version"] opts)))

(defn- statements [sql]
  (->> (str/split sql #";\s*\n")
       (map #(str/trim (str/join "\n" (remove (fn [l] (str/starts-with? (str/trim l) "--")) (str/split-lines %)))))
       (remove str/blank?)))

(defn migrate! [conn ^File data]
  (let [current (user-version conn)
        target (migration-limit)]
    (when (> current (count migrations))
      (throw (ex-info (str "store is at version " current " but this daemon knows only up to " (count migrations))
                      {:store current :daemon (count migrations)})))
    (when (< current target)
      (when (pos? current)
        (let [dir (io/file data "backups")
              snap (io/file dir (str (str/replace (str (Instant/now)) ":" "") "-v" current ".db"))]
          (.mkdirs dir)
          (jdbc/execute! conn [(str "VACUUM INTO '" (.getPath snap) "'")])
          (println "snapshot before migrating:" (.getPath snap))))
      (jdbc/with-transaction [tx conn]
        (doseq [i (range current target)
                stmt (statements (slurp (io/resource (str "migrations/" (migrations i)))))]
          (jdbc/execute! tx [stmt]))
        (jdbc/execute! tx [(str "PRAGMA user_version = " target)]))
      (println "migrated store from v" current "to v" target))))

;; ---------------------------------------------------------------- connections

(def ^:private pragmas ["PRAGMA journal_mode = WAL" "PRAGMA synchronous = FULL"
                        "PRAGMA foreign_keys = ON" "PRAGMA busy_timeout = 5000"])

(defn open [^File data]
  (.mkdirs data)
  (let [url (str "jdbc:sqlite:" (.getPath (io/file data "cloisters.db")))
        writer (jdbc/get-connection {:jdbcUrl url})]
    (doseq [p pragmas] (jdbc/execute! writer [p]))
    (migrate! writer data)
    {:writer writer
     :write-lock (Object.)
     :reads (connection/->pool HikariDataSource
                               {:jdbcUrl url :maximumPoolSize 4
                                :connectionInitSql "PRAGMA foreign_keys = ON; PRAGMA busy_timeout = 5000"})}))

(defn close [{:keys [writer ^HikariDataSource reads]}]
  (.close reads)
  (jdbc/execute! writer ["PRAGMA wal_checkpoint(TRUNCATE)"])
  (.close ^java.sql.Connection writer))

(defn write!
  "Runs f with a transaction on the one write connection. Writes queue on the lock and return synchronously."
  [{:keys [writer write-lock]} f]
  (locking write-lock
    (jdbc/with-transaction [tx writer] (f tx))))
