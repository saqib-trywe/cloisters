(ns cloisters.projects
  "Projects: created, listed, never removed, their keys never changed."
  (:require [cloisters.contract :as contract]
            [cloisters.core :as core :refer [q q1 x!]]))

(defn create-project
  "Creates a project and rescans all prose for its key in the same transaction, so ids written before it existed
  count as mentions now."
  [ctx args]
  (let [{:keys [key name]} (contract/validate! :create-project args)]
    (core/write! ctx
                 (fn [{:keys [tx at]}]
                   (when (q1 tx {:select [:key] :from :project :where [:= :key key]})
                     (core/fail "invalid_argument" (str "Project " key " already exists.")
                                :argument "key" :reason "already exists"))
                   (x! tx {:insert-into :project :values [{:key key :name name :created at}]})
                   (core/rescan-mentions! tx key)
                   {:key key :name name :created at}))))

(defn list-projects
  "Every project, by key."
  [ctx args]
  (contract/validate! :list-projects args)
  (core/with-read ctx #(q % {:select [:key :name :created] :from :project :order-by [:key]})))
