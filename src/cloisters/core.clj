(ns cloisters.core
  "What every operation shares: rejections that name the conflict, ids, the read and write transactions, one event
  per write, edge and mention refs, mention indexing, body edits and relative ranks.

  Operations take a context map:

  - :store   the open store
  - :actor   who writes, by surface and client: \"mcp:claude-code\", \"cli\", \"web\"
  - :project the bound project, if any
  - :clock   optional, a fn returning the current java.time.Instant
  - :notify  optional, called after each commit with the new events' sequence numbers

  Inside a write, the context also carries :tx, the write transaction, and :at, the write's one timestamp."
  (:require [cloisters.contract :as contract]
            [cloisters.markdown :as markdown]
            [cloisters.store :as store]
            [clojure.string :as str]
            [honey.sql :as sql]
            [next.jdbc :as jdbc])
  (:import (java.time Instant ZoneOffset)
           (java.time.format DateTimeFormatter)))

;; ---------------------------------------------------------------- rejections

(defn fail
  "Refuses the operation. code is the contract's error code, message is written for the reader, and facts are what
  the reader needs to act without asking again."
  [code message & {:as facts}]
  (throw (ex-info message (assoc facts :error code))))

;; ---------------------------------------------------------------- ids

(defn parse-id
  "\"CLO-7\" as [\"CLO\" 7]. The contract has already checked the shape."
  [id]
  (let [[_ k n] (re-matches #"([A-Z][A-Z0-9]{1,9})-(\d+)" id)]
    [k (parse-long n)]))

(defn id-str [[k n]] (str k "-" n))

(defn- where-ref [[k n]] [:and [:= :project k] [:= :number n]])

;; ---------------------------------------------------------------- queries

(defn q [conn honey] (jdbc/execute! conn (sql/format honey) store/opts))
(defn q1 [conn honey] (jdbc/execute-one! conn (sql/format honey) store/opts))
(defn x! [conn honey] (jdbc/execute-one! conn (sql/format honey)))

(defn by-ref
  "The issue or page row (table :issue or :page) at ref, or nil."
  [conn table ref]
  (q1 conn {:select :* :from table :where (where-ref ref)}))

(defn kind-of
  "\"issue\" or \"page\": what the id names, or nil when nothing has it yet."
  [conn ref]
  (:kind (q1 conn {:select [:kind] :from :item :where (where-ref ref)})))

(defn project-keys [conn]
  (mapv :key (q conn {:select [:key] :from :project :order-by [:key]})))

(defn require-project
  "key, when it names a project; otherwise unknown_project, listing the keys that exist."
  [conn key]
  (let [keys (project-keys conn)]
    (when-not (some #{key} keys)
      (fail "unknown_project"
            (str "There is no project " key "."
                 (if (seq keys) (str " Projects: " (str/join ", " keys) ".") " No projects exist yet."))
            :project key :projects keys))
    key))

(defn require-kind
  "The ref for id, when it names a kind (\"issue\" or \"page\"). Otherwise not_found, or wrong_kind naming what it is."
  [conn id kind]
  (let [[k :as ref] (parse-id id)
        actual (kind-of conn ref)]
    (cond
      actual (if (= actual kind)
               ref
               (fail "wrong_kind" (str id " is " (if (= actual "page") "a page" "an issue") ", not "
                                       (if (= kind "page") "a page" "an issue") ".")
                     :id id :kind actual))
      (q1 conn {:select [:key] :from :project :where [:= :key k]})
      (fail "not_found" (str id " does not exist.") :id id)
      :else (require-project conn k))))

(defn project-for
  "The project a create writes to: the explicit one, else the parent's, else the bound one."
  [ctx tx {:keys [project]} parent-ref]
  (require-project tx (or project
                          (first parent-ref)
                          (:project ctx)
                          (fail "invalid_argument" "Name a project: this connection is bound to none."
                                :argument "project" :reason "required when unbound"))))

;; ---------------------------------------------------------------- reads and writes

(def ^:private stamp (.withZone (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'") ZoneOffset/UTC))

(defn- now [ctx]
  (.format stamp ^Instant ((or (:clock ctx) #(Instant/now)))))

(defn with-read
  "Runs (f conn) in one read transaction on the read pool, so several queries see one moment of the store."
  [ctx f]
  (jdbc/with-transaction [tx (store/reads (:store ctx))]
    (f tx)))

(defn write!
  "Runs (f w) in the one write transaction, where w is ctx plus :tx and :at, and returns its result. After the commit,
  hands the events it recorded to :notify."
  [ctx f]
  (let [seqs (atom [])
        result (store/write! (:store ctx) #(f (assoc ctx :tx % :at (now ctx) ::seqs seqs)))]
    (when-let [notify (and (seq @seqs) (:notify ctx))]
      (notify @seqs))
    result))

;; ---------------------------------------------------------------- events

(defn rank-str
  "A rank as text: whole numbers without a decimal point."
  [r]
  (let [r (double r)]
    (if (and (== r (Math/rint r)) (< (Math/abs r) 1e15)) (str (long r)) (str r))))

(defn change
  "One field change, named as on the wire, its values as text."
  [field op old new]
  {:field (contract/wire-name field) :op op :old (some-> old str) :new (some-> new str)})

(defn set-change
  "A set change when old and new differ, else nil."
  [field old new]
  (when (not= old new) (change field "set" old new)))

(defn record!
  "Records the write's one event: its changes in order, the items it relates, and moves updated on the subject and
  each related item. A write that changes nothing and carries no comment records nothing; returns the event's
  sequence number or nil."
  [{:keys [tx at actor] ::keys [seqs]}
   {:keys [subject kind changes comment summary revision restored-from related]}]
  (let [changes (vec (remove nil? changes))]
    (when (or (seq changes) comment)
      (let [[k n] subject
            seq-no (:seq (q1 tx {:insert-into :event
                                 :values [{:project k :number n :kind kind :at at :actor actor :comment comment
                                           :summary summary :revision revision :restored-from restored-from}]
                                 :returning [:seq]}))
            related (disj (set related) subject)]
        (doseq [[i {:keys [field op old new]}] (map-indexed vector changes)]
          (x! tx {:insert-into :change
                  :values [{:event seq-no :position (inc i) :field field :op op :old old :new new}]}))
        (doseq [[rk rn] (sort related)]
          (x! tx {:insert-into :event-related :values [{:event seq-no :project rk :number rn}]}))
        (doseq [ref (conj related subject)]
          (x! tx {:update (keyword (kind-of tx ref)) :set {:updated at} :where (where-ref ref)}))
        (swap! seqs conj seq-no)
        seq-no))))

;; ---------------------------------------------------------------- refs

(defn ref-info
  "An edge or mention list entry: id, kind and title, plus status for an issue or archived for a page. nil when
  nothing has the id yet."
  [conn ref]
  (case (kind-of conn ref)
    "issue" (let [{:keys [title status]} (by-ref conn :issue ref)]
              {:id (id-str ref) :kind "issue" :title title :status status})
    "page" (let [{:keys [title archived]} (by-ref conn :page ref)]
             {:id (id-str ref) :kind "page" :title title :archived (= 1 archived)})
    nil))

(defn mention-lists
  "What the item at ref mentions and what mentions it, each existing entry as a ref, ordered by id."
  [conn [k n]]
  (letfn [(refs [rows] (vec (keep #(ref-info conn %) (sort rows))))]
    {:mentions (refs (map (juxt :dst-project :dst-number)
                          (q conn {:select [:dst-project :dst-number] :from :mention
                                   :where [:and [:= :src-project k] [:= :src-number n]]})))
     :backlinks (refs (map (juxt :src-project :src-number)
                           (q conn {:select [:src-project :src-number] :from :mention
                                    :where [:and [:= :dst-project k] [:= :dst-number n]]})))}))

;; ---------------------------------------------------------------- mentions

(defn- prose
  "The title and the markdown texts of the item at ref: an issue's body and comments, a page's body."
  [tx [k n :as ref]]
  (case (kind-of tx ref)
    "issue" (let [{:keys [title body]} (by-ref tx :issue ref)]
              [title (cons body (map :comment (q tx {:select [:comment] :from :event
                                                     :where [:and [:= :project k] [:= :number n]
                                                             [:<> :comment nil]]
                                                     :order-by [:seq]})))])
    "page" (let [{:keys [title body]} (by-ref tx :page ref)]
             [title [body]])))

(defn reindex-mentions!
  "Rewrites the mentions the item at ref makes from its current prose. A token counts when its project exists; it may
  name something not created yet. Nothing mentions itself. Writes no event."
  [tx [k n :as ref]]
  (let [[title texts] (prose tx ref)
        keys (set (project-keys tx))
        targets (->> (concat (markdown/title-mentions title) (mapcat markdown/mentions texts))
                     (filter (comp keys first))
                     (remove #{ref})
                     distinct)]
    (x! tx {:delete-from :mention :where [:and [:= :src-project k] [:= :src-number n]]})
    (doseq [[dk dn] targets]
      (x! tx {:insert-into :mention :values [{:src-project k :src-number n :dst-project dk :dst-number dn}]}))))

(defn rescan-mentions!
  "Reindexes every item whose prose contains key-, so a new project's key counts in text written before it existed."
  [tx key]
  (let [needle (str key "-")]
    (doseq [{:keys [project number]} (q tx {:select [:project :number] :from :item-text
                                            :where [:or [:> [:instr :title needle] 0]
                                                    [:> [:instr :body needle] 0]
                                                    [:> [:instr :comments needle] 0]]})]
      (reindex-mentions! tx [project number]))))

;; ---------------------------------------------------------------- body edits

(defn- occurrences [^String s ^String sub]
  (loop [from 0 n 0]
    (let [i (.indexOf s sub (int from))]
      (if (neg? i) n (recur (+ i (count sub)) (inc n))))))

(defn apply-body-edits
  "body with each {:old :new} applied in order, where every old must occur exactly once in the body as edited so far.
  Otherwise edit_mismatch, naming the edit and whether its old text was missing or ambiguous."
  [body edits]
  (reduce (fn [^String body [i {:keys [old new] :as edit}]]
            (case (occurrences body old)
              1 (str/replace-first body old (str/re-quote-replacement new))
              0 (fail "edit_mismatch" (str "body_edits[" i "]: its old text is not in the body. Read the body again "
                                           "and quote it exactly.")
                      :index i :edit edit :reason "missing")
              (fail "edit_mismatch" (str "body_edits[" i "]: its old text occurs more than once in the body. Quote "
                                         "more of the surrounding text so it matches once.")
                    :index i :edit edit :reason "ambiguous")))
          body
          (map-indexed vector edits)))

;; ---------------------------------------------------------------- relative ranks

(defn place
  "The rank a write sets from args' :rank, :rank-before or :rank-after, or nil when it sets none. order is everything
  the item sorts among, itself excluded, as {:id :rank} in order. A relative rank is the midpoint between the named
  neighbour and the one beside it; when no double fits between them, rank_precision names both."
  [{:keys [rank rank-before rank-after]} order what]
  (cond
    rank rank
    (or rank-before rank-after)
    (let [anchor (or rank-before rank-after)
          arg (if rank-before "rank_before" "rank_after")
          i (or (first (keep-indexed #(when (= anchor (:id %2)) %1) order))
                (fail "invalid_argument" (str arg " must name " what "; " anchor " is not one.")
                      :argument arg :reason (str "must name " what)))
          a (nth order i)
          b (get order (if rank-before (dec i) (inc i)))
          r (if b
              (/ (+ (:rank a) (:rank b)) 2.0)
              ((if rank-before - +) (:rank a) 1.0))]
      (if (or (nil? b) (< (min (:rank a) (:rank b)) r (max (:rank a) (:rank b))))
        r
        (fail "rank_precision" (str "No rank fits between " (:id a) " and " (:id b) "; set a rank yourself.")
              :neighbours (if rank-before [b a] [a b]))))))
