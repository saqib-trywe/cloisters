(ns cloisters.issues
  "Issue operations. Invariants are state invariants: any write that would leave the store violating one is refused,
  whatever kind of write it is, and the refusal names the conflict.

  1. A closed issue has no open children.
  2. An issue closed done has no open blockers.
  3. Hierarchy and blocking are acyclic.
  4. Parent and blocking edges stay within one project.
  5. A closed issue's resolution is immutable while it stays closed.

  Issues are never deleted, and the assignee changes only by claim, release and reassign."
  (:require [cloisters.contract :as contract]
            [cloisters.core :as core :refer [fail id-str q x!]]
            [cloisters.events :as events]
            [cloisters.store :as store]
            [clojure.string :as str]))

;; ---------------------------------------------------------------- reading

(defn- issue [conn ref] (core/by-ref conn :issue ref))

(defn- where-ref [[k n]] [:and [:= :project k] [:= :number n]])

(defn- labels-of [conn ref]
  (vec (sort-by str/lower-case (map :label (q conn {:select [:label] :from :label :where (where-ref ref)})))))

(defn- blockers-of
  "The issues blocking ref, as refs in number order."
  [conn [k n]]
  (mapv #(vector k (:blocker %))
        (q conn {:select [:blocker] :from :blocks :where [:and [:= :project k] [:= :blocked n]] :order-by [:blocker]})))

(defn- blocking-of
  "The issues ref blocks, as refs in number order."
  [conn [k n]]
  (mapv #(vector k (:blocked %))
        (q conn {:select [:blocked] :from :blocks :where [:and [:= :project k] [:= :blocker n]] :order-by [:blocked]})))

(defn- children-of [conn [k n]]
  (mapv #(vector k (:number %))
        (q conn {:select [:number] :from :issue :where [:and [:= :project k] [:= :parent n]]
                 :order-by [:rank :number]})))

(defn- open? [i] (= "open" (:status i)))

(defn- open-blockers [conn ref]
  (filterv #(open? (issue conn %)) (blockers-of conn ref)))

(defn- parent-ref [{:keys [project parent]}]
  (when parent [project parent]))

(defn row
  "An issue's row: everything a list shows, never the body."
  [conn {:keys [project number] :as i}]
  (let [ref [project number]]
    {:id (id-str ref) :kind "issue" :title (:title i) :status (:status i) :resolution (:resolution i)
     :assignee (:assignee i) :labels (labels-of conn ref) :parent (some-> (parent-ref i) id-str)
     :blocked (boolean (seq (open-blockers conn ref))) :rank (:rank i) :updated (:updated i)}))

(defn- row-at [conn ref] (row conn (issue conn ref)))

(defn- brief
  "An issue named in a refusal: id and title."
  [conn ref]
  {:id (id-str ref) :title (:title (issue conn ref))})

(defn- ids [refs] (str/join ", " (map id-str refs)))

;; ---------------------------------------------------------------- checks

(defn- same-project!
  "Refuses an edge between ref and other in different projects. ref's number is nil for an issue being created."
  [[project n :as ref] other]
  (when (not= project (first other))
    (fail "cross_project_edge" (str "Parent and blocking edges stay within one project: "
                                    (if n (id-str ref) "the new issue") " is in " project " and " (id-str other)
                                    " in " (first other) ". Mention it instead.")
          :id (when n (id-str ref)) :project project :other (id-str other) :other-project (first other))))

(defn- cycle! [path what]
  (fail "cycle" (str "That would make a cycle in " what ": " (str/join " → " (map id-str path)) ".")
        :path (mapv id-str path)))

(defn- check-parent!
  "Refuses making pref the parent of the issue i at ref (i is nil for an issue being created): edges stay within a
  project, hierarchy is acyclic, and an open issue never sits under a closed one."
  [tx ref i pref]
  (same-project! ref pref)
  (let [ancestors (take-while some? (iterate #(some-> (issue tx %) parent-ref) pref))]
    (when-let [i (first (keep-indexed #(when (= ref %2) %1) ancestors))]
      (cycle! (cons ref (take (inc i) ancestors)) "the hierarchy")))
  (let [p (issue tx pref)]
    (when (and (or (nil? i) (open? i)) (not (open? p)))
      (fail "issue_closed" (str (id-str pref) " is closed (" (:resolution p) "), and a closed issue has no open "
                                "children. Reopen it first, or choose another parent.")
            :id (id-str pref) :resolution (:resolution p) :closed (:closed p)))))

(defn- blocks-path
  "The path from ref to target along blocks edges (ref blocks … blocks target), or nil."
  [tx ref target]
  (loop [queue [[ref]] seen #{ref}]
    (when-let [[path & more] (seq queue)]
      (let [at (peek path)]
        (if (= at target)
          path
          (let [next-refs (remove seen (blocking-of tx at))]
            (recur (into (vec more) (map #(conj path %) next-refs)) (into seen next-refs))))))))

(defn- check-blocker!
  "Refuses making bref block the issue i at ref: edges stay within a project, blocking is acyclic, and an issue
  closed done has no open blockers."
  [tx ref i bref]
  (same-project! ref bref)
  (when-let [path (if (= ref bref) [ref] (blocks-path tx ref bref))]
    (cycle! (cons bref path) "blocking"))
  (when (and i (= "done" (:resolution i)) (open? (issue tx bref)))
    (fail "open_blockers" (str (id-str ref) " is closed done, so it cannot have an open blocker; " (id-str bref)
                               " is open.")
          :blockers [(brief tx bref)])))

(defn- check-version!
  "Refuses a stale expected version with the current version and the current values of the guarded fields the write
  touched."
  [tx ref i args]
  (when-let [expected (:expected-version args)]
    (when (not= expected (:version i))
      (fail "stale_version" (str (id-str ref) " is at version " (:version i) ", not " expected
                                 ": it changed since you read it. Read it again and redo your change.")
            :id (id-str ref) :version (:version i)
            :title (when (contains? args :title) (:title i))
            :body (when (some #(contains? args %) [:body :body-edits]) (:body i))
            :labels (when (some #(contains? args %) [:labels-add :labels-remove]) (labels-of tx ref))))))

(defn- check-open! [ref i]
  (when-not (open? i)
    (fail "issue_closed" (str (id-str ref) " is closed (" (:resolution i) "), so its assignee is the record of who "
                              "resolved it. Reopen it first if the work is live again.")
          :id (id-str ref) :resolution (:resolution i) :closed (:closed i))))

;; ---------------------------------------------------------------- ranks and labels

(defn- rank-order
  "Every issue in project except ref, in order, for relative ranks."
  [tx project ref]
  (vec (for [{:keys [number rank]} (q tx {:select [:number :rank] :from :issue :where [:= :project project]
                                          :order-by [:rank :number]})
             :when (not= ref [project number])]
         {:id (id-str [project number]) :rank rank})))

(defn- place [tx project ref args]
  (core/place args (rank-order tx project ref) (str "an issue in " project)))

(defn- dedupe-labels [labels]
  (vals (into (sorted-map) (map (juxt str/lower-case identity)) (reverse labels))))

;; ---------------------------------------------------------------- results

(defn- result [tx ref & {:as extra}]
  (let [i (issue tx ref)]
    (merge (row tx i) {:version (:version i)} extra)))

(defn- reindex! [tx ref title-or-body-changed? comment]
  (when (or title-or-body-changed? comment)
    (core/reindex-mentions! tx ref)))

;; ---------------------------------------------------------------- create, get, update

(defn create-issue
  "Creates an issue: row and version."
  [ctx args]
  (let [{:keys [title body labels parent blocked-by comment] :as args} (contract/validate! :create-issue args)
        body (or body "")]
    (core/write!
     ctx
     (fn [{:keys [tx at] :as w}]
       (let [pref (some-> parent (#(core/require-kind tx % "issue")))
             project (core/project-for w tx args pref)
             brefs (vec (distinct (map #(core/require-kind tx % "issue") blocked-by)))
             _ (when pref (check-parent! tx [project nil] nil pref))
             _ (doseq [b brefs] (same-project! [project nil] b))
             rank (place tx project nil args)
             n (store/allocate! tx project :issue)
             ref [project n]
             labels (dedupe-labels labels)]
         (x! tx {:insert-into :issue
                 :values [{:project project :number n :title title :body body :parent (second pref)
                           :rank (or rank n) :created at :updated at}]})
         (doseq [l labels]
           (x! tx {:insert-into :label :values [{:project project :number n :label l}]}))
         (doseq [[_ b] brefs]
           (x! tx {:insert-into :blocks :values [{:project project :blocker b :blocked n}]}))
         (core/record! w {:subject ref :kind "issue" :comment comment
                          :related (cond-> brefs pref (conj pref))
                          :changes (concat [(core/change :title "set" nil title)
                                            (when (seq body) (core/change :body "set" nil body))]
                                           (map #(core/change :labels "add" nil %) labels)
                                           [(when pref (core/change :parent "set" nil (id-str pref)))]
                                           (map #(core/change :blocked-by "add" nil (id-str %)) brefs)
                                           [(when rank (core/change :rank "set" nil (core/rank-str rank)))])})
         (core/reindex-mentions! tx ref)
         (result tx ref))))))

(defn get-issue
  "Everything about an issue: its fields and body, its edges as refs, its mentions and backlinks, and its comments;
  with include [\"events\"], the latest 50 events too, body changes as diffs."
  [ctx args]
  (let [{:keys [id include]} (contract/validate! :get-issue args)]
    (core/with-read
      ctx
      (fn [conn]
        (let [ref (core/require-kind conn id "issue")
              {:keys [body version closed claimed created] :as i} (issue conn ref)
              refs #(mapv (partial core/ref-info conn) %)
              comments (q conn {:select [:seq :at :actor :comment] :from :event
                                :where [:and (where-ref ref) [:<> :comment nil]] :order-by [:seq]})]
          (merge (row conn i)
                 {:body body :version version :closed closed :claimed claimed :created created
                  :parent (some->> (parent-ref i) (core/ref-info conn))
                  :children (refs (children-of conn ref))
                  :blocked-by (refs (blockers-of conn ref))
                  :blocking (refs (blocking-of conn ref))
                  :comments comments}
                 (core/mention-lists conn ref)
                 (when (some #{"events"} include)
                   (let [{:keys [events total truncated]} (events/latest conn ref events/default-limit)]
                     {:events (mapv events/compact events) :events-total total :events-truncated truncated}))))))))

(defn- label-changes
  "The label adds and removes a write makes, removes first, each case-insensitive against the current labels."
  [current {:keys [labels-add labels-remove]}]
  (let [removes (keep (fn [l] (first (filter #(= (str/lower-case l) (str/lower-case %)) current)))
                      (distinct (map str/lower-case labels-remove)))
        remaining (set (map str/lower-case (remove (set removes) current)))
        adds (remove #(remaining (str/lower-case %)) (dedupe-labels labels-add))]
    {:removes (vec (distinct removes)) :adds (vec adds)}))

(defn update-issue
  "Edits an issue: title, body or body edits, labels, parent, blockers, rank, and a comment. Returns row and version.
  The version moves only when the title, body or labels change."
  [ctx args]
  (let [{:keys [id title body body-edits blocked-by-add blocked-by-remove comment] :as args}
        (contract/validate! :update-issue args)]
    (core/write!
     ctx
     (fn [{:keys [tx] :as w}]
       (let [[project n :as ref] (core/require-kind tx id "issue")
             i (issue tx ref)
             _ (check-version! tx ref i args)
             title' (or title (:title i))
             body' (cond body body
                         body-edits (core/apply-body-edits (:body i) body-edits)
                         :else (:body i))
             {:keys [adds removes]} (label-changes (labels-of tx ref) args)
             old-parent (parent-ref i)
             new-parent (if (contains? args :parent)
                          (some-> (:parent args) (#(core/require-kind tx % "issue")))
                          old-parent)
             _ (when (and new-parent (not= new-parent old-parent)) (check-parent! tx ref i new-parent))
             current-blockers (set (blockers-of tx ref))
             block-adds (->> blocked-by-add
                             (map #(core/require-kind tx % "issue"))
                             distinct
                             (remove current-blockers)
                             vec)
             _ (doseq [b block-adds] (check-blocker! tx ref i b))
             block-removes (->> blocked-by-remove (map core/parse-id) distinct (filter current-blockers) vec)
             rank (place tx project ref args)
             rank' (or rank (:rank i))
             content? (or (not= title' (:title i)) (not= body' (:body i)) (seq adds) (seq removes))
             version' (if content? (inc (:version i)) (:version i))
             changes [(core/set-change :title (:title i) title')
                      (core/set-change :body (:body i) body')
                      (map #(core/change :labels "remove" % nil) removes)
                      (map #(core/change :labels "add" nil %) adds)
                      (core/set-change :parent (some-> old-parent id-str) (some-> new-parent id-str))
                      (map #(core/change :blocked-by "remove" (id-str %) nil) block-removes)
                      (map #(core/change :blocked-by "add" nil (id-str %)) block-adds)
                      (when (not= rank' (:rank i))
                        (core/change :rank "set" (core/rank-str (:rank i)) (core/rank-str rank')))]
             changes (flatten (remove nil? changes))]
         (when (seq changes)
           (x! tx {:update :issue
                   :set {:title title' :body body' :parent (second new-parent) :rank rank' :version version'}
                   :where (where-ref ref)})
           (doseq [l removes]
             (x! tx {:delete-from :label :where [:and (where-ref ref) [:= :label l]]}))
           (doseq [l adds]
             (x! tx {:insert-into :label :values [{:project project :number n :label l}]}))
           (doseq [[_ b] block-removes]
             (x! tx {:delete-from :blocks :where [:and [:= :project project] [:= :blocker b] [:= :blocked n]]}))
           (doseq [[_ b] block-adds]
             (x! tx {:insert-into :blocks :values [{:project project :blocker b :blocked n}]})))
         (core/record! w {:subject ref :kind "issue" :changes changes :comment comment
                          :related (concat (when (not= old-parent new-parent) (remove nil? [old-parent new-parent]))
                                           block-adds block-removes)})
         (reindex! tx ref (or (not= title' (:title i)) (not= body' (:body i))) comment)
         (result tx ref))))))

;; ---------------------------------------------------------------- the assignee

(defn- set-assignee! [{:keys [tx at]} ref assignee]
  (x! tx {:update :issue :set {:assignee assignee :claimed (when assignee at)} :where (where-ref ref)}))

(defn- assignee-write!
  "Records an assignee change (or none, when old = new) with the comment, then returns the result."
  [{:keys [tx] :as w} ref old new comment & extra]
  (when (not= old new) (set-assignee! w ref new))
  (core/record! w {:subject ref :kind "issue" :comment comment
                   :changes [(core/set-change :assignee old new)]})
  (reindex! tx ref false comment)
  (apply result tx ref extra))

(defn claim-issue
  "Takes an issue by setting its assignee, only if it has none. Claiming what you already hold succeeds and changes
  nothing. Returns row and claimed_at."
  [ctx args]
  (let [{:keys [id assignee comment]} (contract/validate! :claim-issue args)]
    (core/write!
     ctx
     (fn [{:keys [tx] :as w}]
       (let [ref (core/require-kind tx id "issue")
             {holder :assignee :keys [claimed] :as i} (issue tx ref)]
         (check-open! ref i)
         (when (and holder (not= holder assignee))
           (fail "claim_conflict" (str id " is already claimed by " holder " (since " claimed "). Choose another "
                                       "issue from search ready, or if that claim is stale, reassign_issue from "
                                       holder ".")
                 :id id :holder holder :claimed-at claimed))
         (let [r (assignee-write! w ref holder assignee comment)]
           (assoc r :claimed-at (:claimed (issue tx ref)))))))))

(defn release-issue
  "Gives an issue up: only its holder can. Releasing an unassigned issue changes nothing."
  [ctx args]
  (let [{:keys [id assignee comment]} (contract/validate! :release-issue args)]
    (core/write!
     ctx
     (fn [{:keys [tx] :as w}]
       (let [ref (core/require-kind tx id "issue")
             {holder :assignee :as i} (issue tx ref)]
         (check-open! ref i)
         (when (and holder (not= holder assignee))
           (fail "not_holder" (str id " is held by " holder ", not " assignee ". Only the holder releases; to move "
                                   "a claim on, reassign_issue from " holder ".")
                 :id id :holder holder))
         (assignee-write! w ref holder nil comment))))))

(defn- nobody [name] (when (not= "none" name) name))

(defn reassign-issue
  "Moves an issue from the holder the caller expects (or none) to another (or none)."
  [ctx args]
  (let [{:keys [id from to comment]} (contract/validate! :reassign-issue args)
        from (nobody from)
        to (nobody to)]
    (core/write!
     ctx
     (fn [{:keys [tx] :as w}]
       (let [ref (core/require-kind tx id "issue")
             {holder :assignee :as i} (issue tx ref)]
         (check-open! ref i)
         (when (not= holder from)
           (fail "not_holder" (str id " is held by " (or holder "nobody") ", not " (or from "nobody")
                                   ". Reassign from its actual holder.")
                 :id id :holder holder))
         (assignee-write! w ref holder to comment))))))

;; ---------------------------------------------------------------- close and reopen

(defn close-issue
  "Closes an issue as done or dropped, with a comment saying why. Returns row and the issues it newly unblocked.
  Closing again with the same resolution succeeds and changes nothing."
  [ctx args]
  (let [{:keys [id resolution comment]} (contract/validate! :close-issue args)]
    (core/write!
     ctx
     (fn [{:keys [tx at] :as w}]
       (let [ref (core/require-kind tx id "issue")
             i (issue tx ref)]
         (if-not (open? i)
           (if (= resolution (:resolution i))
             (result tx ref :newly-unblocked [])
             (fail "resolution_immutable" (str id " is already closed " (:resolution i) "; a resolution cannot "
                                               "change while closed. Reopen it first.")
                   :id id :resolution (:resolution i)))
           (let [open-children (filterv #(open? (issue tx %)) (children-of tx ref))
                 blockers (open-blockers tx ref)
                 _ (when (seq open-children)
                     (fail "open_children" (str id " has open children: " (ids open-children) ". Close them first; "
                                                "closing never cascades.")
                           :id id :children (mapv #(brief tx %) open-children)))
                 _ (when (and (= "done" resolution) (seq blockers))
                     (fail "open_blockers" (str id " is blocked by open " (ids blockers) ", so it cannot be done. "
                                                "Close them first, or close this dropped.")
                           :id id :blockers (mapv #(brief tx %) blockers)))
                 _ (x! tx {:update :issue :set {:status "closed" :resolution resolution :closed at}
                           :where (where-ref ref)})
                 unblocked (filterv #(let [d (issue tx %)] (and (open? d) (empty? (open-blockers tx %))))
                                    (blocking-of tx ref))]
             (core/record! w {:subject ref :kind "issue" :comment comment :related unblocked
                              :changes [(core/change :status "set" "open" "closed")
                                        (core/change :resolution "set" nil resolution)]})
             (reindex! tx ref false comment)
             (result tx ref :newly-unblocked (mapv #(row-at tx %) unblocked)))))))))

(defn reopen-issue
  "Returns a closed issue to open, with a comment saying why, discarding its resolution and keeping its assignee.
  Refused while its parent is closed, or while an issue it blocks is closed done."
  [ctx args]
  (let [{:keys [id comment]} (contract/validate! :reopen-issue args)]
    (core/write!
     ctx
     (fn [{:keys [tx] :as w}]
       (let [ref (core/require-kind tx id "issue")
             i (issue tx ref)]
         (when-not (open? i)
           (let [pref (parent-ref i)
                 parent (some->> pref (issue tx))
                 dependents (blocking-of tx ref)
                 done (filterv #(= "done" (:resolution (issue tx %))) dependents)]
             (when (and parent (not (open? parent)))
               (fail "reopen_blocked" (str id "'s parent " (id-str pref) " is closed, and a closed issue has no open "
                                           "children. Reopen the parent first.")
                     :id id :parent (brief tx pref)))
             (when (seq done)
               (fail "reopen_blocked" (str id " blocks " (ids done) ", closed done; an issue closed done has no open "
                                           "blockers. Reopen them first, or remove the blocking edge.")
                     :id id :dependents (mapv #(brief tx %) done)))
             (x! tx {:update :issue :set {:status "open" :resolution nil :closed nil} :where (where-ref ref)})
             (core/record! w {:subject ref :kind "issue" :comment comment
                              :related (filterv #(open? (issue tx %)) dependents)
                              :changes [(core/change :status "set" "closed" "open")
                                        (core/change :resolution "set" (:resolution i) nil)]})
             (reindex! tx ref false comment)))
         (result tx ref))))))
