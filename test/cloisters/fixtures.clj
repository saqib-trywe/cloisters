(ns cloisters.fixtures
  "Temporary homes and stores for tests, always against a real SQLite file."
  (:require [clojure.java.io :as io]
            [cloisters.store :as store]
            [honey.sql :as sql]
            [next.jdbc :as jdbc])
  (:import (java.io File)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn tmp-dir ^File []
  (.toFile (Files/createTempDirectory "cloisters-test" (make-array FileAttribute 0))))

(defn store-file ^File [^File home] (io/file home "data" "cloisters.db"))
(defn backups ^File [^File home] (io/file home "data" "backups"))

(defn open
  ([home] (store/open (store-file home) (backups home)))
  ([home migrations] (store/open (store-file home) (backups home) migrations)))

(def now "2026-10-27T10:00:00.000Z")

(defn insert! [tx table row]
  (jdbc/execute-one! tx (sql/format {:insert-into table :values [row]})))

(defn query [connectable q]
  (jdbc/execute! connectable (sql/format q) store/opts))

(defn project! [tx key]
  (insert! tx :project {:key key :name (str key " project") :created now}))

(defn issue! [tx project title]
  (let [n (store/allocate! tx project :issue)]
    (insert! tx :issue {:project project :number n :title title :rank n :created now :updated now})
    n))

(defn page! [tx project title]
  (let [n (store/allocate! tx project :page)]
    (insert! tx :page {:project project :number n :title title :rank n :created now :updated now})
    n))
