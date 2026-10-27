(ns cloisters.store-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloisters.backup :as backup]
            [cloisters.fixtures :as fx]
            [cloisters.store :as store]
            [next.jdbc :as jdbc])
  (:import (java.io File RandomAccessFile)
           (java.sql SQLException)
           (java.time LocalDate)))

(defn- pragma [conn p]
  (-> (jdbc/execute-one! conn [(str "PRAGMA " p)]) vals first))

(defn- snapshots [home]
  (sort (map #(.getName ^File %) (.listFiles (fx/backups home)))))

(defmacro ^:private with-store
  "with-open for a store."
  [[sym expr] & body]
  `(let [~sym ~expr]
     (try ~@body (finally (store/close ~sym)))))

(deftest fresh-store
  (let [home (fx/tmp-dir)]
    (with-store [st (fx/open home)]
      (testing "migrates an empty store to the bundled schema"
        (is (= (count store/migration-files) (:version st)))
        (is (= (count store/migration-files) (store/user-version (store/reads st)))))
      (testing "takes no snapshot of an empty store"
        (is (empty? (snapshots home))))
      (testing "the writer runs WAL, synchronous=FULL and foreign keys"
        (store/write! st (fn [tx]
                           (is (= "wal" (pragma tx "journal_mode")))
                           (is (= 2 (pragma tx "synchronous")))
                           (is (= 1 (pragma tx "foreign_keys"))))))
      (testing "readers enforce foreign keys and refuse writes"
        (with-open [conn (jdbc/get-connection (store/reads st))]
          (is (= 1 (pragma conn "foreign_keys")))
          (is (thrown-with-msg? SQLException #"readonly"
                                (fx/project! conn "CLO"))))))
    (testing "a clean close checkpoints the WAL"
      (let [wal (io/file (str (fx/store-file home) "-wal"))]
        (is (or (not (.exists wal)) (zero? (.length wal))))))
    (testing "reopening an up-to-date store changes nothing"
      (with-store [st (fx/open home)]
        (is (= (count store/migration-files) (:version st)))
        (is (empty? (snapshots home)))))))

(deftest shared-id-space
  (let [home (fx/tmp-dir)]
    (with-store [st (fx/open home)]
      (store/write! st #(fx/project! % "CLO"))
      (testing "issues and pages draw numbers from one sequence"
        (is (= [1 2 3 4]
               [(store/write! st #(fx/issue! % "CLO" "an issue"))
                (store/write! st #(fx/page! % "CLO" "a page"))
                (store/write! st #(fx/issue! % "CLO" "another issue"))
                (store/write! st #(fx/page! % "CLO" "another page"))]))
        (is (= ["issue" "page" "issue" "page"]
               (map :kind (fx/query (store/reads st) {:select [:kind] :from [:item] :order-by [:number]})))))
      (testing "an id names exactly one thing"
        (is (thrown? SQLException
                     (store/write! st #(fx/insert! % :page {:project "CLO" :number 1 :title "squatter" :rank 1
                                                            :created fx/now :updated fx/now}))))
        (is (thrown? SQLException (store/write! st #(fx/insert! % :item {:project "CLO" :number 2 :kind "issue"})))))
      (testing "a refused create spends no number"
        (is (thrown? SQLException
                     (store/write! st (fn [tx]
                                        (let [n (store/allocate! tx "CLO" :issue)]
                                          (fx/insert! tx :issue {:project "CLO" :number n :title ""
                                                                 :rank n :created fx/now :updated fx/now}))))))
        (is (= 5 (store/write! st #(fx/issue! % "CLO" "after the refusal")))))
      (testing "sequences are per project"
        (store/write! st #(fx/project! % "WF2"))
        (is (= 1 (store/write! st #(fx/page! % "WF2" "first in WF2")))))
      (testing "an unknown project is refused"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no project NOPE"
                              (store/write! st #(store/allocate! % "NOPE" :issue))))))))

(deftest concurrent-writes-queue
  (let [home (fx/tmp-dir)]
    (with-store [st (fx/open home)]
      (store/write! st #(fx/project! % "CLO"))
      (let [numbers (->> (range 40)
                         (mapv (fn [i] (future (store/write! st #(if (even? i)
                                                                   (fx/issue! % "CLO" (str "issue " i))
                                                                   (fx/page! % "CLO" (str "page " i)))))))
                         (mapv deref))]
        (is (= (range 1 41) (sort numbers)))))))

(deftest schema-constraints
  (let [home (fx/tmp-dir)]
    (with-store [st (fx/open home)]
      (let [refused? (fn [f] (try (store/write! st f) false (catch SQLException _ true)))]
        (testing "project keys are 2-10 uppercase letters or digits, starting with a letter"
          (is (every? #(refused? (fn [tx] (fx/project! tx %))) ["C" "clo" "1CL" "CL-O" "ABCDEFGHIJK"]))
          (is (not (refused? #(fx/project! % "WF2")))))
        (store/write! st #(fx/project! % "CLO"))
        (let [n (store/write! st #(fx/issue! % "CLO" "an issue"))
              p (store/write! st #(fx/page! % "CLO" "a page"))]
          (testing "status and resolution agree"
            (is (refused? #(fx/query % {:update :issue :set {:status "closed"} :where [:= :number n]})))
            (is (refused? #(fx/query % {:update :issue :set {:resolution "done"} :where [:= :number n]})))
            (is (not (refused? #(fx/query % {:update :issue :set {:status "closed" :resolution "dropped" :closed fx/now}
                                             :where [:= :number n]})))))
          (testing "an assignee comes with its claim time"
            (is (refused? #(fx/query % {:update :issue :set {:assignee "saqib/wf-7f3a"} :where [:= :number n]}))))
          (testing "labels are case-insensitive"
            (store/write! st #(fx/insert! % :label {:project "CLO" :number n :label "Bug"}))
            (is (refused? #(fx/insert! % :label {:project "CLO" :number n :label "bug"}))))
          (testing "labels and blocking edges are for issues only"
            (is (refused? #(fx/insert! % :label {:project "CLO" :number p :label "bug"})))
            (is (refused? #(fx/insert! % :blocks {:project "CLO" :blocker n :blocked p}))))
          (testing "parents stay within one kind"
            (is (refused? #(fx/query % {:update :page :set {:parent n} :where [:= :number p]}))))
          (testing "comments ride on issue events, edit summaries and revisions on page events"
            (let [event (fn [m] (merge {:project "CLO" :at fx/now :actor "cli"} m))]
              (is (refused? #(fx/insert! % :event (event {:number p :kind "page" :comment "hi"}))))
              (is (refused? #(fx/insert! % :event (event {:number n :kind "issue" :summary "hi"}))))
              (is (refused? #(fx/insert! % :event (event {:number n :kind "issue" :revision 2}))))
              (is (refused? #(fx/insert! % :event (event {:number n :kind "page" :summary "lies about its kind"}))))
              (is (refused? #(fx/insert! % :event (event {:number p :kind "page" :revision 2 :restored_from 2}))))
              (is (not (refused? #(fx/insert! % :event (event {:number p :kind "page" :revision 3
                                                               :restored_from 2 :summary "restored"})))))
              (is (refused? #(fx/insert! % :event (event {:number p :kind "page" :revision 3})))
                  "one revision number per page"))))))))

(deftest one-text-index-over-both-kinds
  (let [home (fx/tmp-dir)]
    (with-store [st (fx/open home)]
      (store/write! st #(fx/project! % "CLO"))
      (let [n (store/write! st #(fx/issue! % "CLO" "Reclaim stale claims"))
            p (store/write! st #(fx/page! % "CLO" "Glossary"))
            search (fn [term]
                     (map (juxt :project :number)
                          (fx/query (store/reads st)
                                    {:select [:project :number] :from [:item-text]
                                     :where [:raw ["item_text MATCH " [:lift (str "\"" term "\"")]]]
                                     :order-by [:number]})))]
        (testing "text: is a case-insensitive substring match"
          (is (= [["CLO" n]] (search "CLAIM"))))
        (testing "edits to title and body follow"
          (store/write! st #(fx/query % {:update :page :set {:body "The frontier.sh script"} :where [:= :number p]}))
          (is (= [["CLO" p]] (search "frontier.sh")))
          (store/write! st #(fx/query % {:update :issue :set {:title "Expire nothing"} :where [:= :number n]}))
          (is (empty? (search "claim"))))
        (testing "an issue's comments are searchable"
          (store/write! st #(fx/insert! % :event {:project "CLO" :number n :kind "issue" :at fx/now :actor "cli"
                                                  :comment "first remark"}))
          (store/write! st #(fx/insert! % :event {:project "CLO" :number n :kind "issue" :at fx/now :actor "cli"
                                                  :comment "second remark"}))
          (is (= [["CLO" n]] (search "first rem")))
          (is (= [["CLO" n]] (search "second rem"))))))))

(def ^:private v2 "ALTER TABLE project ADD COLUMN motto TEXT;\nUPDATE project SET motto = 'migrated';\n")

(deftest migrating-a-non-empty-store
  (let [home (fx/tmp-dir)
        v1 (store/bundled-migrations)]
    (with-store [st (fx/open home v1)]
      (store/write! st (fn [tx] (fx/project! tx "CLO") (fx/issue! tx "CLO" "kept") (fx/page! tx "CLO" "kept too"))))
    (with-store [st (fx/open home (conj v1 v2))]
      (testing "migrates to the new version, keeping the rows"
        (is (= 2 (:version st)))
        (is (= [{:key "CLO" :motto "migrated" :next-number 3}]
               (fx/query (store/reads st) {:select [:key :motto :next-number] :from [:project]})))))
    (testing "the snapshot taken first reopens at the old version, with its rows"
      (let [[snap & more] (snapshots home)]
        (is (nil? more))
        (is (re-matches #"\d{8}T\d{6}\.\d{3}Z-v1\.db" snap))
        (with-open [conn (jdbc/get-connection {:jdbcUrl (str "jdbc:sqlite:" (io/file (fx/backups home) snap))})]
          (is (= 1 (store/user-version conn)))
          (is (= ["issue" "page"] (map :kind (fx/query conn {:select [:kind] :from [:item] :order-by [:number]})))))))))

(deftest a-failed-migration-changes-nothing
  (let [home (fx/tmp-dir)
        v1 (store/bundled-migrations)]
    (with-store [st (fx/open home v1)]
      (store/write! st #(fx/project! % "CLO")))
    (is (thrown? SQLException (fx/open home (conj v1 (str v2 "SELECT * FROM no_such_table;\n")))))
    (with-store [st (fx/open home v1)]
      (is (= 1 (:version st)))
      (is (= [:created :key :name :next-number]
             (sort (keys (first (fx/query (store/reads st) {:select [:*] :from [:project]}))))))
      (is (= 1 (count (snapshots home)))))))

(deftest a-newer-store-is-refused
  (let [home (fx/tmp-dir)
        v1 (store/bundled-migrations)]
    (store/close (fx/open home (conj v1 v2)))
    (let [e (try (fx/open home v1) nil (catch clojure.lang.ExceptionInfo e e))]
      (is (= "the store is at schema v2, but this daemon knows only up to v1; run a newer daemon" (ex-message e)))
      (is (= {:store-version 2 :daemon-version 1} (ex-data e))))))

(deftest a-corrupt-store-is-refused
  (let [home (fx/tmp-dir)]
    (with-store [st (fx/open home)]
      (store/write! st #(fx/project! % "CLO"))
      (is (store/daily-snapshot! st)))
    (with-open [raf (RandomAccessFile. (fx/store-file home) "rw")]
      (.write raf (.getBytes "this is not a SQLite header")))
    (let [snap (str (io/file (fx/backups home) (str "daily-" (LocalDate/now) ".db")))
          e (try (fx/open home) nil (catch clojure.lang.ExceptionInfo e e))]
      (is (str/starts-with? (ex-message e) (str "the store " (fx/store-file home) " failed its integrity check")))
      (is (str/ends-with? (ex-message e) (str "with the daemon stopped, run: cloisters daemon restore " snap)))
      (is (= snap (:snapshot (ex-data e)))))))

(deftest daily-snapshots
  (let [home (fx/tmp-dir)]
    (with-store [st (fx/open home)]
      (store/write! st #(fx/project! % "CLO"))
      (let [day (fn [d] (with-open [conn (jdbc/get-connection {:jdbcUrl (str "jdbc:sqlite:" (fx/store-file home))})]
                          (backup/daily-snapshot! conn (fx/backups home) (LocalDate/parse d))))]
        (testing "a snapshot passes quick_check and holds the rows"
          (let [f (day "2026-10-01")]
            (is (nil? (backup/check-file f)))
            (with-open [conn (jdbc/get-connection {:jdbcUrl (str "jdbc:sqlite:" f)})]
              (is (= ["CLO"] (map :key (fx/query conn {:select [:key] :from [:project]})))))))
        (testing "a second snapshot on one day replaces the first"
          (day "2026-10-01")
          (is (= ["daily-2026-10-01.db"] (snapshots home))))
        (testing "the last seven dailies are kept, and every migration snapshot"
          (spit (io/file (fx/backups home) "20260901T000000.000Z-v1.db") "kept whatever it holds")
          (doseq [d (range 2 11)] (day (format "2026-10-%02d" d)))
          (is (= (cons "20260901T000000.000Z-v1.db" (map #(format "daily-2026-10-%02d.db" %) (range 4 11)))
                 (snapshots home))))
        (testing "the newest passing snapshot skips a newer one that fails"
          (let [bad (io/file (fx/backups home) "20261011T000000.000Z-v1.db")]
            (spit bad "not a database")
            (.setLastModified bad (+ (System/currentTimeMillis) 60000))
            (is (= "daily-2026-10-10.db" (.getName (backup/newest-passing (fx/backups home)))))))))))
