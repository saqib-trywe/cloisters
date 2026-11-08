(ns cloisters.contract
  "The daemon's contract: one malli schema per operation's arguments. The core validates every call against these, so
  MCP, HTTP, the UI and the REPL all meet the same invalid_argument refusal. Keys are kebab-case here and snake_case
  on the wire."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]))

(def Key [:re {:description "a project key, e.g. CLO"} #"^[A-Z][A-Z0-9]{1,9}$"])
(def Id [:re {:description "an issue or page id, e.g. CLO-7"} #"^[A-Z][A-Z0-9]{1,9}-[1-9][0-9]{0,17}$"])
(def Line [:re {:description "one line, not blank"} #"^[^\r\n]*\S[^\r\n]*$"])
(def Text [:re {:description "not blank"} #"^[\s\S]*\S[\s\S]*$"])
(def Version [:int {:min 1}])
(def Rank [:double {:description "any decimal number; siblings sort by rank, then number"}])
(def Limit [:int {:min 1 :max 200}])

(def ^:private comment-arg
  [:comment {:optional true :description "a comment appended in the same write"} Text])

(def ^:private summary-arg
  [:summary {:optional true :description "an edit summary: why the page changed"} Text])

(def ^:private rank-args
  [[:rank {:optional true} Rank]
   [:rank-before {:optional true :description "place just before this id"} Id]
   [:rank-after {:optional true :description "place just after this id"} Id]])

(def ^:private BodyEdits
  [:vector {:description "exact replacements applied in order, all or nothing; each old must match exactly once"}
   [:map {:closed true} [:old [:string {:min 1}]] [:new :string]]])

(defn- args [& entries]
  (into [:map {:closed true}] entries))

(def ops
  "Each operation's argument schema, and the arguments of which at most one may be given."
  {:create-project
   {:args (args [:key Key] [:name Line])}

   :list-projects
   {:args (args)}

   :create-issue
   {:args (apply args
                 [:title Line]
                 [:project {:optional true :description "defaults to the parent's project, then the binding"} Key]
                 [:body {:optional true :description "markdown, stored verbatim"} :string]
                 [:labels {:optional true} [:vector Line]]
                 [:parent {:optional true} Id]
                 [:blocked-by {:optional true} [:vector Id]]
                 comment-arg
                 rank-args)
    :exclusive [[:rank :rank-before :rank-after]]}

   :get-issue
   {:args (args [:id Id] [:include {:optional true} [:vector [:enum "events"]]])}

   :update-issue
   {:args (apply args
                 [:id Id]
                 [:expected-version {:optional true :description "the version you read; refused if it moved"} Version]
                 [:title {:optional true} Line]
                 [:body {:optional true :description "the whole new body, verbatim"} :string]
                 [:body-edits {:optional true} BodyEdits]
                 [:labels-add {:optional true} [:vector Line]]
                 [:labels-remove {:optional true} [:vector Line]]
                 [:parent {:optional true :description "null clears it"} [:maybe Id]]
                 [:blocked-by-add {:optional true} [:vector Id]]
                 [:blocked-by-remove {:optional true} [:vector Id]]
                 comment-arg
                 rank-args)
    :exclusive [[:body :body-edits] [:rank :rank-before :rank-after]]}

   :claim-issue
   {:args (args [:id Id] [:assignee {:description "a name unique to your session, e.g. saqib/wf-7f3a"} Line] comment-arg)}

   :release-issue
   {:args (args [:id Id] [:assignee Line] comment-arg)}

   :reassign-issue
   {:args (args [:id Id]
                [:from {:description "the holder you expect to displace, or none"} Line]
                [:to {:description "the new holder, or none"} Line]
                comment-arg)}

   :close-issue
   {:args (args [:id Id] [:resolution [:enum "done" "dropped"]] [:comment Text])}

   :reopen-issue
   {:args (args [:id Id] [:comment Text])}

   :issue-events
   {:args (args [:id Id] [:after {:optional true} [:int {:min 0}]] [:limit {:optional true} Limit])}

   :events
   {:args (args [:after {:optional true} [:int {:min 0}]] [:limit {:optional true} Limit])}

   :create-page
   {:args (apply args
                 [:title Line]
                 [:project {:optional true :description "defaults to the parent's project, then the binding"} Key]
                 [:body {:optional true :description "markdown, stored verbatim"} :string]
                 [:parent {:optional true :description "the parent page; absent makes a root"} Id]
                 summary-arg
                 rank-args)
    :exclusive [[:rank :rank-before :rank-after]]}

   :get-page
   {:args (args [:id Id]
                [:revision {:optional true :description "an earlier revision's title and body"} Version]
                [:include {:optional true} [:vector [:enum "revisions"]]])}

   :update-page
   {:args (apply args
                 [:id Id]
                 [:expected-version {:optional true :description "the version you read; refused if it moved"} Version]
                 [:title {:optional true} Line]
                 [:body {:optional true :description "the whole new body, verbatim"} :string]
                 [:body-edits {:optional true} BodyEdits]
                 [:parent {:optional true :description "the new parent page; null makes a root"} [:maybe Id]]
                 summary-arg
                 rank-args)
    :exclusive [[:body :body-edits] [:rank :rank-before :rank-after]]}

   :archive-page
   {:args (args [:id Id] [:summary {:description "why the page is archived"} Text])}

   :unarchive-page
   {:args (args [:id Id] [:summary {:description "why the page is back"} Text])}

   :restore-page
   {:args (args [:id Id]
                [:revision Version]
                [:expected-version {:optional true} Version]
                summary-arg)}

   :page-revisions
   {:args (args [:id Id])}

   :diff-page
   {:args (args [:id Id]
                [:from {:optional true :description "default: the revision before to"} Version]
                [:to {:optional true :description "default: the latest"} Version])}})

(defn wire-name
  "An argument's name as clients write it: snake_case."
  [k]
  (str/replace (name k) "-" "_"))

(defn- invalid [argument reason]
  (throw (ex-info (str "invalid argument " argument ": " reason)
                  {:error "invalid_argument" :argument argument :reason reason})))

(defn validate!
  "Returns args when they satisfy op's schema, with ranks as doubles. Otherwise throws invalid_argument, naming the
  first offending argument and why."
  [op args]
  (let [{:keys [exclusive] schema :args} (ops op)
        args (cond-> (or args {})
               (number? (:rank args)) (update :rank double))]
    (when-let [error (first (:errors (m/explain schema args)))]
      (let [path (:in error)
            argument (if (seq path) (wire-name (first (filter keyword? path))) "arguments")]
        (invalid argument (if (= ::m/extra-key (:type error))
                            "is not an argument of this operation"
                            (me/error-message error)))))
    (doseq [group exclusive]
      (let [given (filter #(contains? args %) group)]
        (when (next given)
          (invalid (wire-name (second given))
                   (str "give at most one of " (str/join ", " (map wire-name group)))))))
    args))
