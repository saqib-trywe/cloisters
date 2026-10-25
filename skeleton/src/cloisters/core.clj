(ns cloisters.core
  "PROTOTYPE. The one core every surface calls: HTTP, MCP and the web UI. Arguments and results are unqualified
  kebab-case maps; the core validates its arguments against the contract."
  (:require [clojure.string :as str]
            [cloisters.contract :as contract]
            [cloisters.markdown :as markdown]
            [cloisters.store :as store]
            [honey.sql :as sql]
            [next.jdbc :as jdbc])
  (:import (java.time Instant)))

(defn- fail [code status message & {:as facts}]
  (throw (ex-info message (merge {:error code :status status} facts))))

(defn- now [] (str (Instant/now)))

(defn parse-id [id]
  (let [[_ k n] (re-matches #"([A-Z][A-Z0-9]{1,9})-([1-9][0-9]*)" (str id))]
    (when k [k (parse-long n)])))

(defn id-str [k n] (str k "-" n))

(defn- q1 [conn honey] (jdbc/execute-one! conn (sql/format honey) store/opts))
(defn- q [conn honey] (jdbc/execute! conn (sql/format honey) store/opts))
(defn- x! [conn honey] (jdbc/execute! conn (sql/format honey)))

(defn- kind-of [conn [k n]]
  (:kind (q1 conn {:select [:kind] :from :item :where [:and [:= :project k] [:= :number n]]})))

(defn- version-of [conn [k n]]
  (:v (q1 conn {:select [[[:max :revision] :v]] :from :event :where [:and [:= :project k] [:= :number n]]})))

(defn- require-kind [conn id want]
  (let [[k n :as ref] (parse-id id)
        kind (kind-of conn ref)]
    (cond (nil? kind) (fail "not_found" 404 (str id " does not exist") :id id)
          (not= kind want) (fail "wrong_kind" 404 (str id " is " (if (= kind "page") "a page" "an issue"))
                                 :id id :kind kind)
          :else ref)))

;; ---------------------------------------------------------------- rows and refs

(defn- issue-row [{:keys [project number title status updated]}]
  {:id (id-str project number) :kind "issue" :title title :status status :updated updated})

(defn- page-row [conn {:keys [project number title parent archived rank updated]}]
  (cond-> {:id (id-str project number) :kind "page" :title title :archived (= 1 archived)
           :rank (double rank) :updated updated :revision (version-of conn [project number])}
    parent (assoc :parent (id-str project parent))))

(defn- issue-by [conn [k n]] (q1 conn {:select :* :from :issue :where [:and [:= :project k] [:= :number n]]}))
(defn- page-by [conn [k n]] (q1 conn {:select :* :from :page :where [:and [:= :project k] [:= :number n]]}))

(defn- ref-info [conn [k n :as ref]]
  (let [base {:id (id-str k n)}]
    (case (kind-of conn ref)
      "issue" (let [i (issue-by conn ref)] (assoc base :kind "issue" :title (:title i) :status (:status i)))
      "page" (let [p (page-by conn ref)] (assoc base :kind "page" :title (:title p) :archived (= 1 (:archived p))))
      base)))

(defn lookup
  "For rendering: [key number] -> {:kind :title :archived}, or nil when nothing has the id yet."
  [{:keys [store]}]
  (let [conn (:reads store)] (fn [ref] (let [r (ref-info conn ref)] (when (:kind r) r)))))

(defn- mention-lists [conn [k n]]
  {:mentions (mapv #(ref-info conn [(:dst-project %) (:dst-number %)])
                   (q conn {:select [:dst-project :dst-number] :from :mention
                            :where [:and [:= :src-project k] [:= :src-number n]]
                            :order-by [:dst-project :dst-number]}))
   :backlinks (mapv #(ref-info conn [(:src-project %) (:src-number %)])
                    (q conn {:select [:src-project :src-number] :from :mention
                             :where [:and [:= :dst-project k] [:= :dst-number n]]
                             :order-by [:src-project :src-number]}))})

;; ---------------------------------------------------------------- write helpers

(defn- allocate! [tx project]
  (when-not (q1 tx {:select [:key] :from :project :where [:= :key project]})
    (fail "not_found" 404 (str "no project " project) :project project))
  (let [n (dec (:next-number (q1 tx {:update :project :set {:next-number [:+ :next-number 1]}
                                     :where [:= :key project] :returning [:next-number]})))]
    n))

(defn- record! [tx [k n] {:keys [actor type revision summary changes]}]
  (let [at (now)
        seq (:seq (q1 tx {:insert-into :event
                          :values [{:project k :number n :at at :actor actor :type type
                                    :revision revision :summary summary}]
                          :returning [:seq]}))]
    (doseq [[field [before after]] changes]
      (x! tx {:insert-into :change :values [{:seq seq :field (name field) :before before :after after}]}))
    {:seq seq :type type :actor actor :at at :id (id-str k n)}))

(defn- index! [tx [k n] title body]
  (let [project-keys (set (map :key (q tx {:select [:key] :from :project})))
        targets (->> (markdown/mentions title body)
                     (filter (fn [[mk mn]] (and (project-keys mk) (not= [mk mn] [k n])))))]
    (x! tx {:delete-from :mention :where [:and [:= :src-project k] [:= :src-number n]]})
    (doseq [[mk mn] targets]
      (x! tx {:insert-into :mention :values [{:src-project k :src-number n :dst-project mk :dst-number mn}]}))
    (x! tx {:delete-from :item-text :where [:and [:= :project k] [:= :number n]]})
    (x! tx {:insert-into :item-text :values [{:project k :number n :title title :body body}]})))

(defn- write! [{:keys [store publish]} f]
  (let [[result event] (store/write! store f)]
    (when event (publish event))
    result))

;; ---------------------------------------------------------------- issues

(defn create-issue [ctx args actor]
  (let [{:keys [title body project]} (contract/validate! :create-issue args)
        project (or project (:project ctx) (fail "invalid_argument" 400 "project is required when unbound"))
        body (or body "")]
    (write! ctx
            (fn [tx]
              (let [n (allocate! tx project) ref [project n] t (now)]
                (x! tx {:insert-into :item :values [{:project project :number n :kind "issue"}]})
                (x! tx {:insert-into :issue :values [{:project project :number n :title title :body body
                                                      :created t :updated t}]})
                (index! tx ref title body)
                (let [ev (record! tx ref {:actor actor :type "issue_created"
                                          :changes {:title [nil title] :body [nil body]}})]
                  [(issue-row (issue-by tx ref)) ev]))))))

(defn get-issue [{:keys [store]} args]
  (let [{:keys [id]} (contract/validate! :get-issue args)
        conn (:reads store)
        ref (require-kind conn id "issue")
        i (issue-by conn ref)]
    (merge (issue-row i) {:body (:body i)} (mention-lists conn ref))))

;; ---------------------------------------------------------------- pages

(defn create-page [ctx args actor]
  (let [{:keys [title body project parent summary]} (contract/validate! :create-page args)
        project (or project (some-> parent parse-id first) (:project ctx)
                    (fail "invalid_argument" 400 "project is required when unbound"))
        body (or body "")]
    (write! ctx
            (fn [tx]
              (let [parent-ref (when parent (require-kind tx parent "page"))
                    _ (when (and parent-ref (not= (first parent-ref) project))
                        (fail "cross_project_edge" 409 (str parent " is in another project") :parent parent :project project))
                    n (allocate! tx project) ref [project n] t (now)]
                (x! tx {:insert-into :item :values [{:project project :number n :kind "page"}]})
                (x! tx {:insert-into :page :values [{:project project :number n :title title :body body
                                                     :parent (second parent-ref) :rank (double n)
                                                     :created t :updated t}]})
                (index! tx ref title body)
                (let [ev (record! tx ref {:actor actor :type "page_created" :revision 1 :summary summary
                                          :changes {:title [nil title] :body [nil body]}})]
                  [(assoc (page-row tx (page-by tx ref)) :version 1) ev]))))))

(defn- at-revision [conn [k n] field r]
  (:after (q1 conn {:select [:c.after] :from [[:change :c]] :join [[:event :e] [:= :e.seq :c.seq]]
                    :where [:and [:= :e.project k] [:= :e.number n] [:= :c.field field] [:<= :e.revision r]]
                    :order-by [[:e.revision :desc]] :limit 1})))

(defn get-page [{:keys [store]} args]
  (let [{:keys [id revision]} (contract/validate! :get-page args)
        conn (:reads store)
        [k :as ref] (require-kind conn id "page")
        p (page-by conn ref)
        version (version-of conn ref)
        _ (when (and revision (> revision version))
            (fail "not_found" 404 (str id " has no revision " revision) :id id :revision revision))
        p (if (and revision (not= revision version))
            (assoc p :title (at-revision conn ref "title" revision) :body (at-revision conn ref "body" revision))
            p)]
    (merge (page-row conn p)
           {:body (:body p) :version version
            :children (mapv #(page-row conn %)
                            (q conn {:select :* :from :page :where [:and [:= :project k] [:= :parent (second ref)]]
                                     :order-by [:rank :number]}))}
           (when revision {:revision revision})
           (mention-lists conn ref))))

(defn update-page [ctx args actor]
  (let [{:keys [id expected-version title body summary]} (contract/validate! :update-page args)]
    (write! ctx
            (fn [tx]
              (let [ref (require-kind tx id "page")
                    p (page-by tx ref)
                    version (version-of tx ref)]
                (when (and expected-version (not= expected-version version))
                  (fail "stale_version" 409 (str id " is at version " version ", not " expected-version)
                        :id id :current-version version :title (:title p) :body (:body p)))
                (let [changes (cond-> {}
                                (and title (not= title (:title p))) (assoc :title [(:title p) title])
                                (and body (not= body (:body p))) (assoc :body [(:body p) body]))]
                  (if (empty? changes)
                    [(assoc (page-row tx p) :version version) nil]
                    (let [title (or title (:title p)) body (or body (:body p))]
                      (x! tx {:update :page :set {:title title :body body :updated (now)}
                              :where [:and [:= :project (first ref)] [:= :number (second ref)]]})
                      (index! tx ref title body)
                      (let [ev (record! tx ref {:actor actor :type "page_updated" :revision (inc version)
                                                :summary summary :changes changes})]
                        [(assoc (page-row tx (page-by tx ref)) :version (inc version)) ev])))))))))

;; ---------------------------------------------------------------- search

(defn- parse-query [q]
  (reduce (fn [acc token]
            (let [[_ f v] (re-matches #"([a-z-]+):(.*)" token)]
              (case f
                "kind" (if (#{"issue" "page"} v) (assoc acc :kind v)
                           (fail "invalid_argument" 400 (str "kind: takes issue or page, not " v) :token token))
                "text" (assoc acc :text (str/replace v #"^\"|\"$" ""))
                (fail "invalid_argument" 400 (str "unknown filter " token " (this skeleton knows kind: and text:)")
                      :token token))))
          {}
          (re-seq #"[a-z-]+:\"[^\"]*\"|\S+" (or q ""))))

(defn search [{:keys [store] :as ctx} args]
  (let [{query :q :keys [project]} (contract/validate! :search args)
        {:keys [kind text]} (parse-query query)
        conn (:reads store)
        project (or project (:project ctx))
        where (fn [extra]
                (cond-> [:and extra]
                  project (conj [:= :project project])
                  text (conj [:in [:composite :project :number]
                              {:select [:project :number] :from :item-text
                               :where [:raw ["item_text MATCH " [:lift (str "\"" (str/replace text "\"" "\"\"") "\"")]]]}])))
        issues (when (not= kind "page") (map issue-row (q conn {:select :* :from :issue :where (where [:= 1 1])})))
        pages (when (not= kind "issue")
                (map #(page-row conn %) (q conn {:select :* :from :page :where (where [:= :archived 0])})))
        rows (sort-by (comp parse-id :id) (concat issues pages))]
    {:effective-query (str/join " " (remove nil? [(when kind (str "kind:" kind))
                                                  (when (not= kind "issue") "archived:no")
                                                  (when text (str "text:" (pr-str text)))]))
     :results (vec (take 50 rows))
     :has-more (> (count rows) 50)}))

(defn events-after [{:keys [store]} after]
  (mapv (fn [{:keys [project number] :as e}] (-> e (dissoc :project :number) (assoc :id (id-str project number))))
        (q (:reads store) {:select [:seq :project :number :type :actor :at] :from :event
                           :where [:> :seq after] :order-by [:seq] :limit 500})))

(defn latest-seq [{:keys [store]}]
  (or (:s (q1 (:reads store) {:select [[[:max :seq] :s]] :from :event})) 0))
