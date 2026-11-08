(ns cloisters.search
  "One search over issues and pages: a query in the shared grammar (cloisters.query), compiled through HoneySQL and
  paged by keyset cursors.

  Results are mixed rows, each carrying its kind, in a total order: the sort key, then project, then number. Archived
  pages are left out unless the query asks for them with archived:, or names them with id:. Paging guarantees that
  items which did not change while you paged appear exactly once; an item whose sort key moves mid-paging may repeat
  or be missed."
  (:require [cloisters.contract :as contract]
            [cloisters.core :as core :refer [fail q]]
            [cloisters.issues :as issues]
            [cloisters.pages :as pages]
            [cloisters.query :as query]
            [clojure.edn :as edn]
            [clojure.string :as str])
  (:import (java.nio.charset StandardCharsets)
           (java.time ZoneId)
           (java.util Base64)))

(def default-limit 50)

;; ---------------------------------------------------------------- the items

(def ^:private columns
  "The columns both kinds share in the search, with nil where a kind has no such field."
  [:project :number :kind :title :rank :created :updated :version :parent
   :status :resolution :assignee :claimed :closed :archived])

(defn- branch [kind]
  (let [own (if (= "issue" kind)
              #{:status :resolution :assignee :claimed :closed}
              #{:archived})]
    {:select (vec (for [c columns]
                    (cond (= :kind c) [[:inline kind] :kind]
                          (or (own c) (not (#{:status :resolution :assignee :claimed :closed :archived} c))) c
                          :else [nil c])))
     :from (keyword kind)}))

(defn- items
  "The relation searched: one row per issue and page of the kinds asked for."
  [kinds]
  (let [branches (map branch (sort kinds))]
    (if (next branches) {:union-all (vec branches)} (first branches))))

;; ---------------------------------------------------------------- conditions

(defn- this [[k n]] [:and [:= :it.project k] [:= :it.number n]])

(defn- descendants-of
  "The numbers beneath number n in project k's tree of table, at any depth."
  [table k n]
  {:with-recursive [[[:sub {:columns [:number]}]
                     {:union [{:select [:number] :from table :where [:and [:= :project k] [:= :parent n]]}
                              {:select [:t.number] :from [[table :t]]
                               :join [:sub [:= :t.parent :sub.number]]
                               :where [:= :t.project k]}]}]]
   :select [:number] :from :sub})

(defn- open-blocker-of [blocked]
  {:select [1] :from [[:blocks :b]]
   :join [[:issue :x] [:and [:= :x.project :b.project] [:= :x.number :b.blocker]]]
   :where [:and [:= :b.project :it.project] [:= :b.blocked blocked] [:= :x.status "open"]]})

(def ^:private blocked [:exists (open-blocker-of :it.number)])
(def ^:private unblocked [:and [:= :it.kind "issue"] [:not blocked]])

(defn- fts-phrase
  "v as one FTS5 phrase: the trigram tokenizer makes it a case-insensitive substring match."
  [v]
  (str "\"" (str/replace v "\"" "\"\"") "\""))

(defn- text-match
  "Items whose prose contains v. FTS5 trigrams need three characters; shorter text is matched by scanning."
  [^String v]
  {:select [:project :number] :from :item-text
   :where (if (>= (.codePointCount v 0 (count v)) 3)
            [:raw ["item_text MATCH " [:lift (fts-phrase v)]]]
            (into [:or] (for [col [:title :body :comments]]
                          [:> [:instr [:lower col] [:lower v]] 0])))})

(defn- prefix [v] (subs v 0 (dec (count v))))
(defn- prefix? [v] (str/ends-with? v "*"))

(defn- prefix-of
  "col starts with p. For labels, which are case-insensitive, both sides are lowercased."
  [col ^String p fold]
  (let [n (.codePointCount p 0 (count p))]
    (if fold
      [:= [:lower [:substr col 1 n]] [:lower p]]
      [:= [:substr col 1 n] p])))

(defn- time-column [name] (keyword (str "it." (first (str/split name #"-")))))

(defn- value-condition
  "The condition one value of a filter sets. kind-of says what an id names."
  [{:keys [kind-of now zone]} name v]
  (case name
    "kind" [:= :it.kind v]
    "project" (if (= "*" v) true [:= :it.project v])
    "id" (this (core/parse-id v))
    "parent" (let [[k n :as ref] (core/parse-id v)]
               (if-let [kind (kind-of ref)]
                 [:and [:= :it.kind kind] [:= :it.project k] [:= :it.parent n]]
                 false))
    "under" (let [[k n :as ref] (core/parse-id v)]
              (if-let [kind (kind-of ref)]
                [:and [:= :it.kind kind] [:= :it.project k] [:in :it.number (descendants-of (keyword kind) k n)]]
                false))
    "mentions" (let [[k n :as ref] (core/parse-id v)]
                 (if (kind-of ref)
                   [:exists {:select [1] :from :mention
                             :where [:and [:= :src-project :it.project] [:= :src-number :it.number]
                                     [:= :dst-project k] [:= :dst-number n]]}]
                   false))
    "mentioned-by" (let [[k n] (core/parse-id v)]
                     [:exists {:select [1] :from :mention
                               :where [:and [:= :src-project k] [:= :src-number n]
                                       [:= :dst-project :it.project] [:= :dst-number :it.number]]}])
    "text" [:in [:composite :it.project :it.number] (text-match v)]
    "archived" (case v
                 "no" [:or [:<> :it.kind "page"] [:= :it.archived 0]]
                 "yes" [:= :it.archived 1]
                 "any" true)
    "status" [:= :it.status v]
    "resolution" [:= :it.resolution v]
    "assignee" (cond (= "none" v) [:and [:= :it.kind "issue"] [:= :it.assignee nil]]
                     (= "any" v) [:<> :it.assignee nil]
                     (prefix? v) (prefix-of :it.assignee (prefix v) false)
                     :else [:= :it.assignee v])
    "label" [:exists {:select [1] :from :label
                      :where [:and [:= :label.project :it.project] [:= :label.number :it.number]
                              (if (prefix? v)
                                (prefix-of :label.label (prefix v) true)
                                [:= :label.label v])]}]
    "blocks" (let [[k n] (core/parse-id v)]
               [:exists {:select [1] :from :blocks
                         :where [:and [:= :project :it.project] [:= :blocker :it.number]
                                 [:= :project k] [:= :blocked n]]}])
    "blocked-by" (let [[k n] (core/parse-id v)]
                   [:exists {:select [1] :from :blocks
                             :where [:and [:= :project :it.project] [:= :blocked :it.number]
                                     [:= :project k] [:= :blocker n]]}])
    (let [t (core/timestamp (query/instant-of v now zone))]
      [(if (str/ends-with? name "-before") :< :>) (time-column name) t])))

(defn- clause-condition
  "The condition a clause sets: its values OR together, and negation is the exact complement, true where the clause
  is false or unknown."
  [env {:keys [name negated values]}]
  (let [c (case name
            "ready" [:and [:= :it.status "open"] unblocked [:= :it.assignee nil]]
            "blocked" blocked
            "unblocked" unblocked
            "sort" true
            (let [cs (map #(value-condition env name %) values)]
              (if (next cs) (into [:or] cs) (first cs))))]
    (if negated [:not [:coalesce c false]] c)))

;; ---------------------------------------------------------------- order and cursors

(defn- sort-of
  "The query's sort as [field direction]; rank ascending by default."
  [clauses]
  (let [v (some #(when (= "sort" (:name %)) (first (:values %))) clauses)
        [_ field dir] (re-matches #"(\w+)(?:-(asc|desc))?" (or v "rank"))]
    [field (keyword (or dir "asc"))]))

(defn- sort-key [field]
  (if (= "closed" field) [:coalesce :it.closed ""] (keyword (str "it." field))))

(defn- encode-cursor [v]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) (.getBytes (pr-str v) StandardCharsets/UTF_8)))

(defn- decode-cursor
  "The position a cursor holds, [field key project number], when it was made for this sort."
  [^String cursor field]
  (let [bad #(fail "invalid_argument" (str "invalid argument cursor: " %) :argument "cursor" :reason %)
        v (try (edn/read-string (String. (.decode (Base64/getUrlDecoder) cursor) StandardCharsets/UTF_8))
               (catch Exception _ nil))]
    (when-not (and (vector? v) (= 4 (count v)) (string? (v 2)) (int? (v 3)) (or (string? (v 1)) (number? (v 1))))
      (bad "is not a cursor this daemon issued; pass next_cursor back unchanged"))
    (when-not (= field (v 0))
      (bad (str "was made for sort:" (v 0) ", not sort:" field "; page with the query that issued it")))
    v))

(defn- after-cursor
  "Rows strictly after the position [_ key project number] in the order: by the sort key in direction, then project
  and number ascending."
  [field dir [_ v k n]]
  (let [e (sort-key field)
        tie [:> [:composite :it.project :it.number] [:composite k n]]]
    [:or [(if (= :desc dir) :< :>) e v] [:and [:= e v] tie]]))

;; ---------------------------------------------------------------- search

(defn- result-row [conn {:keys [kind] :as it}]
  (if (= "issue" kind) (issues/row conn it) (pages/row it)))

(defn search
  "Issues and pages matching a query, a page at a time: {:effective-query :results :next-cursor :has-more}.
  Unscoped queries stay in the bound project; effective-query shows that scoping and any implied kind."
  [ctx args]
  (let [{:keys [query cursor limit]} (contract/validate! :search args)
        limit (or limit default-limit)
        clauses (query/parse query)]
    (core/with-read
      ctx
      (fn [conn]
        (let [{:keys [clauses kinds effective]} (query/scope clauses (:project ctx))
              projects (set (core/project-keys conn))]
          (doseq [{:keys [name values]} clauses
                  :when (= "project" name)
                  v values
                  :when (not (or (= "*" v) (projects v)))]
            (fail "invalid_query" (str "Invalid query at `project:" v "`: there is no project " v ".")
                  :token (str "project:" v) :reason (str "there is no project " v)
                  :suggestions (mapv #(str "project:" %) (sort projects))))
          (let [[field dir] (sort-of clauses)
                position (some-> cursor (decode-cursor field))
                env {:kind-of #(core/kind-of conn %) :now (core/instant ctx)
                     :zone (or (:zone ctx) (ZoneId/systemDefault))}
                defaults (when-not (some #(#{"archived"} (:name %)) clauses)
                           (if (some #(and (= "id" (:name %)) (not (:negated %))) clauses)
                             []
                             [{:name "archived" :negated false :values ["no"]}]))
                where (cond-> (into [:and] (map #(clause-condition env %) (concat clauses defaults)))
                        position (conj (after-cursor field dir position)))
                rows (when (seq kinds)
                       (q conn {:select [:it.* [(sort-key field) :sort-key]]
                                :from [[(items kinds) :it]]
                                :where where
                                :order-by [[(sort-key field) dir] [:it.project :asc] [:it.number :asc]]
                                :limit (inc limit)}))
                shown (take limit rows)
                more (> (count rows) limit)
                last-row (last shown)]
            {:effective-query effective
             :results (mapv #(result-row conn %) shown)
             :next-cursor (when more
                            (encode-cursor [field (:sort-key last-row) (:project last-row) (:number last-row)]))
             :has-more more}))))))
