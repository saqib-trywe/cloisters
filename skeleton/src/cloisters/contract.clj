(ns cloisters.contract
  "PROTOTYPE. The daemon-only contract: one malli schema per operation's arguments and result. The core validates
  arguments against these; MCP derives inputSchema and outputSchema from them. Keys are kebab-case here and
  snake_case on the wire."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [malli.core :as m]
            [malli.error :as me]
            [malli.util :as mu]
            [malli.json-schema :as json-schema]))

(def Key [:re {:description "project key, e.g. CLO"} #"^[A-Z][A-Z0-9]{1,9}$"])
(def Id [:re {:description "an issue or page id, e.g. CLO-7"} #"^[A-Z][A-Z0-9]{1,9}-[1-9][0-9]*$"])
(def Title [:and {:description "one line, not blank"} :string [:re #"^\s*\S.*$"]])
(def Version [:int {:min 1}])

(def Ref
  [:map {:description "a mentioned or mentioning item; kind is absent for a forward reference"}
   [:id Id]
   [:kind {:optional true} [:enum "issue" "page"]]
   [:title {:optional true} :string]
   [:status {:optional true :description "issues only"} :string]
   [:archived {:optional true :description "pages only"} :boolean]])

(def IssueRow
  [:map [:id Id] [:kind [:enum "issue"]] [:title :string] [:status :string] [:updated :string]])

(def PageRow
  [:map [:id Id] [:kind [:enum "page"]] [:title :string] [:parent {:optional true} Id] [:archived :boolean]
   [:rank :double] [:updated :string] [:revision Version]])

(def ops
  {:create-issue
   {:args [:map {:closed true}
           [:title Title]
           [:project {:optional true :description "defaults to the binding"} Key]
           [:body {:optional true :description "markdown, stored verbatim"} :string]]
    :result IssueRow}

   :get-issue
   {:args [:map {:closed true} [:id Id]]
    :result (mu/merge IssueRow [:map [:body :string] [:mentions [:vector Ref]] [:backlinks [:vector Ref]]])}

   :create-page
   {:args [:map {:closed true}
           [:title Title]
           [:project {:optional true :description "defaults to the binding"} Key]
           [:body {:optional true :description "markdown, stored verbatim"} :string]
           [:parent {:optional true :description "parent page id; absent makes a root"} Id]
           [:summary {:optional true :description "edit summary"} :string]]
    :result (mu/merge PageRow [:map [:version Version]])}

   :get-page
   {:args [:map {:closed true} [:id Id] [:revision {:optional true :description "an old revision; default latest"} Version]]
    :result (mu/merge PageRow [:map [:body :string] [:version Version] [:children [:vector PageRow]]
                                [:mentions [:vector Ref]] [:backlinks [:vector Ref]]])}

   :update-page
   {:args [:map {:closed true}
           [:id Id]
           [:expected-version {:optional true :description "the version you read; refused with stale_version if it moved"} Version]
           [:title {:optional true} Title]
           [:body {:optional true :description "the whole new body, verbatim"} :string]
           [:summary {:optional true :description "edit summary"} :string]]
    :result (mu/merge PageRow [:map [:version Version]])}

   :search
   {:args [:map {:closed true}
           [:q {:optional true :description "query: kind:issue|page and text:<substring> in this skeleton"} :string]
           [:project {:optional true} Key]]
    :result [:map [:effective-query :string] [:results [:vector [:or IssueRow PageRow]]]
             [:next-cursor {:optional true} :string] [:has-more :boolean]]}})

(defn validate! [op args]
  (let [schema (get-in ops [op :args])]
    (when-not (m/validate schema args)
      (throw (ex-info (str "invalid arguments: " (pr-str (me/humanize (m/explain schema args))))
                      {:error "invalid_argument" :status 400
                       :details (me/humanize (m/explain schema args))})))
    args))

;; ---------------------------------------------------------------- the wire

(defn snake [k] (str/replace (name k) "-" "_"))
(defn kebab [s] (keyword (str/replace s "_" "-")))

(defn- snake-props [js]
  (walk/postwalk
   (fn [x]
     (if (and (map? x) (map? (:properties x)))
       (cond-> (update x :properties #(update-keys % (comp keyword snake)))
         (:required x) (update :required #(mapv snake %)))
       x))
   js))

(defn json-schema [schema] (snake-props (json-schema/transform schema)))
