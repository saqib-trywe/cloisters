(ns cloisters.events
  "Reading the event log: an item's timeline, the cross-item feed, and the event shape every surface shares."
  (:require [cloisters.contract :as contract]
            [cloisters.core :as core :refer [q]]
            [cloisters.diff :as diff]))

(def default-limit 50)

(defn load-events
  "The events matching where (a HoneySQL condition over the event table), in sequence order, each with its changes
  and the ids of the items it relates."
  [conn where & {:keys [limit desc]}]
  (let [rows (q conn (cond-> {:select :* :from :event :where where
                              :order-by [[:seq (if desc :desc :asc)]]}
                       limit (assoc :limit limit)))
        seqs (map :seq rows)
        changes (when (seq seqs)
                  (group-by :event (q conn {:select :* :from :change :where [:in :event seqs]
                                            :order-by [:event :position]})))
        related (when (seq seqs)
                  (group-by :event (q conn {:select :* :from :event-related :where [:in :event seqs]
                                            :order-by [:event :project :number]})))
        events (for [{:keys [seq project number kind at actor comment summary revision restored-from]} rows]
                 {:seq seq :id (core/id-str [project number]) :kind kind :at at :actor actor
                  :comment comment :summary summary :revision revision :restored-from restored-from
                  :changes (mapv #(select-keys % [:field :op :old :new]) (get changes seq))
                  :related (mapv #(core/id-str [(:project %) (:number %)]) (get related seq))})]
    (vec (if desc (reverse events) events))))

(defn timeline-where
  "The events on the timeline of the item at ref: those it is the subject of, and those that relate it."
  [[k n]]
  [:or [:and [:= :project k] [:= :number n]]
   [:in :seq {:select [:event] :from :event-related :where [:and [:= :project k] [:= :number n]]}]])

(defn latest
  "The latest n events on ref's timeline, oldest first within those, with the total and whether it was cut."
  [conn ref n]
  (let [total (:n (core/q1 conn {:select [[[:count :*] :n]] :from :event :where (timeline-where ref)}))]
    {:events (load-events conn (timeline-where ref) :limit n :desc true)
     :total total
     :truncated (> total n)}))

(defn compact
  "An event as an agent reads it: a body change as a unified diff rather than both full texts. The full texts stay
  in the HTTP history and the feed."
  [event]
  (update event :changes
          (partial mapv (fn [{:keys [field op old new] :as c}]
                          (if (= "body" field)
                            {:field field :op op :diff (diff/unified old new "before" "after")}
                            c)))))

(defn latest-seq
  "The newest event's sequence number, or 0 before any write."
  [ctx]
  (core/with-read ctx #(or (:n (core/q1 % {:select [[[:max :seq] :n]] :from :event})) 0)))

(defn- page-of [conn where {:keys [after limit]}]
  (let [limit (or limit default-limit)
        events (load-events conn [:and where [:> :seq (or after 0)]] :limit (inc limit))
        shown (vec (take limit events))]
    {:events shown
     :next-cursor (when (> (count events) limit) (:seq (peek shown)))
     :has-more (> (count events) limit)}))

(defn issue-events
  "The full history of an issue, page by page: events after the sequence number :after, up to :limit."
  [ctx args]
  (let [args (contract/validate! :issue-events args)]
    (core/with-read ctx
      (fn [conn]
        (page-of conn (timeline-where (core/require-kind conn (:id args) "issue")) args)))))

(defn feed
  "Every event after the sequence number :after, up to :limit, across all items: what live updates consume."
  [ctx args]
  (let [args (contract/validate! :events args)]
    (core/with-read ctx #(page-of % true args))))
