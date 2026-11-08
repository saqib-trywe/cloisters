(ns cloisters.store
  "The SQLite store: one write connection behind a lock, so writes queue and each returns synchronously, and a
  HikariCP read pool beside it under WAL. Opening checks the store's integrity and migrates it forward."
  (:require [cloisters.backup :as backup]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [next.jdbc.connection :as connection]
            [next.jdbc.result-set :as rs])
  (:import (com.zaxxer.hikari HikariDataSource)
           (java.io File)
           (java.sql Connection SQLException)
           (java.time Instant LocalDate)
           (java.util.concurrent TimeUnit)
           (java.util.concurrent.locks ReentrantLock)))

(def opts {:builder-fn rs/as-unqualified-kebab-maps})

;; ---------------------------------------------------------------- migrations

(def migration-files
  "Schema version N is the first N of these, applied in order. Append only: a merged migration never changes."
  ["001-schema.sql"])

(defn bundled-migrations []
  (mapv #(slurp (io/resource (str "migrations/" %))) migration-files))

(defn user-version [conn]
  ;; execute-one! closes its statement and result set, which VACUUM INTO needs.
  (:user-version (jdbc/execute-one! conn ["PRAGMA user_version"] opts)))

(defn- exec-script!
  "Runs a whole migration file. sqlite-jdbc's executeUpdate goes through sqlite3_exec, which runs every statement,
  triggers included."
  [^Connection conn ^String sql]
  (with-open [st (.createStatement conn)]
    (.executeUpdate st sql)))

(defn- migrate!
  "Applies the migrations the store lacks, in one transaction, after a snapshot of any non-empty store. Refuses a store
  newer than migrations, naming both versions."
  [conn backups migrations]
  (let [current (user-version conn)
        target (count migrations)]
    (when (> current target)
      (throw (ex-info (str "the store is at schema v" current ", but this daemon knows only up to v" target
                           "; run a newer daemon")
                      {:store-version current :daemon-version target})))
    (when (< current target)
      (when (pos? current)
        (backup/migration-snapshot! conn backups current (Instant/now)))
      (jdbc/with-transaction [tx conn]
        (doseq [script (subvec migrations current)]
          (exec-script! tx script))
        (jdbc/execute! tx [(str "PRAGMA user_version = " target)])))
    target))

;; ---------------------------------------------------------------- opening and closing

(defn- url [^File f & params]
  (str "jdbc:sqlite:" (.getPath f) "?" (str/join "&" params)))

(def ^:private shared-params ["foreign_keys=true" "busy_timeout=5000"])

(defn- writer-url [f]
  ;; IMMEDIATE takes the write lock at BEGIN, so "check invariants, then write" never meets SQLITE_BUSY halfway.
  (apply url f "journal_mode=WAL" "synchronous=FULL" "transaction_mode=IMMEDIATE" shared-params))

(defn- integrity-refusal [^File f ^File backups problem]
  (let [snap (backup/newest-passing backups)]
    (ex-info (str "the store " f " failed its integrity check (" problem "). "
                  (if snap
                    (str "The newest passing snapshot is " snap "; with the daemon stopped, run: cloisters daemon restore "
                         snap)
                    (str "No snapshot in " backups " passes either.")))
             {:store (str f) :problem problem :snapshot (some-> snap str)})))

(defn- open-writer
  "The write connection, or a refusal when the store fails quick_check or SQLite cannot open it at all."
  [^File f ^File backups]
  (let [conn (try (jdbc/get-connection {:jdbcUrl (writer-url f)})
                  (catch SQLException e (throw (integrity-refusal f backups (ex-message e)))))]
    (when-let [problem (try (backup/quick-check conn) (catch SQLException e (ex-message e)))]
      (.close ^Connection conn)
      (throw (integrity-refusal f backups problem)))
    conn))

(defn open
  "Opens the store file, refusing one that fails quick_check or is newer than this daemon, and migrates it forward.
  backups is where snapshots go. migrations defaults to the bundled ones."
  ([file backups] (open file backups (bundled-migrations)))
  ([^File file ^File backups migrations]
   (.mkdirs (.getParentFile file))
   (let [writer (open-writer file backups)
         version (try (migrate! writer backups migrations)
                      (catch Exception e (.close ^Connection writer) (throw e)))]
     {:file file
      :backups backups
      :version version
      :writer writer
      :write-lock (ReentrantLock. true)
      :reads (connection/->pool HikariDataSource
                                {:jdbcUrl (apply url file shared-params)
                                 :maximumPoolSize 4
                                 :connectionInitSql "PRAGMA query_only = true"})})))

(defn close
  "Closes the read pool, lets an in-flight write finish for up to 5 s, checkpoints the WAL and closes the writer."
  [{:keys [^Connection writer ^ReentrantLock write-lock ^HikariDataSource reads]}]
  (.close reads)
  (let [locked (.tryLock write-lock 5 TimeUnit/SECONDS)]
    (try (jdbc/execute! writer ["PRAGMA wal_checkpoint(TRUNCATE)"])
         (.close writer)
         (finally (when locked (.unlock write-lock))))))

;; ---------------------------------------------------------------- reads and writes

(defn reads
  "The read pool, for next.jdbc. Its connections refuse writes."
  [store]
  (:reads store))

(defn- with-writer [{:keys [writer ^ReentrantLock write-lock]} f]
  (.lock write-lock)
  (try (f writer)
       (finally (.unlock write-lock))))

(defn write!
  "Runs (f tx) in a transaction on the one write connection and returns its result. Writes queue on the lock, one at a
  time; an exception rolls the transaction back and propagates."
  [store f]
  (with-writer store (fn [writer] (jdbc/with-transaction [tx writer] (f tx)))))

(defn daily-snapshot!
  "Today's snapshot, taken between writes."
  [store]
  (with-writer store #(backup/daily-snapshot! % (:backups store) (LocalDate/now))))

(defn allocate!
  "Issues the next number in project's shared sequence to a new issue or page (kind :issue or :page), recording it in
  item. Call inside the creating transaction, so a refused create spends no number."
  [tx project kind]
  (let [{:keys [number]} (jdbc/execute-one! tx (sql/format {:update :project
                                                            :set {:next-number [:+ :next-number 1]}
                                                            :where [:= :key project]
                                                            :returning [[[:- :next-number 1] :number]]})
                                            opts)]
    (when-not number
      (throw (ex-info (str "no project " project) {:project project})))
    (jdbc/execute-one! tx (sql/format {:insert-into :item :values [{:project project :number number :kind (name kind)}]}))
    number))
