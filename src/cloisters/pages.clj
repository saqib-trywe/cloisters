(ns cloisters.pages
  "Page operations. A page's version is its latest revision number, and a revision is the page as it stood after an
  event that changed its title or body: revisions are read from the event log, never stored beside it.

  Invariants, refused naming the conflict:

  1. A page tree is acyclic and stays within one project.
  2. A live page never has an archived parent: archiving refuses live children, and unarchiving, moving or creating a
     live page beneath an archived parent is refused.

  Pages are never deleted; archiving is how a page leaves the way."
  (:require [cloisters.contract :as contract]
            [cloisters.core :as core :refer [fail id-str q q1 x!]]
            [cloisters.diff :as diff]
            [cloisters.events :as events]
            [cloisters.store :as store]
            [clojure.string :as str]))

;; ---------------------------------------------------------------- reading

(defn- page [conn ref] (core/by-ref conn :page ref))

(defn- where-ref [[k n]] [:and [:= :project k] [:= :number n]])

(defn- archived? [p] (= 1 (:archived p)))

(defn- parent-ref [{:keys [project parent]}]
  (when parent [project parent]))

(defn- children-of [conn [k n]]
  (mapv #(vector k (:number %))
        (q conn {:select [:number] :from :page :where [:and [:= :project k] [:= :parent n]]
                 :order-by [:rank :number]})))

(defn row
  "A page's row: everything a list shows, never the body."
  [{:keys [project number] :as p}]
  {:id (id-str [project number]) :kind "page" :title (:title p) :parent (some-> (parent-ref p) id-str)
   :archived (archived? p) :rank (:rank p) :updated (:updated p) :revision (:version p)})

(defn- result [tx ref]
  (let [p (page tx ref)]
    (assoc (row p) :version (:version p))))

(defn- brief [p] {:id (id-str [(:project p) (:number p)]) :title (:title p)})

(defn- revision-events
  "The page's content-changing events, as revisions, oldest first."
  [conn [k n] & {:keys [limit]}]
  (let [rows (q conn (cond-> {:select [:revision :at :actor :summary :restored-from] :from :event
                              :where [:and [:= :project k] [:= :number n] [:<> :revision nil]]
                              :order-by [[:revision :desc]]}
                       limit (assoc :limit limit)))]
    (vec (reverse rows))))

(defn- content-at
  "The page's title and body as they stood at revision rev, read from the latest change to each at or before it."
  [conn [k n] rev]
  (letfn [(field [f]
            (:new (q1 conn {:select [:c.new] :from [[:change :c]]
                            :join [[:event :e] [:= :e.seq :c.event]]
                            :where [:and [:= :e.project k] [:= :e.number n] [:<= :e.revision rev] [:= :c.field f]]
                            :order-by [[:e.revision :desc]]
                            :limit 1})))]
    {:title (field "title") :body (or (field "body") "")}))

(defn- require-revision [ref p rev]
  (when-not (<= 1 rev (:version p))
    (fail "not_found" (str (id-str ref) " has no revision r" rev "; its latest is r" (:version p) ".")
          :id (id-str ref) :revision rev))
  rev)

;; ---------------------------------------------------------------- checks

(defn- check-version!
  "Refuses a stale expected version with the current version and the current title and body, as the write touched
  them."
  [ref p args touched]
  (when-let [expected (:expected-version args)]
    (when (not= expected (:version p))
      (fail "stale_version" (str (id-str ref) " is at version " (:version p) ", not " expected
                                 ": it changed since you read it. Read it again and redo your change.")
            :id (id-str ref) :version (:version p)
            :title (when (touched :title) (:title p))
            :body (when (touched :body) (:body p))))))

(defn- archived-parent! [parent what]
  (fail "archived_parent" (str "Parent " (:id (brief parent)) " is archived, and a live page never has an archived "
                               "parent. Unarchive the parent first, or " what ".")
        :parent (brief parent)))

(defn- check-parent!
  "Refuses making pref the parent of the page at ref (nil while creating it, live? whether it is live): the tree stays
  within a project and acyclic, and a live page never sits under an archived one."
  [tx [project n :as ref] live? pref]
  (when (not= project (first pref))
    (fail "cross_project_edge" (str "A page tree never crosses projects: " (if n (id-str ref) "the new page")
                                    " is in " project " and " (id-str pref) " in " (first pref) ".")
          :id (when n (id-str ref)) :project project :other (id-str pref) :other-project (first pref)))
  (let [ancestors (take-while some? (iterate #(some-> (page tx %) parent-ref) pref))]
    (when-let [i (first (keep-indexed #(when (= ref %2) %1) ancestors))]
      (let [path (cons ref (take (inc i) ancestors))]
        (fail "cycle" (str "A page cannot move beneath itself: " (str/join " → " (map id-str path)) ".")
              :path (mapv id-str path)))))
  (let [p (page tx pref)]
    (when (and live? (archived? p))
      (archived-parent! p "choose another parent"))))

(defn- rank-order
  "The pages under parent (a page number, or nil for the roots) in project except ref, in order."
  [tx project parent ref]
  (vec (for [{:keys [number rank]} (q tx {:select [:number :rank] :from :page
                                          :where [:and [:= :project project] [:= :parent parent]]
                                          :order-by [:rank :number]})
             :when (not= ref [project number])]
         {:id (id-str [project number]) :rank rank})))

(defn- place [tx project parent ref args]
  (core/place args (rank-order tx project parent ref) (str "a sibling under " (if parent (id-str [project parent])
                                                                                  "the roots"))))

;; ---------------------------------------------------------------- create, get, update

(defn create-page
  "Creates a page, r1. Returns row and version."
  [ctx args]
  (let [{:keys [title body parent summary] :as args} (contract/validate! :create-page args)
        body (or body "")]
    (core/write!
     ctx
     (fn [{:keys [tx at] :as w}]
       (let [pref (some-> parent (#(core/require-kind tx % "page")))
             project (core/project-for w tx args pref)
             _ (when pref (check-parent! tx [project nil] true pref))
             rank (place tx project (second pref) nil args)
             n (store/allocate! tx project :page)
             ref [project n]]
         (x! tx {:insert-into :page
                 :values [{:project project :number n :title title :body body :parent (second pref)
                           :rank (or rank n) :created at :updated at}]})
         (core/record! w {:subject ref :kind "page" :summary summary :revision 1
                          :related (when pref [pref])
                          :changes [(core/change :title "set" nil title)
                                    (when (seq body) (core/change :body "set" nil body))
                                    (when pref (core/change :parent "set" nil (id-str pref)))
                                    (when rank (core/change :rank "set" nil (core/rank-str rank)))]})
         (core/reindex-mentions! tx ref)
         (result tx ref))))))

(defn get-page
  "Everything about a page: its fields, full body verbatim, children as rows, mentions and backlinks. With revision,
  that revision's title and body. With include [\"revisions\"], the newest 50 revisions too."
  [ctx args]
  (let [{:keys [id revision include]} (contract/validate! :get-page args)]
    (core/with-read
      ctx
      (fn [conn]
        (let [ref (core/require-kind conn id "page")
              {:keys [version created] :as p} (page conn ref)]
          (merge (row p)
                 {:body (:body p) :version version :created created
                  :parent (some->> (parent-ref p) (core/ref-info conn))
                  :children (mapv #(row (page conn %)) (children-of conn ref))}
                 (core/mention-lists conn ref)
                 (when revision
                   (assoc (content-at conn ref (require-revision ref p revision)) :revision revision))
                 (when (some #{"revisions"} include)
                   (let [total (count (revision-events conn ref))]
                     {:revisions (revision-events conn ref :limit events/default-limit)
                      :revisions-total total
                      :revisions-truncated (> total events/default-limit)}))))))))

(defn- content-write!
  "Writes a page's new title and body (when either differs) and any move, as one event, making a revision only when
  the content changed. extra is merged into the event."
  [{:keys [tx] :as w} ref p {:keys [title body parent rank summary]} extra]
  (let [content? (or (not= title (:title p)) (not= body (:body p)))
        version (if content? (inc (:version p)) (:version p))
        old-parent (parent-ref p)
        changes (remove nil? [(core/set-change :title (:title p) title)
                              (core/set-change :body (:body p) body)
                              (core/set-change :parent (some-> old-parent id-str) (some-> parent id-str))
                              (when (not= rank (:rank p))
                                (core/change :rank "set" (core/rank-str (:rank p)) (core/rank-str rank)))])]
    (when (seq changes)
      (x! tx {:update :page :set {:title title :body body :parent (second parent) :rank rank :version version}
              :where (where-ref ref)})
      (core/record! w (merge {:subject ref :kind "page" :summary summary :changes changes
                              :revision (when content? version)
                              :related (when (not= old-parent parent) (remove nil? [old-parent parent]))}
                             extra))
      (when content? (core/reindex-mentions! tx ref)))
    (result tx ref)))

(defn update-page
  "Edits a page: title, body or body edits, parent (a move; null makes a root) and rank, with an edit summary. Returns
  row and version; the version moves only when the title or body changes."
  [ctx args]
  (let [{:keys [id title body body-edits summary] :as args} (contract/validate! :update-page args)]
    (core/write!
     ctx
     (fn [{:keys [tx] :as w}]
       (let [[project :as ref] (core/require-kind tx id "page")
             p (page tx ref)
             _ (check-version! ref p args (cond-> #{}
                                            title (conj :title)
                                            (or body body-edits) (conj :body)))
             body' (cond body body
                         body-edits (core/apply-body-edits (:body p) body-edits)
                         :else (:body p))
             old-parent (parent-ref p)
             parent (if (contains? args :parent)
                      (some-> (:parent args) (#(core/require-kind tx % "page")))
                      old-parent)
             _ (when (and parent (not= parent old-parent)) (check-parent! tx ref (not (archived? p)) parent))
             rank (or (place tx project (second parent) ref args) (:rank p))]
         (content-write! w ref p {:title (or title (:title p)) :body body' :parent parent :rank rank
                                  :summary summary}
                         nil))))))

;; ---------------------------------------------------------------- archive, unarchive, restore

(defn archive-page
  "Archives a page, with a summary saying why. Refused while it has live children, which it names. Archiving an
  archived page succeeds and changes nothing."
  [ctx args]
  (let [{:keys [id summary]} (contract/validate! :archive-page args)]
    (core/write!
     ctx
     (fn [{:keys [tx] :as w}]
       (let [ref (core/require-kind tx id "page")
             p (page tx ref)]
         (when-not (archived? p)
           (let [live (remove archived? (map #(page tx %) (children-of tx ref)))]
             (when (seq live)
               (fail "live_children" (str id " has live children: " (str/join ", " (map (comp :id brief) live))
                                          ". Archive or move them first; archiving never cascades.")
                     :id id :children (mapv brief live))))
           (x! tx {:update :page :set {:archived 1} :where (where-ref ref)})
           (core/record! w {:subject ref :kind "page" :summary summary
                            :changes [(core/change :archived "set" "false" "true")]}))
         (result tx ref))))))

(defn unarchive-page
  "Brings an archived page back, with a summary saying why. Refused beneath an archived parent. Unarchiving a live
  page succeeds and changes nothing."
  [ctx args]
  (let [{:keys [id summary]} (contract/validate! :unarchive-page args)]
    (core/write!
     ctx
     (fn [{:keys [tx] :as w}]
       (let [ref (core/require-kind tx id "page")
             p (page tx ref)]
         (when (archived? p)
           (when-let [parent (some->> (parent-ref p) (page tx))]
             (when (archived? parent)
               (archived-parent! parent "move this page elsewhere first")))
           (x! tx {:update :page :set {:archived 0} :where (where-ref ref)})
           (core/record! w {:subject ref :kind "page" :summary summary
                            :changes [(core/change :archived "set" "true" "false")]}))
         (result tx ref))))))

(defn restore-page
  "Makes a new revision with revision's title and body, recording which it restored from. Never touches parent, rank
  or archived. Restoring content identical to the current is a no-op."
  [ctx args]
  (let [{:keys [id revision summary] :as args} (contract/validate! :restore-page args)]
    (core/write!
     ctx
     (fn [{:keys [tx] :as w}]
       (let [ref (core/require-kind tx id "page")
             p (page tx ref)
             _ (require-revision ref p revision)
             _ (check-version! ref p args #{:title :body})
             {:keys [title body]} (content-at tx ref revision)]
         (content-write! w ref p {:title title :body body :parent (parent-ref p) :rank (:rank p) :summary summary}
                         {:restored-from revision}))))))

(defn page-revisions
  "Every revision of a page, oldest first: number, time, who, summary, and what it restored from."
  [ctx args]
  (let [{:keys [id]} (contract/validate! :page-revisions args)]
    (core/with-read ctx #(revision-events % (core/require-kind % id "page")))))

(defn diff-page
  "Compares two revisions of a page, by default the latest against the one before: the title before and after, and a
  unified line diff of the body. Before r1 the page is empty."
  [ctx args]
  (let [{:keys [id from to]} (contract/validate! :diff-page args)]
    (core/with-read
      ctx
      (fn [conn]
        (let [ref (core/require-kind conn id "page")
              p (page conn ref)
              to (require-revision ref p (or to (:version p)))
              from (if from (require-revision ref p from) (dec to))
              a (if (pos? from) (content-at conn ref from) {:title nil :body ""})
              b (content-at conn ref to)]
          {:id id :from from :to to :title-before (:title a) :title-after (:title b)
           :diff (diff/unified (:body a) (:body b) (str id " r" from) (str id " r" to))})))))
