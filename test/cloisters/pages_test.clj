(ns cloisters.pages-test
  (:require [clojure.test :refer [deftest is testing]]
            [cloisters.events :as events]
            [cloisters.fixtures :as fx :refer [rejection with-ctx]]
            [cloisters.issues :as issues]
            [cloisters.pages :as pages]
            [cloisters.projects :as projects]))

(defn- new! [c title & {:as args}]
  (:id (pages/create-page c (assoc args :title title))))

(defn- get-page [c id & {:as args}] (pages/get-page c (assoc args :id id)))

(defn- all-events [c] (:events (events/feed c {:limit 200})))

(defn- archive! [c id] (pages/archive-page c {:id id :summary "stale"}))

(deftest creating-and-revisions
  (with-ctx [c (fx/ctx)]
    (let [r (pages/create-page c {:title "Glossary" :body "Words." :summary "start"})
          id (:id r)]
      (testing "a page is r1, from the shared sequence"
        (is (= {:id "CLO-1" :kind "page" :title "Glossary" :archived false :rank 1.0 :revision 1 :version 1}
               (select-keys r [:id :kind :title :archived :rank :revision :version])))
        (is (= "CLO-2" (:id (issues/create-issue c {:title "An issue"})))))
      (testing "the create event is r1 and carries the summary"
        (is (= [1 "start"] ((juxt :revision :summary) (first (all-events c))))))
      (testing "only title and body changes make revisions; the version is the latest revision"
        (pages/update-page c {:id id :body "More words." :summary "expand"})
        (pages/update-page c {:id id :rank 7.0})
        (pages/update-page c {:id id :title "Glossary of terms"})
        (is (= 3 (:version (get-page c id))))
        (is (= [[1 "start"] [2 "expand"] [3 nil]] (map (juxt :revision :summary) (pages/page-revisions c {:id id}))))
        (is (= [nil 7.0] ((juxt :revision #(-> % :changes first :new parse-double)) (nth (all-events c) 3)))))
      (testing "an earlier revision reads on its own"
        (is (= {:title "Glossary" :body "Words." :revision 1 :version 3}
               (select-keys (get-page c id :revision 1) [:title :body :revision :version])))
        (is (= "not_found" (:error (rejection #(get-page c id :revision 4))))))
      (testing "include revisions gives the newest with a total"
        (is (= [3 false] ((juxt :revisions-total :revisions-truncated) (get-page c id :include ["revisions"])))))
      (testing "a save that changes nothing records nothing"
        (let [n (count (all-events c))]
          (pages/update-page c {:id id :title "Glossary of terms" :summary "noop"})
          (is (= n (count (all-events c)))))))))

(deftest wrong-kind
  (with-ctx [c (fx/ctx)]
    (let [page (new! c "Page")
          issue (:id (issues/create-issue c {:title "Issue"}))]
      (is (= {:error "wrong_kind" :id page :kind "page"} (rejection #(issues/get-issue c {:id page}))))
      (is (= {:error "wrong_kind" :id issue :kind "issue"} (rejection #(get-page c issue))))
      (is (= "wrong_kind" (:error (rejection #(new! c "Child" :parent issue)))))
      (is (= "wrong_kind" (:error (rejection #(issues/update-issue c {:id issue :parent page}))))))))

(deftest stale-page-edits
  (with-ctx [c (fx/ctx)]
    (let [id (new! c "Plan" :body "v1")]
      (pages/update-page c {:id id :body "v2 by an agent"})
      (testing "a stale save is refused with the current version, and title and body as touched"
        (is (= {:error "stale_version" :id id :version 2 :title nil :body "v2 by an agent"}
               (rejection #(pages/update-page c {:id id :expected-version 1 :body "v2 by a human"}))))
        (is (= {:error "stale_version" :id id :version 2 :title "Plan" :body nil}
               (rejection #(pages/update-page c {:id id :expected-version 1 :title "Renamed"})))))
      (testing "the hand-merged save against the new version goes through"
        (is (= 3 (:version (pages/update-page c {:id id :expected-version 2 :body "v2 merged"})))))
      (testing "body edits need no version"
        (is (= 4 (:version (pages/update-page c {:id id :body-edits [{:old "merged" :new "final"}]}))))
        (is (= "v2 final" (:body (get-page c id)))))
      (testing "archived pages stay editable, under the same guard"
        (archive! c id)
        (is (= "stale_version" (:error (rejection #(pages/update-page c {:id id :expected-version 3 :body "x"})))))
        (is (= 5 (:version (pages/update-page c {:id id :expected-version 4 :body "x"}))))))))

(deftest moves
  (with-ctx [c (fx/ctx)]
    (let [a (new! c "A")
          b (new! c "B")
          child (new! c "Child" :parent a)
          grandchild (new! c "Grandchild" :parent child)
          updated #(:updated (get-page c %))
          before [(updated a) (updated b)]]
      (testing "a move keeps the page's rank and shows on the page, its old parent and its new parent"
        (Thread/sleep 5)
        (let [r (pages/update-page c {:id child :parent b})]
          (is (= [b 3.0 1] ((juxt :parent :rank :version) r))))
        (is (= [a b] (:related (last (all-events c)))))
        (is (every? true? (map not= before [(updated a) (updated b)])))
        (is (= [child] (map :id (:children (get-page c b)))))
        (is (empty? (:children (get-page c a)))))
      (testing "a move may set a rank among the new siblings"
        (let [other (new! c "Other" :parent a)]
          (is (= 2.0 (:rank (pages/update-page c {:id other :parent b :rank-before child}))))
          (is (= [other child] (map :id (:children (get-page c b)))))
          (testing "a relative rank must name a sibling"
            (is (= "invalid_argument" (:error (rejection #(pages/update-page c {:id other :rank-after a}))))))))
      (testing "null makes a root"
        (is (nil? (:parent (pages/update-page c {:id grandchild :parent nil}))))
        (pages/update-page c {:id grandchild :parent child}))
      (testing "a page cannot move beneath itself or a descendant"
        (is (= {:error "cycle" :path [b grandchild child b]}
               (rejection #(pages/update-page c {:id b :parent grandchild}))))
        (is (= {:error "cycle" :path [b b]} (rejection #(pages/update-page c {:id b :parent b})))))
      (testing "a page tree never crosses projects"
        (projects/create-project c {:key "OTH" :name "Other"})
        (let [there (new! c "There" :project "OTH")]
          (is (= {:error "cross_project_edge" :id a :project "CLO" :other there :other-project "OTH"}
                 (rejection #(pages/update-page c {:id a :parent there}))))
          (is (= "cross_project_edge" (:error (rejection #(new! c "x" :project "CLO" :parent there))))))))))

(deftest archiving
  (with-ctx [c (fx/ctx)]
    (let [parent (new! c "Parent")
          child (new! c "Child" :parent parent)]
      (testing "archiving and unarchiving require a summary"
        (is (= {:error "invalid_argument" :argument "summary"}
               (select-keys (rejection #(pages/archive-page c {:id child})) [:error :argument])))
        (is (= "invalid_argument" (:error (rejection #(pages/unarchive-page c {:id child :summary " "}))))))
      (testing "a page with live children cannot be archived; the refusal names them"
        (is (= {:error "live_children" :id parent :children [{:id child :title "Child"}]}
               (rejection #(archive! c parent)))))
      (testing "archive the children first; nothing cascades"
        (is (true? (:archived (archive! c child))))
        (is (true? (:archived (archive! c parent))))
        (is (= "stale" (:summary (last (all-events c))))))
      (testing "archiving an archived page changes nothing"
        (let [n (count (all-events c))]
          (archive! c parent)
          (is (= n (count (all-events c))))))
      (testing "no live page beneath an archived parent: unarchive, create or move"
        (is (= {:error "archived_parent" :parent {:id parent :title "Parent"}}
               (rejection #(pages/unarchive-page c {:id child :summary "back"}))))
        (is (= "archived_parent" (:error (rejection #(new! c "New" :parent parent)))))
        (let [live (new! c "Live")]
          (is (= "archived_parent" (:error (rejection #(pages/update-page c {:id live :parent parent})))))))
      (testing "an archived page may move beneath an archived parent"
        (let [other (new! c "Other")]
          (archive! c other)
          (is (= parent (:parent (pages/update-page c {:id other :parent parent}))))))
      (testing "unarchive the parent, then the child"
        (is (false? (:archived (pages/unarchive-page c {:id parent :summary "back"}))))
        (is (false? (:archived (pages/unarchive-page c {:id child :summary "back"}))))
        (testing "archiving makes no revision"
          (is (= 1 (:version (get-page c child)))))))))

(deftest restoring
  (with-ctx [c (fx/ctx)]
    (let [parent (new! c "Parent")
          id (new! c "Doc" :body "first")]
      (pages/update-page c {:id id :title "Doc v2" :body "second"})
      (pages/update-page c {:id id :parent parent})
      (archive! c id)
      (testing "restoring rN makes a new revision with rN's content, recording where it came from"
        (let [r (pages/restore-page c {:id id :revision 1 :summary "revert"})]
          (is (= 3 (:version r)))
          (is (= [parent true] ((juxt :parent :archived) r))))
        (is (= {:title "Doc" :body "first"} (select-keys (get-page c id) [:title :body])))
        (let [e (last (all-events c))]
          (is (= [3 1 "revert"] ((juxt :revision :restored-from :summary) e)))))
      (testing "restoring content identical to the current is a no-op"
        (let [n (count (all-events c))]
          (is (= 3 (:version (pages/restore-page c {:id id :revision 3}))))
          (is (= 3 (:version (pages/restore-page c {:id id :revision 1}))))
          (is (= n (count (all-events c))))))
      (testing "restore takes an expected version, and refuses an unknown revision"
        (is (= "stale_version" (:error (rejection #(pages/restore-page c {:id id :revision 2 :expected-version 2})))))
        (is (= {:error "not_found" :id id :revision 9} (rejection #(pages/restore-page c {:id id :revision 9}))))
        (is (= 4 (:version (pages/restore-page c {:id id :revision 2 :expected-version 3}))))))))

(deftest diffs
  (with-ctx [c (fx/ctx)]
    (let [id (new! c "Doc" :body "a\nb\nc")]
      (pages/update-page c {:id id :body "a\nB\nc"})
      (pages/update-page c {:id id :title "Doc 2"})
      (testing "by default the latest against the one before"
        (is (= {:id id :from 2 :to 3 :title-before "Doc" :title-after "Doc 2" :diff ""} (pages/diff-page c {:id id}))))
      (testing "any two revisions, body as a unified line diff"
        (let [d (pages/diff-page c {:id id :from 1 :to 2})]
          (is (= (str "--- " id " r1\n+++ " id " r2\n@@ -1,3 +1,3 @@\n a\n-b\n+B\n c") (:diff d)))))
      (testing "r1 against nothing"
        (is (= nil (:title-before (pages/diff-page c {:id id :to 1})))))
      (testing "an unknown revision is not_found"
        (is (= "not_found" (:error (rejection #(pages/diff-page c {:id id :from 5})))))))))
