(ns cloisters.search-test
  "Search against a real store: every row of the grammar's filter table, implied kinds, archived pages, sorts and
  keyset cursors."
  (:require [clojure.test :refer [deftest is testing]]
            [cloisters.fixtures :as fx :refer [rejection with-ctx]]
            [cloisters.issues :as issues]
            [cloisters.pages :as pages]
            [cloisters.projects :as projects]
            [cloisters.search :as search])
  (:import (java.time Instant ZoneId)))

(defn- clocked
  "A context whose clock reads the atom t, in UTC."
  [t]
  (assoc (fx/ctx) :clock #(deref t) :zone (ZoneId/of "UTC")))

(defn- at! [t s] (reset! t (Instant/parse s)))

(defn- ids
  "The ids a query finds, in order, gathering every page."
  ([c q] (ids c q {}))
  ([c q args]
   (loop [cursor nil out []]
     (let [r (search/search c (cond-> (assoc args :query q :limit 2) cursor (assoc :cursor cursor)))
           out (into out (map :id (:results r)))]
       (if (:has-more r) (recur (:next-cursor r) out) out)))))

(defn- effective [c q] (:effective-query (search/search c {:query q})))

(defn- world!
  "The store most tests search:

  CLO-1  issue  Map, wayfinder:map                       day 1
  CLO-2  issue  Ticket A, child of 1, wayfinder:grilling, claimed by saqib/wf-1 on day 3
  CLO-3  issue  Ticket B, child of 1, Bug, blocked by 2
  CLO-4  page   Glossary, mentions CLO-3 (and CLO-1 only in code)   day 5
  CLO-5  page   Terms, child of 4
  CLO-6  page   Old, child of 5, archived
  CLO-7  issue  Done thing, closed done on day 8 with a comment
  OTH-1  issue  Elsewhere, in project OTH, mentions CLO-4"
  [c t]
  (at! t "2026-10-01T10:00:00Z")
  (projects/create-project c {:key "OTH" :name "Other"})
  (issues/create-issue c {:title "Map" :labels ["wayfinder:map"] :body "The destination."})
  (issues/create-issue c {:title "Ticket A" :parent "CLO-1" :labels ["wayfinder:grilling"]})
  (issues/create-issue c {:title "Ticket B" :parent "CLO-1" :labels ["Bug"] :blocked-by ["CLO-2"]})
  (at! t "2026-10-03T10:00:00Z")
  (issues/claim-issue c {:id "CLO-2" :assignee "saqib/wf-1"})
  (at! t "2026-10-05T10:00:00Z")
  (pages/create-page c {:title "Glossary" :body "See CLO-3, not `CLO-1`."})
  (pages/create-page c {:title "Terms" :parent "CLO-4"})
  (pages/create-page c {:title "Old" :parent "CLO-5"})
  (pages/archive-page c {:id "CLO-6" :summary "stale"})
  (at! t "2026-10-08T10:00:00Z")
  (issues/create-issue c {:title "Done thing"})
  (issues/close-issue c {:id "CLO-7" :resolution "done" :comment "fixed with Frobnicate"})
  (issues/create-issue c {:title "Elsewhere" :project "OTH" :body "About CLO-4."})
  (at! t "2026-10-10T10:00:00Z"))

(deftest kind-project-and-id
  (let [t (atom nil)]
    (with-ctx [c (clocked t)]
      (world! c t)
      (testing "an empty query is every live item in the bound project, by rank"
        (is (= ["CLO-1" "CLO-2" "CLO-3" "CLO-4" "CLO-5" "CLO-7"] (ids c "")))
        (is (= "project:CLO" (effective c ""))))
      (testing "kind:"
        (is (= ["CLO-4" "CLO-5"] (ids c "kind:page")))
        (is (= ["CLO-1" "CLO-2" "CLO-3" "CLO-7"] (ids c "kind:issue")))
        (is (= ["CLO-4" "CLO-5"] (ids c "-kind:issue"))))
      (testing "project:, with * for every project, negation, and the binding only when unscoped"
        (is (= ["OTH-1"] (ids c "project:OTH")))
        (is (= ["OTH-1"] (ids c "-project:CLO")))
        (is (= ["CLO-1" "OTH-1"] (ids c "project:* text:map,elsewhere")))
        (is (= ["CLO-1" "OTH-1"] (ids (dissoc c :project) "text:map,elsewhere")))
        (is (= "text:map" (effective (dissoc c :project) "text:map"))))
      (testing "an unknown project is refused with the projects that exist"
        (is (= {:error "invalid_query" :token "project:NOPE" :suggestions ["project:CLO" "project:OTH"]}
               (select-keys (rejection #(search/search c {:query "project:NOPE"})) [:error :token :suggestions]))))
      (testing "id: lists, matching archived pages because they were named, and unknown ids match nothing"
        (is (= ["CLO-3" "CLO-6"] (ids c "id:CLO-6,CLO-3")))
        (is (= ["CLO-1" "CLO-2" "CLO-4" "CLO-5" "CLO-7"] (ids c "-id:CLO-3")))
        (is (= [] (ids c "id:CLO-99,NOPE-1")))))))

(deftest tree-filters
  (let [t (atom nil)]
    (with-ctx [c (clocked t)]
      (world! c t)
      (testing "parent: follows the named id's kind"
        (is (= ["CLO-2" "CLO-3"] (ids c "parent:CLO-1")))
        (is (= ["CLO-5"] (ids c "parent:CLO-4")))
        (is (= ["CLO-2" "CLO-3" "CLO-5"] (ids c "parent:CLO-1,CLO-4")))
        (is (= [] (ids c "parent:CLO-99"))))
      (testing "under: is every descendant, at any depth, not the item itself"
        (pages/unarchive-page c {:id "CLO-6" :summary "back"})
        (is (= ["CLO-5" "CLO-6"] (ids c "under:CLO-4")))
        (is (= ["CLO-2" "CLO-3"] (ids c "under:CLO-1")))
        (is (= ["CLO-1" "CLO-7"] (ids c "kind:issue -under:CLO-1")))
        (is (= [] (ids c "under:CLO-89")))))))

(deftest mention-filters
  (let [t (atom nil)]
    (with-ctx [c (clocked t)]
      (world! c t)
      (testing "mentions: finds the prose of either kind that names an id; code never counts"
        (is (= ["CLO-4"] (ids c "mentions:CLO-3")))
        (is (= [] (ids c "mentions:CLO-1")))
        (is (= ["OTH-1"] (ids c "project:* mentions:CLO-4"))))
      (testing "mentioned-by: is what an item names"
        (is (= ["CLO-3"] (ids c "mentioned-by:CLO-4")))
        (is (= ["CLO-4"] (ids c "project:* mentioned-by:OTH-1"))))
      (testing "a forward reference matches nothing until its target exists, then matches"
        (issues/update-issue c {:id "CLO-1" :body "Next: CLO-8."})
        (is (= [] (ids c "mentions:CLO-8")))
        (pages/create-page c {:title "Later"})
        (is (= ["CLO-1"] (ids c "mentions:CLO-8")))))))

(deftest text
  (let [t (atom nil)]
    (with-ctx [c (clocked t)]
      (world! c t)
      (testing "a case-insensitive substring of an issue's title, body or comments, or a page's title or body"
        (is (= ["CLO-1"] (ids c "text:DESTINAT")))
        (is (= ["CLO-7"] (ids c "text:frobnicate")))
        (is (= ["CLO-4" "CLO-5"] (ids c "text:ss,erm")))
        (is (= ["CLO-1" "CLO-4"] (ids c "text:ap,lossar")))
        (is (= ["CLO-4"] (ids c "text:\"See CLO-3\""))))
      (testing "fewer than three characters still match, by scanning"
        (is (= ["CLO-2" "CLO-3"] (ids c "text:ET")))
        (is (= ["CLO-4"] (ids c "text:gl"))))
      (testing "an FTS operator in the text is just text"
        (is (= [] (ids c "text:\"a OR b\"")))
        (is (= [] (ids c "text:\"x\\\"y*\"")))))))

(deftest time-filters
  (let [t (atom nil)]
    (with-ctx [c (clocked t)]
      (world! c t)
      (testing "created-: relative durations, ISO dates (midnight in the daemon's zone) and date-times"
        (is (= ["CLO-7"] (ids c "created-after:3d")))
        (is (= ["CLO-4" "CLO-5" "CLO-7"] (ids c "created-after:2026-10-04")))
        (is (= ["CLO-1" "CLO-2" "CLO-3"] (ids c "created-before:2026-10-05T09:00")))
        (is (= ["CLO-1" "CLO-2" "CLO-3"] (ids c "created-before:2026-10-05T11:00+02:00")))
        (is (= ["CLO-4" "CLO-5"] (ids c "created-after:6d created-before:3d"))))
      (testing "updated-: moves with any event, so the claim updated CLO-2"
        (is (= ["CLO-2" "CLO-4" "CLO-5" "CLO-7"] (ids c "updated-after:2026-10-02")))
        (is (= ["CLO-1" "CLO-3"] (ids c "-updated-after:2026-10-02"))))
      (testing "closed- and claimed- apply to issues only"
        (is (= ["CLO-7"] (ids c "closed-after:5d")))
        (is (= [] (ids c "closed-before:5d")))
        (is (= ["CLO-2"] (ids c "claimed-before:5d")))
        (is (= "project:CLO kind:issue claimed-before:5d" (effective c "claimed-before:5d")))
        (is (= ["CLO-1" "CLO-3" "CLO-7"] (ids c "-claimed-before:5d")))))))

(deftest archived
  (let [t (atom nil)]
    (with-ctx [c (clocked t)]
      (world! c t)
      (testing "archived pages are left out by default; archived: asks for them and implies kind:page"
        (is (= ["CLO-4" "CLO-5"] (ids c "kind:page")))
        (is (= ["CLO-6"] (ids c "archived:yes")))
        (is (= ["CLO-4" "CLO-5" "CLO-6"] (ids c "archived:any")))
        (is (= ["CLO-4" "CLO-5"] (ids c "archived:no")))
        (is (= "project:CLO kind:page archived:yes" (effective c "archived:yes"))))
      (testing "the default leaves issues alone"
        (is (= ["CLO-5"] (ids c "under:CLO-4")))
        (is (= ["CLO-5" "CLO-6"] (ids c "under:CLO-4 archived:any")))
        (is (= ["CLO-6"] (ids c "text:old archived:any")))))))

(deftest issue-filters
  (let [t (atom nil)]
    (with-ctx [c (clocked t)]
      (world! c t)
      (testing "status: and resolution:"
        (is (= ["CLO-7"] (ids c "status:closed")))
        (is (= ["CLO-1" "CLO-2" "CLO-3"] (ids c "status:open")))
        (is (= ["CLO-7"] (ids c "resolution:done,dropped")))
        (is (= ["CLO-1" "CLO-2" "CLO-3"] (ids c "-resolution:done"))))
      (testing "assignee: a name, none, any, or a prefix"
        (issues/claim-issue c {:id "CLO-3" :assignee "saqib/wf-2"})
        (issues/claim-issue c {:id "CLO-1" :assignee "sam"})
        (is (= ["CLO-2"] (ids c "assignee:saqib/wf-1")))
        (is (= ["CLO-2" "CLO-3"] (ids c "assignee:saqib/*")))
        (is (= ["CLO-1" "CLO-2" "CLO-3"] (ids c "assignee:sa*")))
        (is (= [] (ids c "assignee:Saqib/*")))
        (is (= ["CLO-1" "CLO-2" "CLO-3"] (ids c "assignee:any")))
        (is (= [] (ids c "assignee:none status:open")))
        (is (= ["CLO-1" "CLO-7"] (ids c "-assignee:saqib/*")))
        (issues/release-issue c {:id "CLO-1" :assignee "sam"})
        (issues/release-issue c {:id "CLO-3" :assignee "saqib/wf-2"}))
      (testing "label: exact or by prefix, ignoring case"
        (is (= ["CLO-3"] (ids c "label:bug")))
        (is (= ["CLO-3"] (ids c "label:BUG")))
        (is (= ["CLO-1" "CLO-2"] (ids c "label:wayfinder:*")))
        (is (= ["CLO-1" "CLO-2" "CLO-3"] (ids c "label:*")))
        (is (= ["CLO-1"] (ids c "label:wayfinder:map,nothing")))
        (is (= ["CLO-1"] (ids c "label:wayfinder:* -label:wayfinder:grilling")))
        (is (= ["CLO-7"] (ids c "-label:*"))))
      (testing "blocked and unblocked: blocked means an open blocker"
        (is (= ["CLO-3"] (ids c "blocked")))
        (is (= ["CLO-1" "CLO-2" "CLO-7"] (ids c "unblocked")))
        (is (= ["CLO-1" "CLO-2" "CLO-7"] (ids c "-blocked")))
        (is (= ["CLO-3"] (ids c "-unblocked"))))
      (testing "blocks: and blocked-by: are direct edges"
        (is (= ["CLO-2"] (ids c "blocks:CLO-3")))
        (is (= ["CLO-3"] (ids c "blocked-by:CLO-2")))
        (is (= [] (ids c "blocks:CLO-99"))))
      (testing "ready is exactly status:open unblocked assignee:none"
        (is (= ["CLO-1"] (ids c "ready")))
        (is (= (ids c "status:open unblocked assignee:none") (ids c "ready")))
        (is (= "project:CLO kind:issue ready parent:CLO-1" (effective c "ready parent:CLO-1")))
        (is (= [] (ids c "ready parent:CLO-1")))
        (issues/release-issue c {:id "CLO-2" :assignee "saqib/wf-1"})
        (is (= ["CLO-2"] (ids c "ready parent:CLO-1")))
        (issues/close-issue c {:id "CLO-2" :resolution "dropped" :comment "not needed"})
        (testing "a closed blocker unblocks, whatever its resolution"
          (is (= ["CLO-3"] (ids c "ready parent:CLO-1"))))
        (is (= ["CLO-2" "CLO-7"] (ids c "-ready")))))))

(deftest sorts
  (let [t (atom nil)]
    (with-ctx [c (clocked t)]
      (world! c t)
      (testing "rank by default, ties broken by number"
        (issues/update-issue c {:id "CLO-7" :rank 2.0})
        (is (= ["CLO-1" "CLO-2" "CLO-7" "CLO-3" "CLO-4" "CLO-5"] (ids c "")))
        (is (= (ids c "") (ids c "sort:rank")))
        (is (= ["CLO-5" "CLO-4" "CLO-3" "CLO-2" "CLO-7" "CLO-1"] (ids c "sort:rank-desc"))))
      (testing "created and updated, either way"
        (is (= ["CLO-1" "CLO-2" "CLO-3" "CLO-4" "CLO-5" "CLO-7"] (ids c "sort:created")))
        (is (= ["CLO-7" "CLO-4" "CLO-5" "CLO-1" "CLO-2" "CLO-3"] (ids c "sort:created-desc"))))
      (testing "closed implies kind:issue; open issues sort as never closed"
        (at! t "2026-10-11T10:00:00Z")
        (issues/close-issue c {:id "CLO-2" :resolution "dropped" :comment "no"})
        (is (= ["CLO-2" "CLO-7" "CLO-1" "CLO-3"] (ids c "sort:closed-desc")))
        (is (= ["CLO-1" "CLO-3" "CLO-7" "CLO-2"] (ids c "sort:closed-asc")))
        (is (= "project:CLO kind:issue sort:closed-desc" (effective c "sort:closed-desc")))))))

(deftest recent-changes-and-trees
  (let [t (atom nil)]
    (with-ctx [c (clocked t)]
      (world! c t)
      (testing "recent changes is kind:page sort:updated-desc; a move updates the page and both parents"
        (at! t "2026-10-12T10:00:00Z")
        (pages/create-page c {:title "Root two"})
        (at! t "2026-10-13T10:00:00Z")
        (pages/update-page c {:id "CLO-5" :parent "CLO-8"})
        (is (= ["CLO-4" "CLO-5" "CLO-8"] (ids c "kind:page sort:updated-desc")))
        (testing "sort:updated-desc alone mixes both kinds"
          (is (= ["CLO-4" "CLO-5" "CLO-8" "CLO-7" "CLO-2"] (take 5 (ids c "sort:updated-desc"))))))
      (testing "a tree is a query: rows by rank, each carrying parent and rank to assemble"
        (pages/create-page c {:title "First under root two" :parent "CLO-8" :rank-before "CLO-5"})
        (let [rows (:results (search/search c {:query "under:CLO-8"}))]
          (is (= [["CLO-9" "CLO-8"] ["CLO-5" "CLO-8"]] (map (juxt :id :parent) rows))))
        (is (= ["CLO-4" "CLO-9" "CLO-5" "CLO-8"] (ids c "kind:page"))
            "a flat page list by rank is deterministic: CLO-9 ties CLO-4 at rank 4 and follows it by number")))))

(deftest rows-carry-their-kind
  (let [t (atom nil)]
    (with-ctx [c (clocked t)]
      (world! c t)
      (let [[issue page] (:results (search/search c {:query "id:CLO-3,CLO-6"}))]
        (is (= {:id "CLO-3" :kind "issue" :title "Ticket B" :status "open" :resolution nil :assignee nil
                :labels ["Bug"] :parent "CLO-1" :blocked true :rank 3.0 :updated "2026-10-01T10:00:00.000Z"}
               issue))
        (is (= {:id "CLO-6" :kind "page" :title "Old" :parent "CLO-5" :archived true :rank 6.0
                :updated "2026-10-05T10:00:00.000Z" :revision 1}
               page))))))

(deftest cursors
  (let [t (atom nil)]
    (with-ctx [c (clocked t)]
      (world! c t)
      (testing "every item appears exactly once, whatever the page size, for every sort"
        (doseq [sort ["rank" "rank-desc" "created" "created-desc" "updated-desc" "closed-desc"]
                :let [q (str "project:* sort:" sort)
                      all (:results (search/search c {:query q}))]]
          (is (= (count all) (count (distinct (map :id all)))))
          (doseq [limit [1 2 3]]
            (is (= (map :id all) (ids c q {:limit limit})) (str q " by " limit)))))
      (testing "the envelope"
        (let [r (search/search c {:query "kind:issue" :limit 3})]
          (is (= [true 3] [(:has-more r) (count (:results r))]))
          (is (string? (:next-cursor r)))
          (let [r2 (search/search c {:query "kind:issue" :limit 3 :cursor (:next-cursor r)})]
            (is (= [false nil ["CLO-7"]] [(:has-more r2) (:next-cursor r2) (map :id (:results r2))])))))
      (testing "items unchanged while paging appear exactly once while others write"
        (let [r (search/search c {:query "sort:created" :limit 2})]
          (issues/create-issue c {:title "Written meanwhile"})
          (issues/update-issue c {:id "CLO-1" :title "Map, renamed"})
          (let [rest-ids (ids c "sort:created" {:cursor (:next-cursor r)})]
            (is (= ["CLO-3" "CLO-4" "CLO-5" "CLO-7" "CLO-8"] rest-ids)))))
      (testing "a cursor works only with the sort that issued it, and garbage is refused"
        (let [cursor (:next-cursor (search/search c {:query "" :limit 1}))]
          (is (= {:error "invalid_argument" :argument "cursor"}
                 (select-keys (rejection #(search/search c {:query "sort:created" :cursor cursor})) [:error :argument]))))
        (is (= "cursor" (:argument (rejection #(search/search c {:cursor "not-a-cursor"})))))
        (is (= "limit" (:argument (rejection #(search/search c {:limit 201})))))))))
