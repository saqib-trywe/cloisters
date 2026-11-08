(ns cloisters.issues-test
  (:require [clojure.test :refer [deftest is testing]]
            [cloisters.events :as events]
            [cloisters.fixtures :as fx :refer [rejection with-ctx]]
            [cloisters.issues :as issues]
            [cloisters.projects :as projects]))

(defn- new! [c title & {:as args}]
  (:id (issues/create-issue c (assoc args :title title))))

(defn- get-issue [c id] (issues/get-issue c {:id id}))

(defn- all-events [c] (:events (events/feed c {:limit 200})))

(defn- close! [c id resolution] (issues/close-issue c {:id id :resolution resolution :comment "why"}))

(deftest creating
  (with-ctx [c (fx/ctx)]
    (let [a (new! c "First")
          b (issues/create-issue c {:title "Second" :body "text" :labels ["Bug" "bug" "ux"] :parent a
                                    :blocked-by [a] :comment "made"})]
      (testing "ids come from the project's sequence, with rank defaulting to the number"
        (is (= "CLO-1" a))
        (is (= {:id "CLO-2" :kind "issue" :title "Second" :status "open" :labels ["Bug" "ux"] :parent "CLO-1"
                :blocked true :rank 2.0 :version 1}
               (select-keys b [:id :kind :title :status :labels :parent :blocked :rank :version]))))
      (testing "the create is one event: set for each field, add for each label and blocker, relating the parent and
                blocker"
        (let [e (last (all-events c))]
          (is (= "made" (:comment e)))
          (is (= ["CLO-1"] (:related e)))
          (is (= [["title" "set"] ["body" "set"] ["labels" "add"] ["labels" "add"] ["parent" "set"]
                  ["blocked_by" "add"]]
                 (map (juxt :field :op) (:changes e))))))
      (testing "get_issue shows edges as refs and comments in full"
        (let [i (get-issue c "CLO-2")]
          (is (= {:id "CLO-1" :kind "issue" :title "First" :status "open"} (:parent i)))
          (is (= ["CLO-1"] (map :id (:blocked-by i))))
          (is (= ["made"] (map :comment (:comments i))))
          (is (= ["CLO-2"] (map :id (:children (get-issue c "CLO-1")))))
          (is (= ["CLO-2"] (map :id (:blocking (get-issue c "CLO-1"))))))))))

(deftest refusals-for-arguments-and-ids
  (with-ctx [c (fx/ctx)]
    (new! c "An issue")
    (testing "the core validates arguments, naming the argument"
      (is (= {:error "invalid_argument" :argument "title"} (select-keys (rejection #(new! c "  ")) [:error :argument])))
      (is (= "invalid_argument" (:error (rejection #(new! c "two\nlines")))))
      (is (= {:error "invalid_argument" :argument "colour"}
             (select-keys (rejection #(issues/create-issue c {:title "x" :colour "red"})) [:error :argument])))
      (is (= "invalid_argument" (:error (rejection #(issues/update-issue c {:id "CLO-1" :body "a"
                                                                            :body-edits [{:old "a" :new "b"}]})))))
      (is (= "invalid_argument" (:error (rejection #(new! c "x" :rank 1 :rank-after "CLO-1"))))))
    (testing "an unbound create needs a project"
      (is (= {:error "invalid_argument" :argument "project"}
             (select-keys (rejection #(issues/create-issue (dissoc c :project) {:title "x"})) [:error :argument]))))
    (testing "an unknown project lists the ones that exist"
      (is (= {:error "unknown_project" :project "NOPE" :projects ["CLO"]}
             (select-keys (rejection #(new! c "x" :project "NOPE")) [:error :project :projects])))
      (is (= "unknown_project" (:error (rejection #(get-issue c "NOPE-1"))))))
    (testing "an unknown id is not_found"
      (is (= {:error "not_found" :id "CLO-9"} (rejection #(get-issue c "CLO-9")))))
    (testing "a refused create spends no number"
      (is (= "CLO-2" (new! c "Next"))))))

(deftest invariant-closed-issues-have-no-open-children
  (with-ctx [c (fx/ctx)]
    (let [parent (new! c "Parent")
          child (new! c "Child" :parent parent)]
      (testing "closing a parent with open children names them, for either resolution"
        (doseq [resolution ["done" "dropped"]]
          (is (= {:error "open_children" :id parent :children [{:id child :title "Child"}]}
                 (rejection #(close! c parent resolution))))))
      (close! c child "done")
      (close! c parent "done")
      (testing "no open child can appear under a closed parent: create, move or reopen"
        (is (= {:error "issue_closed" :id parent :resolution "done"}
               (select-keys (rejection #(new! c "Late" :parent parent)) [:error :id :resolution])))
        (let [other (new! c "Other")]
          (is (= "issue_closed" (:error (rejection #(issues/update-issue c {:id other :parent parent})))))
          (testing "a closed child may move under a closed parent"
            (close! c other "dropped")
            (is (= parent (:parent (issues/update-issue c {:id other :parent parent}))))))
        (is (= {:error "reopen_blocked" :id child :parent {:id parent :title "Parent"}}
               (rejection #(issues/reopen-issue c {:id child :comment "again"}))))))))

(deftest invariant-done-has-no-open-blockers
  (with-ctx [c (fx/ctx)]
    (let [blocker (new! c "Blocker")
          blocked (new! c "Blocked" :blocked-by [blocker])]
      (testing "done with an open blocker names it; dropped is exempt"
        (is (= {:error "open_blockers" :id blocked :blockers [{:id blocker :title "Blocker"}]}
               (rejection #(close! c blocked "done"))))
        (is (= "closed" (:status (close! c blocked "dropped")))))
      (let [done (new! c "Done")
            later (new! c "Later")]
        (close! c done "done")
        (testing "an open blocker cannot be added to an issue closed done"
          (is (= {:error "open_blockers" :blockers [{:id later :title "Later"}]}
                 (rejection #(issues/update-issue c {:id done :blocked-by-add [later]})))))
        (testing "reopening a blocker of an issue closed done names the dependent"
          (close! c later "done")
          (issues/update-issue c {:id done :blocked-by-add [later]})
          (is (= {:error "reopen_blocked" :id later :dependents [{:id done :title "Done"}]}
                 (rejection #(issues/reopen-issue c {:id later :comment "again"})))))))))

(deftest invariant-acyclic
  (with-ctx [c (fx/ctx)]
    (let [a (new! c "A")
          b (new! c "B" :parent a)
          d (new! c "D" :parent b)]
      (testing "hierarchy cycles are refused with the path"
        (is (= {:error "cycle" :path [a d b a]} (rejection #(issues/update-issue c {:id a :parent d}))))
        (is (= {:error "cycle" :path [a a]} (rejection #(issues/update-issue c {:id a :parent a})))))
      (testing "blocking cycles are refused with the path"
        (issues/update-issue c {:id b :blocked-by-add [a]})
        (issues/update-issue c {:id d :blocked-by-add [b]})
        (is (= {:error "cycle" :path [d a b d]} (rejection #(issues/update-issue c {:id a :blocked-by-add [d]}))))
        (is (= {:error "cycle" :path [a a]} (rejection #(issues/update-issue c {:id a :blocked-by-add [a]}))))))))

(deftest invariant-edges-stay-in-one-project
  (with-ctx [c (fx/ctx)]
    (projects/create-project c {:key "OTH" :name "Other"})
    (let [here (new! c "Here")
          there (new! c "There" :project "OTH")]
      (is (= {:error "cross_project_edge" :id here :project "CLO" :other there :other-project "OTH"}
             (rejection #(issues/update-issue c {:id here :parent there}))))
      (is (= "cross_project_edge" (:error (rejection #(issues/update-issue c {:id here :blocked-by-add [there]})))))
      (testing "a create in one project with a parent in another"
        (is (= "cross_project_edge" (:error (rejection #(new! c "x" :project "CLO" :parent there))))))
      (testing "a create with a parent and no project takes the parent's project"
        (is (= "OTH-2" (new! c "Child" :parent there)))))))

(deftest invariant-resolution-immutable
  (with-ctx [c (fx/ctx)]
    (let [id (new! c "Issue")]
      (close! c id "done")
      (let [n (count (all-events c))]
        (testing "closing again with the same resolution succeeds and records nothing"
          (is (= [] (:newly-unblocked (close! c id "done"))))
          (is (= n (count (all-events c)))))
        (testing "a different resolution is refused while closed"
          (is (= {:error "resolution_immutable" :id id :resolution "done"} (rejection #(close! c id "dropped")))))
        (testing "title, body and labels stay editable after close"
          (is (= "Renamed" (:title (issues/update-issue c {:id id :title "Renamed" :labels-add ["x"]})))))
        (testing "reopening clears the resolution and keeps the assignee"
          (let [other (new! c "Other")]
            (issues/claim-issue c {:id other :assignee "s/1"})
            (close! c other "done")
            (let [r (issues/reopen-issue c {:id other :comment "more"})]
              (is (= ["open" nil "s/1"] ((juxt :status :resolution :assignee) r))))))))))

(deftest closing-releases-dependents
  (with-ctx [c (fx/ctx)]
    (let [blocker (new! c "Blocker")
          freed (new! c "Freed" :blocked-by [blocker])
          other (new! c "Other blocker")
          held (new! c "Still held" :blocked-by [blocker other])
          before (:updated (get-issue c freed))
          r (close! c blocker "dropped")]
      (testing "close returns exactly the issues it newly unblocked"
        (is (= [freed] (map :id (:newly-unblocked r))))
        (is (false? (:blocked (get-issue c freed))))
        (is (true? (:blocked (get-issue c held)))))
      (testing "the close event appears on the freed issue's timeline and moves its updated time"
        (is (= [freed] (:related (last (all-events c)))))
        (is (not= before (:updated (get-issue c freed))))
        (is (some #(= blocker (:id %)) (:events (issues/get-issue c {:id freed :include ["events"]})))))
      (testing "reopening relates the open dependents it blocks again"
        (issues/reopen-issue c {:id blocker :comment "back"})
        (is (= [freed held] (:related (last (all-events c)))))
        (is (true? (:blocked (get-issue c freed))))))))

(deftest claims
  (with-ctx [c (fx/ctx)]
    (let [id (new! c "Work")
          claim #(issues/claim-issue c {:id id :assignee %})]
      (testing "a claim sets the assignee and claim time"
        (let [r (claim "saqib/a")]
          (is (= "saqib/a" (:assignee r)))
          (is (string? (:claimed-at r)))))
      (testing "claiming what you hold succeeds and records nothing"
        (let [n (count (all-events c))]
          (is (= "saqib/a" (:assignee (claim "saqib/a"))))
          (is (= n (count (all-events c))))))
      (testing "a claim on a held issue fails, naming the holder and claim time"
        (let [r (rejection #(claim "saqib/b"))]
          (is (= ["claim_conflict" "saqib/a"] ((juxt :error :holder) r)))
          (is (= (:claimed (get-issue c id)) (:claimed-at r)))))
      (testing "only the holder releases"
        (is (= {:error "not_holder" :id id :holder "saqib/a"}
               (rejection #(issues/release-issue c {:id id :assignee "saqib/b"}))))
        (is (nil? (:assignee (issues/release-issue c {:id id :assignee "saqib/a"}))))
        (is (nil? (:claimed (get-issue c id)))))
      (testing "releasing an unassigned issue changes nothing"
        (let [n (count (all-events c))]
          (issues/release-issue c {:id id :assignee "anyone"})
          (is (= n (count (all-events c))))))
      (testing "reassign names the expected holder, and none clears"
        (claim "dead/session")
        (is (= {:error "not_holder" :id id :holder "dead/session"}
               (rejection #(issues/reassign-issue c {:id id :from "someone" :to "none"}))))
        (is (= "fresh/session" (:assignee (issues/reassign-issue c {:id id :from "dead/session" :to "fresh/session"}))))
        (is (nil? (:assignee (issues/reassign-issue c {:id id :from "fresh/session" :to "none"})))))
      (testing "the assignee ops refuse a closed issue"
        (claim "saqib/a")
        (close! c id "done")
        (doseq [f [#(claim "saqib/b")
                   #(issues/release-issue c {:id id :assignee "saqib/a"})
                   #(issues/reassign-issue c {:id id :from "saqib/a" :to "none"})]]
          (is (= {:error "issue_closed" :id id :resolution "done"}
                 (select-keys (rejection f) [:error :id :resolution]))))
        (is (= "saqib/a" (:assignee (get-issue c id))))))))

(deftest concurrent-claim-race
  (with-ctx [c (fx/ctx)]
    (let [id (new! c "Contended")
          start (promise)
          racers (doall (for [i (range 16)]
                          (future @start
                                  (let [name (str "session/" i)]
                                    (if-let [r (rejection #(issues/claim-issue c {:id id :assignee name}))]
                                      r
                                      {:won name})))))
          _ (deliver start true)
          results (mapv deref racers)
          winners (keep :won results)
          holder (:assignee (get-issue c id))]
      (is (= 1 (count winners)))
      (is (= holder (first winners)))
      (is (every? #(= ["claim_conflict" holder] ((juxt :error :holder) %)) (remove :won results)))
      (is (= 1 (count (filter #(= "assignee" (-> % :changes first :field)) (all-events c))))))))

(deftest versions-and-body-edits
  (with-ctx [c (fx/ctx)]
    (let [id (new! c "Doc" :body "one two three")
          v #(:version (get-issue c id))]
      (testing "the version moves on title, body and labels only"
        (issues/update-issue c {:id id :title "Doc 2"})
        (issues/update-issue c {:id id :labels-add ["a"]})
        (is (= 3 (v)))
        (issues/claim-issue c {:id id :assignee "me"})
        (issues/update-issue c {:id id :comment "note" :rank 9.5})
        (is (= 3 (v))))
      (testing "a stale expected version is refused with the current values of the fields it touched"
        (is (= {:error "stale_version" :id id :version 3 :title "Doc 2" :body nil :labels nil}
               (rejection #(issues/update-issue c {:id id :expected-version 1 :title "x"}))))
        (is (= {:error "stale_version" :id id :version 3 :title nil :body "one two three" :labels ["a"]}
               (rejection #(issues/update-issue c {:id id :expected-version 2 :body "x" :labels-remove ["a"]}))))
        (is (= 4 (:version (issues/update-issue c {:id id :expected-version 3 :title "Doc 3"})))))
      (testing "body edits apply exactly, in order, all or nothing"
        (is (= "one 2 3" (:body (do (issues/update-issue c {:id id :body-edits [{:old "two" :new "2"}
                                                                                {:old "three" :new "3"}]})
                                    (get-issue c id)))))
        (is (= {:error "edit_mismatch" :index 1 :reason "missing"}
               (select-keys (rejection #(issues/update-issue c {:id id :body-edits [{:old "one" :new "1"}
                                                                                    {:old "nine" :new "9"}]}))
                            [:error :index :reason])))
        (is (= "one 2 3" (:body (get-issue c id))))
        (issues/update-issue c {:id id :body "a a"})
        (is (= {:error "edit_mismatch" :index 0 :reason "ambiguous"}
               (select-keys (rejection #(issues/update-issue c {:id id :body-edits [{:old "a" :new "b"}]}))
                            [:error :index :reason])))))))

(deftest one-event-per-write
  (with-ctx [c (fx/ctx)]
    (let [id (new! c "Issue")
          n #(count (all-events c))]
      (testing "a write touching several fields is one event, its comment included"
        (let [before (n)]
          (issues/update-issue c {:id id :title "New" :labels-add ["x" "y"] :comment "tidy"})
          (is (= (inc before) (n)))
          (let [e (last (all-events c))]
            (is (= "tidy" (:comment e)))
            (is (= [["title" "set" "Issue" "New"] ["labels" "add" nil "x"] ["labels" "add" nil "y"]]
                   (map (juxt :field :op :old :new) (:changes e)))))))
      (testing "a write that changes nothing records nothing and leaves updated alone"
        (let [before (n)
              updated (:updated (get-issue c id))]
          (issues/update-issue c {:id id :title "New" :labels-add ["X"] :labels-remove ["absent"]})
          (is (= before (n)))
          (is (= updated (:updated (get-issue c id))))))
      (testing "a comment alone is an event with no changes"
        (issues/update-issue c {:id id :comment "just saying"})
        (is (= [] (:changes (last (all-events c)))))))))

(deftest relative-ranks
  (with-ctx [c (fx/ctx)]
    (let [a (new! c "A")
          b (new! c "B")]
      (is (= 1.5 (:rank (issues/create-issue c {:title "Between" :rank-after a}))))
      (is (= 0.0 (:rank (issues/create-issue c {:title "First" :rank-before a}))))
      (is (= 3.0 (:rank (issues/create-issue c {:title "Last" :rank-after b}))))
      (testing "a neighbour must be an issue in the project"
        (is (= "invalid_argument" (:error (rejection #(new! c "x" :rank-before "CLO-99"))))))
      (testing "when no double fits between neighbours, rank_precision names them"
        (issues/update-issue c {:id b :rank 1.0000000000000002})
        (is (= {:error "rank_precision" :neighbours [{:id a :rank 1.0} {:id b :rank 1.0000000000000002}]}
               (select-keys (rejection #(new! c "x" :rank-after a)) [:error :neighbours])))))))

(deftest issue-history-pages
  (with-ctx [c (fx/ctx)]
    (let [id (new! c "Issue")]
      (dotimes [i 4] (issues/update-issue c {:id id :comment (str "c" i)}))
      (let [p1 (events/issue-events c {:id id :limit 3})
            p2 (events/issue-events c {:id id :limit 3 :after (:next-cursor p1)})]
        (is (true? (:has-more p1)))
        (is (= 3 (count (:events p1))))
        (is (= ["c2" "c3"] (map :comment (:events p2))))
        (is (false? (:has-more p2)))))))
