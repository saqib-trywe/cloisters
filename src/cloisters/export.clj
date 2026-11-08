(ns cloisters.export
  "The export: JSON Lines in the contract's own types, one record per line, each with a type. Projects by key; then
  issues and pages interleaved by project and number, which is creation order; then every event by sequence, so the
  log reads as the log. Two exports of one store are byte-identical. There is no import."
  (:require [cloisters.core :as core :refer [q]]
            [cloisters.events :as events]
            [cloisters.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [next.jdbc :as jdbc])
  (:import (java.io File Writer)))

(defn- record
  "An ordered record without its nil fields, so every line reads type first and identically each time."
  [& kvs]
  (apply array-map (apply concat (filter (comp some? second) (partition 2 kvs)))))

(defn- parent-id [{:keys [project parent]}]
  (when parent (core/id-str [project parent])))

(defn- issue-record [conn {:keys [project number] :as i}]
  (let [ref [project number]]
    (record :type "issue" :id (core/id-str ref) :title (:title i) :body (:body i) :status (:status i)
            :resolution (:resolution i) :closed (:closed i) :assignee (:assignee i) :claimed (:claimed i)
            :labels (vec (sort-by str/lower-case (map :label (q conn {:select [:label] :from :label
                                                                      :where [:and [:= :project project]
                                                                              [:= :number number]]}))))
            :parent (parent-id i)
            :blocked-by (mapv #(core/id-str [project (:blocker %)])
                              (q conn {:select [:blocker] :from :blocks
                                       :where [:and [:= :project project] [:= :blocked number]]
                                       :order-by [:blocker]}))
            :rank (:rank i) :version (:version i) :created (:created i) :updated (:updated i))))

(defn- page-record [{:keys [project number] :as p}]
  (record :type "page" :id (core/id-str [project number]) :title (:title p) :body (:body p) :parent (parent-id p)
          :rank (:rank p) :archived (= 1 (:archived p)) :version (:version p) :created (:created p)
          :updated (:updated p)))

(defn- event-record [{:keys [changes] :as e}]
  (record :type "event" :seq (:seq e) :id (:id e) :kind (:kind e) :at (:at e) :actor (:actor e)
          :comment (:comment e) :summary (:summary e) :revision (:revision e) :restored-from (:restored-from e)
          :changes (mapv #(record :field (:field %) :op (:op %) :old (:old %) :new (:new %)) changes)
          :related (:related e)))

(defn records
  "Every export record, in order, read through conn."
  [conn]
  (concat
   (for [p (q conn {:select [:key :name :created] :from :project :order-by [:key]})]
     (record :type "project" :key (:key p) :name (:name p) :created (:created p)))
   (for [{:keys [project number kind]} (q conn {:select :* :from :item :order-by [:project :number]})]
     (case kind
       "issue" (issue-record conn (core/by-ref conn :issue [project number]))
       "page" (page-record (core/by-ref conn :page [project number]))))
   (map event-record (events/load-events conn true))))

(defn export!
  "Writes every record through conn to w, one JSON object per line, in one read transaction."
  [conn ^Writer w]
  (jdbc/with-transaction [tx conn]
    (doseq [r (records tx)]
      (.write w ^String (json/write-str r))
      (.write w "\n")))
  (.flush w))

(defn export-file!
  "Exports the store file to out, reading it read-only, whether or not a daemon has it open."
  [^File store-file ^File out]
  (with-open [conn (jdbc/get-connection {:jdbcUrl (str "jdbc:sqlite:" (.getPath store-file) "?open_mode=1")})
              w (io/writer out)]
    (export! conn w)))
