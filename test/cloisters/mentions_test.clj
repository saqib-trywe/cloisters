(ns cloisters.mentions-test
  (:require [clojure.test :refer [deftest is testing]]
            [cloisters.events :as events]
            [cloisters.fixtures :as fx :refer [with-ctx]]
            [cloisters.issues :as issues]
            [cloisters.markdown :as markdown]
            [cloisters.pages :as pages]
            [cloisters.projects :as projects]))

(defn- ids [refs] (mapv :id refs))

(deftest detection
  (testing "the token needs a boundary of anything but letters, digits, _ and -"
    (is (= [["TIK" 42]] (markdown/mentions "TIK-42")))
    (is (= [["TIK" 42] ["TIK" 43] ["TIK" 44]] (markdown/mentions "(TIK-42) TIK-43. see /i/TIK-44")))
    (is (empty? (markdown/mentions "tik-42 xTIK-42 TIK-42s TIK-42-fix TIK-042 _TIK-42 T-1"))))
  (testing "code never counts"
    (is (empty? (markdown/mentions "`TIK-1`\n\n```\nTIK-2\n```\n\n    TIK-3 indented\n\n$TIK-4$"))))
  (testing "a link counts when its path is exactly /KEY-N, whatever the host"
    (is (= [["CLO" 7]] (markdown/mentions "[the glossary](/CLO-7)")))
    (is (= [["CLO" 7]] (markdown/mentions "[x](http://127.0.0.1:7227/CLO-7?tab=1#top)")))
    (is (empty? (markdown/mentions "[x](/CLO-7/history) [y](/i/x)"))))
  (testing "image alt text and raw HTML are not prose"
    (is (empty? (markdown/mentions "![CLO-1](http://x/y.png) <span title=\"CLO-2\"></span>"))))
  (testing "titles are plain text"
    (is (= [["CLO" 1]] (markdown/title-mentions "Follow-up to `CLO-1`")))))

(deftest backlinks-both-ways
  (with-ctx [c (fx/ctx)]
    (let [issue (:id (issues/create-issue c {:title "Build the thing" :body "See the plan CLO-2."}))
          page (:id (pages/create-page c {:title "Plan" :body "Tracks [the ticket](/CLO-1), not `CLO-1` alone."}))]
      (testing "an issue lists the pages that mention it, and a page the issues, each with its kind"
        (is (= [{:id page :kind "page" :title "Plan" :archived false}]
               (:backlinks (issues/get-issue c {:id issue}))))
        (is (= [{:id issue :kind "issue" :title "Build the thing" :status "open"}]
               (:backlinks (pages/get-page c {:id page})))))
      (testing "mention lists say when a page is archived"
        (pages/archive-page c {:id page :summary "done"})
        (is (= [true] (map :archived (:mentions (issues/get-issue c {:id issue}))))))
      (testing "editing an id out removes the backlink, and writes no event on the mentioned item"
        (let [n (count (:events (events/feed c {})))]
          (issues/update-issue c {:id issue :body "Nothing here."})
          (is (empty? (:backlinks (pages/get-page c {:id page}))))
          (is (= (inc n) (count (:events (events/feed c {})))))))
      (testing "a mention in a comment is permanent"
        (issues/update-issue c {:id issue :comment "Wrote CLO-2 up."})
        (issues/update-issue c {:id issue :title "Build it"})
        (is (= [issue] (ids (:backlinks (pages/get-page c {:id page}))))))
      (testing "nothing mentions itself"
        (pages/update-page c {:id page :body "I am CLO-2."})
        (is (empty? (:mentions (pages/get-page c {:id page}))))))))

(deftest forward-references
  (with-ctx [c (fx/ctx)]
    (let [issue (:id (issues/create-issue c {:title "Plan ahead" :body "Then CLO-3, and NOPE-1."}))]
      (testing "a mention of an id not created yet is listed only once it exists"
        (is (empty? (:mentions (issues/get-issue c {:id issue})))))
      (issues/create-issue c {:title "Filler"})
      (pages/create-page c {:title "Arrives later"})
      (testing "creating it makes the stored forward reference a live backlink"
        (is (= ["CLO-3"] (ids (:mentions (issues/get-issue c {:id issue})))))
        (is (= [issue] (ids (:backlinks (pages/get-page c {:id "CLO-3"})))))))))

(deftest project-rescans
  (with-ctx [c (fx/ctx)]
    (let [issue (:id (issues/create-issue c {:title "Depends on NEW-1" :comment "and NEW-2"}))
          page (:id (pages/create-page c {:title "Notes" :body "Upstream: NEW-1, `NEW-3`."}))]
      (testing "a token whose project does not exist does not count"
        (is (empty? (:mentions (issues/get-issue c {:id issue})))))
      (testing "creating the project rescans all prose for its key, in the same write"
        (projects/create-project c {:key "NEW" :name "New"})
        (let [one (:id (issues/create-issue c {:title "One" :project "NEW"}))
              two (:id (pages/create-page c {:title "Two" :project "NEW"}))]
          (is (= [issue page] (ids (:backlinks (issues/get-issue c {:id one})))))
          (is (= [issue] (ids (:backlinks (pages/get-page c {:id two}))))))))))
