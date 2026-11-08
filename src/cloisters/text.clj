(ns cloisters.text
  "The compact text rendering every surface shares: MCP's text block, and what HTTP returns for Accept: text/plain,
  which is what the CLI prints. One line per row; bodies verbatim, never decorated, so body_edits can quote them."
  (:require [cloisters.diff :as diff]
            [clojure.string :as str]))

;; ---------------------------------------------------------------- pieces

(defn- when-str
  "The time part of an ISO timestamp a reader needs: date, hours and minutes, in UTC."
  [t]
  (when t (str/replace (subs t 0 (min 16 (count t))) "T" " ")))

(defn- indent [prefix s]
  (str/join "\n" (map #(if (str/blank? %) "" (str prefix %)) (str/split-lines s))))

(defn- section
  "A titled list, or nil when xs is empty."
  [title render xs]
  (when (seq xs)
    (str title ":\n" (str/join "\n" (map #(indent "  " (render %)) xs)))))

(defn- blocks
  "Non-blank parts joined by blank lines."
  [& parts]
  (str/join "\n\n" (remove str/blank? (flatten parts))))

(defn- issue-state
  "open · claimed by saqib/wf-1 · blocked, or closed done · saqib."
  [{:keys [status resolution assignee blocked]}]
  (str/join " · " (remove nil? [(if (= "closed" status) (str "closed " resolution) "open")
                                (when assignee (if (= "closed" status) assignee (str "claimed by " assignee)))
                                (when (and blocked (not= "closed" status)) "blocked")])))

(defn row
  "One line for an issue or page row: CLO-42  open · blocked  Title  [label], or CLO-57  page  Glossary."
  [{:keys [id kind title labels] :as r}]
  (if (= "page" kind)
    (str id "  " (if (:archived r) "archived" "page") "  " title)
    (str id "  " (issue-state r) "  " title (when (seq labels) (str "  [" (str/join ", " labels) "]")))))

(defn ref-line
  "One line for an item an edge or mention names: its status if an issue, page or archived if a page."
  [{:keys [id kind title status archived]}]
  (str id "  " (if (= "page" kind) (if archived "archived" "page") status) "  " title))

(defn- version-line [{:keys [kind version]}]
  (if (= "page" kind) (str "version r" version) (str "version " version)))

;; ---------------------------------------------------------------- events

(defn- change-line [{:keys [field op old new] :as c}]
  (let [v #(if (nil? %) "none" %)]
    (cond
      (= "body" field) (let [d (or (:diff c) (diff/unified old new "before" "after"))]
                         (str "body:\n" (indent "  " (if (str/blank? d) "(whitespace only)" d))))
      (= "title" field) (if old (str "title: " (pr-str old) " → " (pr-str (v new))) (str "title: " (pr-str new)))
      (= "add" op) (str "+" (when-not (= "labels" field) (str field " ")) new)
      (= "remove" op) (str "-" (when-not (= "labels" field) (str field " ")) old)
      :else (str field ": " (v old) " → " (v new)))))

(defn event
  "An event: a header line (#1042 2026-09-15 10:02 mcp:claude-code CLO-7), then its changes, summary and comment."
  [{:keys [seq id at actor changes comment summary revision restored-from]}]
  (str/join "\n" (concat [(str "#" seq " " (when-str at) " " actor " " id
                               (when revision (str " r" revision))
                               (when restored-from (str " (restored from r" restored-from ")")))]
                         (map #(indent "  " (change-line %)) changes)
                         (when summary [(str "  summary: " summary)])
                         (when comment [(indent "  " (str "comment: " comment))]))))

(defn- revision-line [{:keys [revision at actor summary restored-from]}]
  (str "r" revision "  " (when-str at) "  " actor
       (when restored-from (str "  restored from r" restored-from))
       (when summary (str "  " summary))))

;; ---------------------------------------------------------------- results by operation

(defmulti render
  "The text for an operation's result."
  (fn [op _] op))

(defmethod render :default [_ r]
  (if (:kind r)
    (str (row r) (when (:version r) (str "\n" (version-line r))))
    (pr-str r)))

(defmethod render :search [_ {:keys [effective-query results has-more next-cursor]}]
  (str "query: " effective-query "\n"
       (if (seq results) (str/join "\n" (map row results)) "(no results)")
       (when has-more (str "\nmore: cursor " next-cursor))))

(defmethod render :claim-issue [_ r]
  (str (row r) "\n" (version-line r) " · claimed " (when-str (:claimed-at r))))

(defmethod render :close-issue [_ {:keys [newly-unblocked] :as r}]
  (blocks (str (row r) "\n" (version-line r))
          (section "newly unblocked" row newly-unblocked)))

(defmethod render :get-issue [_ {:keys [body parent] :as r}]
  (blocks (str (row (assoc r :parent (:id parent))) "\n"
               (version-line r) " · created " (when-str (:created r)) " · updated " (when-str (:updated r))
               (when (:closed r) (str " · closed " (when-str (:closed r))))
               (when parent (str "\nparent: " (ref-line parent))))
          (if (str/blank? body) "(no body)" body)
          (section "children" ref-line (:children r))
          (section "blocked by" ref-line (:blocked-by r))
          (section "blocking" ref-line (:blocking r))
          (section "mentions" ref-line (:mentions r))
          (section "backlinks" ref-line (:backlinks r))
          (section "comments" #(str "#" (:seq %) " " (when-str (:at %)) " " (:actor %) "\n" (indent "  " (:comment %)))
                   (:comments r))
          (when (:events r)
            (str "events (" (count (:events r)) " of " (:events-total r) ", oldest first):\n"
                 (str/join "\n" (map #(indent "  " (event %)) (:events r)))))))

(defmethod render :get-page [_ {:keys [body parent version revision] :as r}]
  (blocks (str (row r) "\n"
               (if (= revision version)
                 (str "version r" version)
                 (str "revision r" revision " of r" version " (an earlier revision)"))
               " · created " (when-str (:created r)) " · updated " (when-str (:updated r))
               (when parent (str "\nparent: " (ref-line parent))))
          (if (str/blank? body) "(no body)" body)
          (section "children" row (:children r))
          (section "mentions" ref-line (:mentions r))
          (section "backlinks" ref-line (:backlinks r))
          (when (:revisions r)
            (str "revisions (" (count (:revisions r)) " of " (:revisions-total r) ", oldest first):\n"
                 (str/join "\n" (map #(str "  " (revision-line %)) (:revisions r)))))))

(defmethod render :diff-page [_ {:keys [id from to title-before title-after diff]}]
  (blocks (str id " r" from " → r" to)
          (when (not= title-before title-after)
            (str "title: " (pr-str (or title-before "none")) " → " (pr-str title-after)))
          (if (str/blank? diff) "(bodies match)" diff)))

(defmethod render :page-revisions [_ revisions]
  (str/join "\n" (map revision-line revisions)))

(defn- events-page [{:keys [events has-more next-cursor]}]
  (str (if (seq events) (str/join "\n" (map event events)) "(no events)")
       (when has-more (str "\nmore: after " next-cursor))))

(defmethod render :events [_ r] (events-page r))
(defmethod render :issue-events [_ r] (events-page r))

(defmethod render :list-projects [_ projects]
  (if (seq projects)
    (str/join "\n" (map #(str (:key %) "  " (:name %)) projects))
    "(no projects)"))

(defmethod render :create-project [_ {:keys [key name]}]
  (str key "  " name))

(defmethod render :meta [_ {:keys [version data-dir]}]
  (str "cloisters daemon " version "\ndata " data-dir))

(defn error
  "A refusal as text: led by its code, then the message written for the reader."
  [code message]
  (str code ": " message))
