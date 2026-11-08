(ns cloisters.export-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloisters.export :as export]
            [cloisters.fixtures :as fx :refer [with-ctx]]
            [cloisters.issues :as issues]
            [cloisters.json :as json]
            [cloisters.pages :as pages]
            [cloisters.projects :as projects]
            [cloisters.store :as store]))

(defn- populate! [c]
  (projects/create-project c {:key "AB" :name "Second"})
  (let [a (:id (issues/create-issue c {:title "Issue" :body "Body" :labels ["x" "Y"]}))
        p (:id (pages/create-page c {:title "Page" :body "Mentions CLO-1"}))
        b (:id (issues/create-issue c {:title "Child" :parent a :blocked-by [a] :rank 0.5}))]
    (issues/create-issue c {:title "Elsewhere" :project "AB"})
    (issues/claim-issue c {:id b :assignee "s/1" :comment "mine"})
    (pages/update-page c {:id p :body "v2" :summary "edit"})
    (pages/restore-page c {:id p :revision 1})
    (pages/archive-page c {:id p :summary "old"})))

(defn- export-str [c]
  (let [w (java.io.StringWriter.)]
    (export/export! (store/reads (:store c)) w)
    (str w)))

(deftest deterministic-export
  (with-ctx [c (fx/ctx)]
    (populate! c)
    (let [out (export-str c)
          records (map json/read-str (str/split-lines out))]
      (testing "two exports of one store are byte-identical"
        (is (= out (export-str c))))
      (testing "projects, then issues and pages by project and number, then events by sequence"
        (is (= [["project" "AB"] ["project" "CLO"]] (map (juxt :type :key) (take 2 records))))
        (is (= [["issue" "AB-1"] ["issue" "CLO-1"] ["page" "CLO-2"] ["issue" "CLO-3"]]
               (map (juxt :type :id) (take 4 (drop 2 records)))))
        (let [events (drop 6 records)]
          (is (every? #(= "event" (:type %)) events))
          (is (= (sort (map :seq events)) (map :seq events)))))
      (testing "every line reads type first, with snake_case keys and no nulls"
        (is (every? #(str/starts-with? % "{\"type\":") (str/split-lines out)))
        (is (str/includes? out "\"blocked_by\":[\"CLO-1\"]"))
        (is (not (str/includes? out "null"))))
      (testing "records carry current state; page events carry their revision"
        (let [by-id (into {} (map (juxt :id identity)) (filter :id (take 6 records)))]
          (is (= {:type "page" :id "CLO-2" :title "Page" :body "Mentions CLO-1" :rank 2.0 :archived true :version 3}
                 (select-keys (by-id "CLO-2") [:type :id :title :body :rank :archived :version])))
          (is (= ["x" "Y"] (:labels (by-id "CLO-1"))))
          (is (= "s/1" (:assignee (by-id "CLO-3")))))
        (let [page-events (filter #(= "CLO-2" (:id %)) (drop 6 records))]
          (is (= [1 2 3 nil] (map :revision page-events)))
          (is (= 1 (:restored-from (nth page-events 2))))))
      (testing "comments stay events"
        (is (some #(= "mine" (:comment %)) records))))))

(deftest export-a-store-file
  (with-ctx [c (fx/ctx)]
    (populate! c)
    (let [file (:file (:store c))
          out (io/file (fx/tmp-dir) "export.jsonl")]
      (testing "reads the store file directly, beside a running daemon"
        (export/export-file! file out)
        (is (= (export-str c) (slurp out)))))))
