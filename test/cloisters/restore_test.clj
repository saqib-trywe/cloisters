(ns cloisters.restore-test
  "Restoring a snapshot, and exporting, as the daemon jar's offline operations do: against a temporary home, with the
  lock guarding both against a running daemon."
  (:require [cloisters.fixtures :as fx]
            [cloisters.lock :as lock]
            [cloisters.main :as main]
            [cloisters.paths :as paths]
            [cloisters.restore :as restore]
            [cloisters.store :as store]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc])
  (:import (clojure.lang ExceptionInfo)
           (java.time Instant)))

(def now (Instant/parse "2026-11-09T10:00:00Z"))

(defn- keys-in [f]
  (with-open [conn (jdbc/get-connection {:jdbcUrl (str "jdbc:sqlite:" f)})]
    (mapv :key (fx/query conn {:select [:key] :from [:project] :order-by [:key]}))))

(defn- refusal [f]
  (try (f) nil (catch ExceptionInfo e (ex-message e))))

(deftest restoring-a-snapshot
  (let [home (fx/tmp-dir)
        dirs (paths/dirs {"CLOISTERS_HOME" (str home)} "/nowhere")
        snap (io/file (fx/backups home) "daily-2026-11-08.db")]
    (let [st (fx/open home)]
      (store/write! st #(fx/project! % "CLO"))
      (store/daily-snapshot! st)
      (.renameTo (first (.listFiles (fx/backups home))) snap)
      (store/write! st #(fx/project! % "LATER"))
      (store/close st))
    (testing "refused while a daemon holds the lock, changing nothing"
      (let [held (lock/acquire! (paths/lock-file dirs) {:port 1 :version "test"})]
        (try (is (str/starts-with? (refusal #(restore/restore! dirs (str snap) now)) "another daemon holds"))
             (finally (lock/release! held))))
      (is (= ["CLO" "LATER"] (keys-in (fx/store-file home)))))
    (testing "a snapshot that fails its check, or is newer than the daemon, is refused"
      (let [bad (io/file home "bad.db")
            newer (io/file home "newer.db")]
        (spit bad "not a database")
        (is (str/includes? (refusal #(restore/restore! dirs (str bad) now)) "failed its integrity check"))
        (io/copy snap newer)
        (with-open [conn (jdbc/get-connection {:jdbcUrl (str "jdbc:sqlite:" newer)})]
          (jdbc/execute! conn ["PRAGMA user_version = 99"]))
        (is (str/includes? (refusal #(restore/restore! dirs (str newer) now)) "is at schema v99"))
        (is (str/includes? (refusal #(restore/restore! dirs "missing.db" now)) "no snapshot at missing.db"))))
    (testing "a snapshot named in backups replaces the store, which is moved aside"
      (let [{:keys [moved-aside]} (restore/restore! dirs "daily-2026-11-08.db" now)]
        (is (= "pre-restore-20261109T100000.000Z.db" (.getName moved-aside)))
        (is (= ["CLO" "LATER"] (keys-in moved-aside)))
        (is (= ["CLO"] (keys-in (fx/store-file home))))))
    (testing "the lock is free after, and the restored store opens"
      (store/close (fx/open home))
      (is (nil? (lock/holder (paths/lock-file dirs)))))))

(deftest restore-refuses-occupied-destinations-and-rolls-back
  (let [home (fx/tmp-dir)
        dirs (paths/dirs {"CLOISTERS_HOME" (str home)} "/nowhere")
        snap (io/file (fx/backups home) "daily-2026-11-08.db")
        aside (io/file (fx/backups home) "pre-restore-20261109T100000.000Z.db")]
    (let [st (fx/open home)]
      (store/write! st #(fx/project! % "CLO"))
      (store/daily-snapshot! st)
      (.renameTo (first (.listFiles (fx/backups home))) snap)
      (store/write! st #(fx/project! % "LATER"))
      (store/close st))
    (testing "an occupied aside destination is refused before anything moves"
      (.mkdirs aside)
      (is (str/includes? (refusal #(restore/restore! dirs (str snap) now)) "already exists"))
      (is (= ["CLO" "LATER"] (keys-in (fx/store-file home))))
      (.delete aside))
    (testing "a failure moving the snapshot into place leaves the store as it was"
      (let [real-move @#'restore/move!
            target (fx/store-file home)
            failed (atom false)]
        (is (thrown? Exception
                     (with-redefs-fn {#'restore/move! (fn [from to]
                                                        (if (and (= (str target) (str to))
                                                                 (compare-and-set! failed false true))
                                                          (throw (ex-info "injected move failure" {}))
                                                          (real-move from to)))}
                       (fn [] (restore/restore! dirs (str snap) now)))))
        (is (= ["CLO" "LATER"] (keys-in (fx/store-file home))))
        (is (false? (.exists (io/file (str target ".restoring")))))))))

(deftest exporting-offline
  (let [home (fx/tmp-dir)
        dirs (paths/dirs {"CLOISTERS_HOME" (str home)} "/nowhere")
        out (io/file home "out" "export.jsonl")]
    (is (str/starts-with? (refusal #(main/export-to dirs out)) "no store at"))
    (let [st (fx/open home)]
      (store/write! st #(fx/project! % "CLO"))
      (testing "it reads the store while the daemon has it open"
        (main/export-to dirs out)
        (is (str/starts-with? (slurp out) "{\"type\":\"project\",\"key\":\"CLO\"")))
      (store/close st))))
