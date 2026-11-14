(ns cloisters.ui
  "The web UI: a third thin adapter over the core, beside HTTP and MCP. Handlers call the core and answer
  server-rendered hiccup, whole pages or htmx fragments; they never call the JSON API. Writes record `web`.

  Every issue and page lives at /KEY-N, which routes by what the id names. The query lives only in the URL and the
  search box. Live updates come from the API's one SSE stream: assets/live.js turns each event into a
  `cloisters:change` htmx trigger on the elements watching an id it names, which re-fetch their fragment by ordinary
  GET. Lists never reshuffle under the reader: they count updates in a bar instead.

  Inline edits post with htmx and answer the refreshed item, a refusal shown in it with its status. Navigating writes
  (saving the editor, a new page, restoring a revision) are plain form posts answered by a redirect; a stale save
  answers 409 with the editor, the human's text kept and what changed underneath shown, for a hand merge."
  (:require [cloisters.core :as core]
            [cloisters.diff :as diff]
            [cloisters.issues :as issues]
            [cloisters.markdown :as markdown]
            [cloisters.pages :as pages]
            [cloisters.projects :as projects]
            [cloisters.search :as search]
            [clojure.string :as str]
            [hiccup2.core :as h])
  (:import (clojure.lang ExceptionInfo)
           (java.net URLDecoder URLEncoder)
           (java.nio.charset StandardCharsets)))

;; ---------------------------------------------------------------- responses

(defn- enc [s] (URLEncoder/encode (str s) StandardCharsets/UTF_8))

(defn- html-str [hiccup] (str (h/html {:mode :html} hiccup)))

(defn- respond
  "An HTML response: a fragment's hiccup, or a whole page's string."
  [status body & {:as headers}]
  {:status status
   :headers (merge {"Content-Type" "text/html; charset=utf-8" "Cache-Control" "no-store"} headers)
   :body (if (string? body) body (html-str body))})

(defn- see-other [location] {:status 303 :headers {"Location" location} :body ""})

(def ^:private nav
  [["Ready" "/?q=ready"]
   ["Recent changes" (str "/?q=" (enc "kind:page sort:updated-desc"))]
   ["Projects" "/projects"]])

(defn- layout
  "A whole page: the header with the search box, which holds q, then body."
  [{:keys [title q]} & body]
  (str "<!doctype html>"
       (html-str
        [:html {:lang "en"}
         [:head
          [:meta {:charset "utf-8"}]
          [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
          [:title (str title " · cloisters")]
          [:link {:rel "stylesheet" :href "/assets/app.css"}]
          [:script {:src "/assets/htmx.min.js"}]
          [:script {:src "/assets/live.js" :defer true}]]
         [:body
          [:header.top
           [:a.brand {:href "/"} "cloisters"]
           [:form.search {:method "get" :action "/"}
            [:input {:type "search" :name "q" :value q :aria-label "Query" :placeholder "ready project:CLO label:bug"}]]
           (into [:nav] (for [[label href] nav] [:a {:href href} label]))]
          (into [:main] body)]])))

(defn- notice
  "A refusal as the reader sees it: its code, then its message."
  [{:keys [code message]}]
  (when code
    [:p.notice {:role "alert"} [:strong code] " " message]))

(defn- refusal-of
  "The contract refusal e carries, as {:code :message :facts}, or nil when it is not one."
  [^ExceptionInfo e]
  (when-let [code (:error (ex-data e))]
    {:code code :message (ex-message e) :facts (ex-data e)}))

(defmacro ^:private refusing
  "Runs body; a contract refusal comes back as {:refusal r} instead of throwing."
  [& body]
  `(try (do ~@body)
        (catch ExceptionInfo e#
          (if-let [r# (refusal-of e#)] {:refusal r#} (throw e#)))))

;; ---------------------------------------------------------------- shared pieces

(defn- time-el
  "A stored time, shown to the minute in UTC, the full stamp on hover."
  [t]
  (when t [:time {:datetime t :title t} (str/replace (subs t 0 (min 16 (count t))) "T" " ")]))

(defn- issue-state [{:keys [status resolution assignee blocked]}]
  (cond (= "closed" status) (str "closed" (when resolution (str " " resolution)))
        assignee "claimed"
        blocked "blocked"
        :else "open"))

(defn- state
  "What a row or ref shows of its state: an issue's open, claimed, blocked or closed, a page's revision or archived."
  [{:keys [kind archived revision] :as r}]
  (if (= "issue" kind)
    (issue-state r)
    (if archived "archived" (str "page" (when revision (str " r" revision))))))

(defn- state-class [r]
  (str "state-" (first (str/split (state r) #" "))))

(defn- id-link
  "An id linked to its item, titled on hover and struck through or muted as mentions are."
  [{:keys [id] :as r}]
  (markdown/mention id r))

(defn- labels-el [labels]
  (when (seq labels) (into [:span.labels] (for [l labels] [:span.label l]))))

(defn- ref-list
  "An edge or mention list of refs, or nothing when empty."
  [heading refs]
  (when (seq refs)
    [:section [:h2 heading]
     (into [:ul.refs] (for [r refs] [:li (id-link r) " " [:span.state (state r)] " " (:title r)]))]))

(defn- refer-for
  "How the mentions in texts render: each id whose project exists, mapped to its ref, or :missing when nothing has it
  yet. Ids in other tokens map to nil and stay plain text."
  [ctx texts]
  (core/with-read
    ctx
    (fn [conn]
      (let [keys (set (core/project-keys conn))]
        (into {} (for [[k :as ref] (distinct (mapcat markdown/mentions texts))
                       :when (keys k)]
                   [ref (or (core/ref-info conn ref) :missing)]))))))

(defn- prose [ctx text]
  (markdown/render text (refer-for ctx [text])))

(defn- all-rows
  "Every row a query matches, following cursors."
  [ctx query]
  (loop [cursor nil out []]
    (let [{:keys [results next-cursor]} (search/search ctx (cond-> {:query query :limit 200} cursor (assoc :cursor cursor)))
          out (into out results)]
      (if next-cursor (recur next-cursor out) out))))

(defn- updates-bar
  "The bar a list shows instead of reshuffling: live.js counts events into it."
  [watch]
  [:p.updates {:data-count watch :hidden true}
   [:span.n "0"] " updates · " [:a {:href ""} "refresh"]])

(defn- hx-form
  "A form that posts to action with htmx, answering the refreshed item, and without htmx as a plain post."
  [id action & body]
  (into [:form {:method "post" :action (str "/" id "/" action)
                :hx-post (str "/" id "/" action) :hx-target "#item" :hx-swap "outerHTML"}]
        body))

;; ---------------------------------------------------------------- search

(defn- row-tr [r]
  [:tr {:class (state-class r)}
   [:td.id (id-link r)]
   [:td.state (state r)]
   [:td.title [:a {:href (str "/" (:id r))} (:title r)] " " (labels-el (:labels r))
    (when-let [a (:assignee r)] [:span.assignee a])]
   [:td.when (time-el (:updated r))]])

(defn- more-tr [q cursor]
  [:tr.more
   [:td {:colspan 4}
    [:button {:hx-get (str "/more?q=" (enc q) "&cursor=" (enc cursor)) :hx-target "closest tr" :hx-swap "outerHTML"}
     "Load more"]]])

(defn- rows-and-more [q {:keys [results next-cursor]}]
  (cond-> (mapv row-tr results)
    next-cursor (conj (more-tr q next-cursor))))

(defn- search-page
  "The search view, as [status page]: 400 for a query that doesn't parse."
  [ctx status-of q]
  (let [{:keys [refusal effective-query results] :as found} (refusing (search/search ctx {:query q}))]
    [(if refusal (status-of (:code refusal)) 200)
     (layout {:title (if (str/blank? q) "Everything" q) :q q}
             (notice refusal)
             (when-let [s (seq (get-in refusal [:facts :suggestions]))]
               (into [:p.suggest "Try: "] (interpose " " (for [x s] [:a {:href (str "/?q=" (enc x))} x]))))
             (when-not refusal
               (list
                [:p.query "Query: " [:code effective-query]]
                (updates-bar "*")
                (if (seq results)
                  [:table.rows (into [:tbody] (rows-and-more q found))]
                  [:p.empty "Nothing matches."]))))]))

(defn- cookie-query
  "The last query, from its cookie."
  [request]
  (some->> (get-in request [:headers "cookie"])
           (re-find #"(?:^|;\s*)q=([^;]*)")
           second
           (#(URLDecoder/decode ^String % StandardCharsets/UTF_8))))

(defn- home
  "/?q=… runs the query and remembers it; / alone goes to the last query, or ready on a first visit."
  [ctx status-of request]
  (if-let [q (get-in request [:query-params "q"])]
    (let [[status page] (search-page ctx status-of q)]
      (respond status page
               "Set-Cookie" (str "q=" (enc q) "; Path=/; SameSite=Strict; Max-Age=31536000")))
    (see-other (str "/?q=" (enc (or (cookie-query request) "ready"))))))

(defn- more [ctx request]
  (let [{:strs [q cursor]} (:query-params request)]
    (respond 200 (list* (rows-and-more q (search/search ctx {:query q :cursor cursor}))))))

;; ---------------------------------------------------------------- projects

(defn- projects-page [ctx]
  (let [ps (projects/list-projects ctx {})
        n #(count (all-rows ctx %))]
    (layout {:title "Projects"}
            [:h1 "Projects"]
            (if (empty? ps)
              [:p.empty "No projects yet. Create one with " [:code "cloisters project new"] "."]
              [:table.projects
               [:thead [:tr [:th "Project"] [:th "Open"] [:th "Ready"] [:th "Pages"] [:th "Trees"]]]
               (into [:tbody]
                     (for [{:keys [key name]} ps]
                       [:tr
                        [:td [:strong key] " " name]
                        [:td [:a {:href (str "/?q=" (enc (str "project:" key " status:open")))}
                              (n (str "project:" key " status:open"))]]
                        [:td [:a {:href (str "/?q=" (enc (str "project:" key " ready")))}
                              (n (str "project:" key " ready"))]]
                        [:td [:a {:href (str "/?q=" (enc (str "project:" key " kind:page")))}
                              (n (str "project:" key " kind:page"))]]
                        [:td [:a {:href (str "/tree/" key)} "issues"] " · "
                         [:a {:href (str "/tree/" key "/pages")} "pages"]]]))]))))

;; ---------------------------------------------------------------- trees

(defn- tree-ul
  "rows as a nested list under the ids in roots, each row's children in the order given."
  [rows roots]
  (let [by-parent (group-by :parent rows)
        node (fn node [r]
               (let [kids (by-parent (:id r))
                     line [:span.row {:class (state-class r)} (id-link r) " " [:span.state (state r)] " "
                           [:a {:href (str "/" (:id r))} (:title r)] " " (labels-el (:labels r))]]
                 (if (seq kids)
                   [:li [:details {:open true} [:summary line] (into [:ul] (map node kids))]]
                   [:li line])))]
    (into [:ul.tree] (map node roots))))

(defn- roots-of [rows]
  (let [ids (set (map :id rows))]
    (remove #(ids (:parent %)) rows)))

(defn- tree-page [ctx target archived?]
  (let [[_ key pages?] (re-matches #"([A-Z][A-Z0-9]{1,9})(/pages)?" target)
        archived (if archived? " archived:any" "")]
    (if key
      (let [kind (if pages? "page" "issue")
            _ (core/with-read ctx #(core/require-project % key))
            rows (all-rows ctx (str "kind:" kind " project:" key (when pages? archived)))]
        (layout {:title (str key (if pages? " pages" " issues"))}
                [:h1 key " " (if pages? "pages" "issues")]
                [:p.links
                 (if pages?
                   (list [:a {:href (str "/tree/" key)} "issue tree"] " · "
                         [:a {:href (str "/new?project=" key)} "new page"] " · "
                         (if archived?
                           [:a {:href (str "/tree/" key "/pages")} "hide archived"]
                           [:a {:href (str "/tree/" key "/pages?archived=yes")} "show archived"]))
                   [:a {:href (str "/tree/" key "/pages")} "page tree"])]
                (updates-bar "*")
                (if (seq rows) (tree-ul rows (roots-of rows)) [:p.empty "Nothing here yet."])))
      ;; a subtree: the item and everything under it
      (let [kind (core/with-read ctx #(core/kind-of % (core/parse-id target)))
            root (case kind
                   "issue" (issues/get-issue ctx {:id target})
                   "page" (pages/get-page ctx {:id target})
                   (core/fail "not_found" (str target " does not exist.") :id target))
            rows (all-rows ctx (str "under:" target (when (= "page" kind) archived)))
            root-row (assoc root :parent nil)]
        (layout {:title (str target " tree")}
                [:h1 "Under " (id-link root) " " (:title root)]
                (updates-bar "*")
                (tree-ul (cons root-row rows) [root-row]))))))

;; ---------------------------------------------------------------- issue detail

(defn- blockers-ul
  "An issue's blockers, each expanding in place to its own."
  [refs]
  (if (empty? refs)
    [:p.empty "No blockers."]
    (into [:ul.refs]
          (for [r refs]
            [:li
             [:details {:hx-get (str "/" (:id r) "/blockers") :hx-trigger "toggle once" :hx-target "find .sub"
                        :hx-swap "innerHTML"}
              [:summary (id-link r) " " [:span.state (state r)] " " (:title r)]
              [:div.sub "…"]]]))))

(defn- change-li [{:keys [field op old new diff]}]
  (cond diff [:li field [:pre.diff (for [l (str/split-lines diff)
                                         :when (not (re-find #"^(---|\+\+\+) " l))]
                                     [:span {:class (case (first l) \+ "ins" \- "del" \@ "hunk" nil)} l "\n"])]]
        (= "add" op) [:li "added " field " " [:code new]]
        (= "remove" op) [:li "removed " field " " [:code old]]
        :else [:li field ": " [:code (or old "none")] " → " [:code (or new "none")]]))

(defn- event-li [this {:keys [id at actor comment summary changes]}]
  [:li
   [:span.meta (time-el at) " " [:span.actor actor]
    (when (not= id this) (list " on " [:a {:href (str "/" id)} id]))]
   (when summary [:p.summary summary])
   (when (seq changes) (into [:ul.changes] (map change-li changes)))
   (when comment [:p.commented "commented"])])

(defn- issue-view [ctx {:keys [id title body labels assignee version created updated closed parent children
                               blocked-by blocking mentions backlinks comments events events-total
                               events-truncated] :as i}]
  [:div#view {:data-live id :hx-get (str "/" id "/view") :hx-trigger "cloisters:change" :hx-swap "outerHTML"}
   [:h1 [:span.id id] " " title]
   [:p.meta {:class (state-class i)}
    [:span.state (issue-state i)] (when assignee (list " · " [:span.assignee assignee]))
    " · v" version " · created " (time-el created) " · updated " (time-el updated)
    (when closed (list " · closed " (time-el closed)))
    (when parent (list " · under " (id-link parent)))
    " · " [:a {:href (str "/tree/" id)} "tree"]]
   (labels-el labels)
   (if (str/blank? body) [:p.empty "No description."] (prose ctx body))
   (ref-list "Children" children)
   (when (seq blocked-by) [:section [:h2 "Blocked by"] (blockers-ul blocked-by)])
   (ref-list "Blocks" blocking)
   (ref-list "Mentions" mentions)
   (ref-list "Backlinks" backlinks)
   (when (seq comments)
     [:section [:h2 "Comments"]
      (into [:ol.comments]
            (for [{:keys [at actor comment]} comments]
              [:li [:span.meta (time-el at) " " [:span.actor actor]] (prose ctx comment)]))])
   (when (seq events)
     [:section [:h2 "History"]
      (when events-truncated [:p.meta "The latest " (count events) " of " events-total "."])
      (into [:ol.events] (map #(event-li id %) (rseq events)))])])

(defn- issue-actions [{:keys [id status labels assignee]}]
  [:div#actions {:data-live id :data-guard true :hx-get (str "/" id "/actions") :hx-trigger "cloisters:change"
                 :hx-swap "outerHTML"}
   (hx-form id "comment"
            [:textarea {:name "comment" :rows 3 :placeholder "Comment (markdown)" :required true}]
            [:button "Comment"])
   [:div.labels-edit
    (for [l labels]
      (hx-form id "labels" [:input {:type "hidden" :name "remove" :value l}]
               [:button.chip {:title (str "remove " l)} l " ×"]))
    (hx-form id "labels" [:input {:name "add" :placeholder "label" :required true :size 12}] [:button "Add label"])]
   (if (= "open" status)
     (hx-form id "close"
              [:select {:name "resolution"} [:option {:value "done"} "done"] [:option {:value "dropped"} "dropped"]]
              [:input {:name "comment" :placeholder "Why (required)" :required true :size 40}]
              [:button "Close"])
     (hx-form id "reopen"
              [:input {:name "comment" :placeholder "Why (required)" :required true :size 40}]
              [:button "Reopen"]))
   (hx-form id "reassign"
            [:input {:type "hidden" :name "from" :value (or assignee "none")}]
            [:input {:name "to" :placeholder "nobody" :size 20}]
            [:button (if assignee "Reassign" "Assign")])
   [:div.rank
    (hx-form id "rank" [:input {:type "hidden" :name "dir" :value "up"}] [:button "Move up"])
    (hx-form id "rank" [:input {:type "hidden" :name "dir" :value "down"}] [:button "Move down"])]])

;; ---------------------------------------------------------------- page view

(defn- page-view [ctx {:keys [id title body archived version created updated parent children mentions backlinks]
                       :as p}]
  [:div#view {:data-live id :hx-get (str "/" id "/view") :hx-trigger "cloisters:change" :hx-swap "outerHTML"}
   [:h1 [:span.id id] " " title (when archived [:span.flag " archived"])]
   [:p.meta {:class (state-class p)}
    "r" version " · created " (time-el created) " · updated " (time-el updated)
    (when parent (list " · under " (id-link parent)))
    " · " [:a {:href (str "/" id "/edit")} "edit"]
    " · " [:a {:href (str "/" id "/history")} "history"]
    " · " [:a {:href (str "/tree/" id)} "tree"]
    " · " [:a {:href (str "/new?parent=" id)} "new child"]]
   (if (str/blank? body) [:p.empty "This page is empty."] (prose ctx body))
   (when (seq children)
     [:section [:h2 "Children"]
      (into [:ul.refs] (for [c children] [:li (id-link c) " " (:title c)
                                          (when (:archived c) [:span.flag " archived"])]))])
   (ref-list "Mentions" mentions)
   (ref-list "Backlinks" backlinks)])

(defn- page-actions [{:keys [id archived parent]}]
  [:div#actions {:data-live id :data-guard true :hx-get (str "/" id "/actions") :hx-trigger "cloisters:change"
                 :hx-swap "outerHTML"}
   (hx-form id "move"
            [:input {:name "parent" :value (:id parent) :placeholder "parent id, or blank for a root" :size 24}]
            [:button "Move"])
   [:div.rank
    (hx-form id "rank" [:input {:type "hidden" :name "dir" :value "up"}] [:button "Move up"])
    (hx-form id "rank" [:input {:type "hidden" :name "dir" :value "down"}] [:button "Move down"])]
   (hx-form id (if archived "unarchive" "archive")
            [:input {:name "summary" :placeholder "Why (required)" :required true :size 40}]
            [:button (if archived "Unarchive" "Archive")])])

;; ---------------------------------------------------------------- the item

(defn- kind-of [ctx id]
  (core/with-read ctx (fn [conn]
                        (or (core/kind-of conn (core/parse-id id))
                            (let [[k] (core/parse-id id)]
                              (core/require-project conn k)
                              (core/fail "not_found" (str id " doesn't exist yet.") :id id))))))

(defn- get-item [ctx id]
  (if (= "issue" (kind-of ctx id))
    (issues/get-issue ctx {:id id :include ["events"]})
    (pages/get-page ctx {:id id})))

(defn- view-of [ctx item]
  (if (= "issue" (:kind item)) (issue-view ctx item) (page-view ctx item)))

(defn- actions-of [item]
  (if (= "issue" (:kind item)) (issue-actions item) (page-actions item)))

(defn- item-el
  "The item's view and actions, with a refusal shown above them."
  [ctx item refusal]
  [:div#item (notice refusal) (view-of ctx item) (actions-of item)])

(defn- item-page [ctx id]
  (let [item (get-item ctx id)]
    (layout {:title (str id " " (:title item))} (item-el ctx item nil))))

;; ---------------------------------------------------------------- inline edits

(defn- siblings
  "The ids the item sorts among, itself included, in order: the same parent's children, or the project's roots."
  [ctx {:keys [id kind parent archived]}]
  (let [[k] (core/parse-id id)
        rows (if parent
               (all-rows ctx (str "parent:" (:id parent) (when archived " archived:any")))
               (filter #(nil? (:parent %))
                       (all-rows ctx (str "kind:" kind " project:" k (when archived " archived:any")))))]
    (mapv :id rows)))

(defn- rank-args
  "Moving the item up or down one place among its siblings, or nil when it is already at that end."
  [ctx item dir]
  (let [ids (siblings ctx item)
        i (.indexOf ^java.util.List ids (:id item))]
    (if (= "up" dir)
      (when (pos? i) {:rank-before (ids (dec i))})
      (when (< -1 i (dec (count ids))) {:rank-after (ids (inc i))}))))

(defn- blank->nil [s] (when-not (str/blank? s) s))

(defn- edit!
  "Runs action's write for the item at id with the form's params."
  [ctx id action params]
  (let [{:strs [comment add remove resolution from to dir parent summary]} params
        item #(get-item ctx id)]
    (case action
      "comment" (issues/update-issue ctx {:id id :comment comment})
      "labels" (issues/update-issue ctx (cond-> {:id id}
                                          (blank->nil add) (assoc :labels-add [(str/trim add)])
                                          (blank->nil remove) (assoc :labels-remove [remove])))
      "close" (issues/close-issue ctx {:id id :resolution resolution :comment comment})
      "reopen" (issues/reopen-issue ctx {:id id :comment comment})
      "reassign" (issues/reassign-issue ctx {:id id :from from :to (or (some-> to str/trim blank->nil) "none")})
      "rank" (let [it (item)]
               (when-let [args (rank-args ctx it dir)]
                 ((if (= "issue" (:kind it)) issues/update-issue pages/update-page) ctx (assoc args :id id))))
      "move" (pages/update-page ctx {:id id :parent (some-> parent str/trim blank->nil)})
      "archive" (pages/archive-page ctx {:id id :summary summary})
      "unarchive" (pages/unarchive-page ctx {:id id :summary summary})
      (core/fail "not_found" (str "No action " action ".") :id id))))

(def ^:private actions #{"comment" "labels" "close" "reopen" "reassign" "rank" "move" "archive" "unarchive"})

(defn- inline-edit
  "An inline edit: with htmx, the refreshed item, a refusal shown in it at its status; without, a redirect back to
  the item, or the page showing the refusal."
  [ctx status-of request id action]
  (let [{:keys [refusal]} (refusing (edit! ctx id action (:params request)) nil)
        status (if refusal (status-of (:code refusal)) 200)
        item (get-item ctx id)]
    (cond (get-in request [:headers "hx-request"]) (respond status (item-el ctx item refusal))
          refusal (respond status (layout {:title id} (item-el ctx item refusal)))
          :else (see-other (str "/" id)))))

;; ---------------------------------------------------------------- history, revisions and diff

(defn- history-page [ctx id]
  (let [p (pages/get-page ctx {:id id})
        revs (pages/page-revisions ctx {:id id})]
    (layout {:title (str id " history")}
            [:h1 "History of " (id-link p) " " (:title p)]
            [:form.compare {:method "get" :action (str "/" id "/diff")}
             "Compare "
             (into [:select {:name "from"}] (for [{:keys [revision]} revs]
                                              [:option {:value revision :selected (= revision (max 1 (dec (:version p))))}
                                               (str "r" revision)]))
             " with "
             (into [:select {:name "to"}] (for [{:keys [revision]} revs]
                                            [:option {:value revision :selected (= revision (:version p))}
                                             (str "r" revision)]))
             " " [:button "Diff"]]
            [:table.revisions
             (into [:tbody]
                   (for [{:keys [revision at actor summary restored-from]} (rseq revs)]
                     [:tr
                      [:td [:a {:href (str "/" id "/r" revision)} (str "r" revision)]]
                      [:td (time-el at)]
                      [:td.actor actor]
                      [:td (when restored-from [:span.restored (str "restored from r" restored-from " · ")])
                       (or summary [:span.empty "no summary"])]
                      [:td (when (> revision 1)
                             [:a {:href (str "/" id "/diff?from=" (dec revision) "&to=" revision)} "diff"])]]))])))

(defn- revision-page [ctx id revision]
  (let [current (pages/get-page ctx {:id id})
        {:keys [title body]} (pages/get-page ctx {:id id :revision revision})
        n (:version current)]
    (layout {:title (str id " r" revision)}
            [:p.banner "Revision r" revision " of r" n
             (when (> revision 1) (list " · " [:a {:href (str "/" id "/diff?from=" (dec revision) "&to=" revision)}
                                               "what changed"]))
             " · " [:a {:href (str "/" id "/history")} "history"]
             " · " [:a {:href (str "/" id)} "current"]]
            [:h1 [:span.id id] " " title]
            (prose ctx body)
            (when (not= revision n)
              [:form.restore {:method "post" :action (str "/" id "/restore")}
               [:input {:type "hidden" :name "revision" :value revision}]
               [:input {:type "hidden" :name "expected_version" :value n}]
               [:input {:name "summary" :placeholder "edit summary (optional)" :size 40}]
               [:button (str "Restore r" revision)]]))))

(defn- seg-el [tag [text changed]]
  (if changed [tag text] text))

(defn- diff-table
  "old against new side by side, changed words marked."
  [old new]
  (let [rows (diff/side-by-side old new)]
    (if (every? #(= :equal (:tag %)) rows)
      [:p.empty "The bodies match."]
      [:table.diff
       (into [:tbody]
             (for [{:keys [tag] a :old b :new} rows]
               [:tr {:class (name tag)}
                [:td.n (:line a)]
                [:td.old (when a (map #(seg-el :del %) (:segments a)))]
                [:td.n (:line b)]
                [:td.new (when b (map #(seg-el :ins %) (:segments b)))]]))])))

(defn- content
  "A page's title and body at revision, with nothing before r1."
  [ctx id revision]
  (if (pos? revision) (pages/get-page ctx {:id id :revision revision}) {:title nil :body ""}))

(defn- diff-body
  "The diff of a page between two revisions: the title before and after when it changed, then the bodies."
  [ctx id from to]
  (let [a (content ctx id from)
        b (content ctx id to)]
    (list
     (when (not= (:title a) (:title b))
       [:p.title-change "Title: " (if (:title a) [:del (:title a)] [:span.empty "none"]) " → " [:ins (:title b)]])
     (diff-table (:body a) (:body b)))))

(defn- diff-page [ctx id request]
  (let [num #(some-> (get-in request [:query-params %]) parse-long)
        {:keys [from to]} (pages/diff-page ctx (cond-> {:id id}
                                                 (num "from") (assoc :from (num "from"))
                                                 (num "to") (assoc :to (num "to"))))]
    (layout {:title (str id " r" from "→r" to)}
            [:h1 "Changes to " [:a {:href (str "/" id)} id] ": "
             (if (pos? from) [:a {:href (str "/" id "/r" from)} (str "r" from)] "nothing")
             " → " [:a {:href (str "/" id "/r" to)} (str "r" to)]]
            [:p.links [:a {:href (str "/" id "/history")} "history"]]
            (diff-body ctx id from to))))

(defn- restore [ctx request id]
  (let [{:strs [revision expected_version summary]} (:params request)
        {:keys [refusal]} (refusing (pages/restore-page ctx (cond-> {:id id :revision (parse-long (str revision))
                                                                     :expected-version (parse-long (str expected_version))}
                                                              (blank->nil summary) (assoc :summary summary)))
                                    nil)]
    (if refusal
      (respond (if (= "stale_version" (:code refusal)) 409 400)
               (layout {:title id} (notice refusal) [:p [:a {:href (str "/" id "/history")} "Back to the history"]]))
      (see-other (str "/" id)))))

;; ---------------------------------------------------------------- the editor

(defn- crlf->lf [s] (some-> s (str/replace "\r\n" "\n")))

(defn- editor
  "The page editor: title, body with a live preview, edit summary. version is what the save expects; stale, when the
  last save was refused, shows what changed underneath since loaded."
  [ctx {:keys [id action title body summary version hidden refusal stale]}]
  [:div.editor
   (when id
     [:div#banner {:data-live id :hx-get (str "/" id "/banner?version=" version) :hx-trigger "cloisters:change"
                   :hx-swap "innerHTML"}])
   (notice refusal)
   (when-let [{:keys [loaded]} stale]
     [:section.stale
      [:p [:strong "Not saved."] " " id " changed underneath you; it is now r" version
       ". Your text is kept below: merge by hand and save again."]
      [:h2 "What changed since r" loaded]
      (diff-body ctx id loaded version)])
   (into [:form {:method "post" :action action}]
         (concat
          (for [[k v] hidden :when v] [:input {:type "hidden" :name k :value v}])
          [(when version [:input {:type "hidden" :name "expected_version" :value version}])
           [:p [:input.title {:name "title" :value title :placeholder "Title" :required true :aria-label "Title"}]]
           [:div.split
            [:textarea#body {:name "body" :rows 24 :aria-label "Body"
                             :hx-post "/preview" :hx-trigger "input changed delay:300ms" :hx-target "#preview"
                             :hx-swap "innerHTML"}
             body]
            [:div#preview (prose ctx body)]]
           [:p [:input {:name "summary" :value summary :placeholder "Edit summary: why it changed (optional)"
                        :size 60 :aria-label "Edit summary"}]]
           [:p [:button "Save"] " " [:a {:href (if id (str "/" id) "/")} "Cancel"]]]))])

(defn- edit-page [ctx id]
  (let [{:keys [title body version]} (pages/get-page ctx {:id id})]
    (layout {:title (str "Edit " id)}
            [:h1 "Edit " [:a {:href (str "/" id)} id]]
            (editor ctx {:id id :action (str "/" id "/edit") :title title :body body :version version}))))

(defn- save [ctx request id]
  (let [{:strs [title body summary expected_version]} (:params request)
        loaded (parse-long (str expected_version))
        form {:title title :body (crlf->lf body) :summary summary}
        {:keys [refusal]} (refusing (pages/update-page ctx (cond-> {:id id :expected-version loaded :title title
                                                                    :body (crlf->lf body)}
                                                             (blank->nil summary) (assoc :summary summary)))
                                    nil)]
    (cond
      (nil? refusal) (see-other (str "/" id))
      (= "stale_version" (:code refusal))
      (let [current (get-in refusal [:facts :version])]
        (respond 409 (layout {:title (str "Edit " id)}
                             [:h1 "Edit " [:a {:href (str "/" id)} id]]
                             (editor ctx (assoc form :id id :action (str "/" id "/edit") :version current
                                                :stale {:loaded loaded})))))
      :else
      (respond 400 (layout {:title (str "Edit " id)}
                           [:h1 "Edit " [:a {:href (str "/" id)} id]]
                           (editor ctx (assoc form :id id :action (str "/" id "/edit") :version loaded
                                              :refusal refusal)))))))

(defn- banner
  "Above the editor: who changed the page since version, linking the diff, or nothing."
  [ctx id request]
  (let [loaded (some-> (get-in request [:query-params "version"]) parse-long)
        {:keys [version revisions]} (pages/get-page ctx {:id id :include ["revisions"]})
        by (->> revisions (filter #(> (:revision %) (or loaded 0))) (map :actor) distinct)]
    (respond 200 (if (and loaded (> version loaded))
                   [:p.banner "Changed since you opened it, by " (str/join ", " by) ": now r" version ". "
                    [:a {:href (str "/" id "/diff?from=" loaded "&to=" version) :target "_blank"} "See what changed"]]
                   ""))))

(defn- new-page-form [ctx request]
  (let [{:strs [project parent]} (:query-params request)
        parent-page (some->> (blank->nil parent) (#(pages/get-page ctx {:id %})))]
    (layout {:title "New page"}
            [:h1 "New page" (when parent-page (list " under " (id-link parent-page)))]
            (editor ctx {:action "/new" :hidden {"project" (when-not parent project) "parent" parent}}))))

(defn- create [ctx request]
  (let [{:strs [title body summary project parent]} (:params request)
        {:keys [refusal id]} (refusing (pages/create-page ctx (cond-> {:title title :body (crlf->lf body)}
                                                                (blank->nil project) (assoc :project project)
                                                                (blank->nil parent) (assoc :parent parent)
                                                                (blank->nil summary) (assoc :summary summary))))]
    (if refusal
      (respond 400 (layout {:title "New page"}
                           [:h1 "New page"]
                           (editor ctx {:action "/new" :title title :body (crlf->lf body) :summary summary
                                        :hidden {"project" project "parent" parent} :refusal refusal})))
      (see-other (str "/" id)))))

;; ---------------------------------------------------------------- routes

(def ^:private item-path #"/([A-Z][A-Z0-9]{1,9}-[1-9][0-9]{0,17})(?:/([a-z]+|r[1-9][0-9]{0,17}))?")

(defn- error-page [status-of {:keys [code] :as r}]
  (respond (status-of code) (layout {:title code} (notice r) [:p [:a {:href "/"} "Back to the search"]])))

(defn- route
  "The response for a UI request, or nil when the path is not the UI's."
  [ctx status-of {:keys [uri request-method] :as request}]
  (let [get? (= :get request-method)
        post? (= :post request-method)
        [_ id sub] (re-matches item-path uri)
        [_ tree] (re-matches #"/tree/(.+)" uri)]
    (cond
      (and get? (= "/" uri)) (home ctx status-of request)
      (and get? (= "/more" uri)) (more ctx request)
      (and get? (= "/projects" uri)) (respond 200 (projects-page ctx))
      (and post? (= "/preview" uri)) (respond 200 (prose ctx (crlf->lf (get-in request [:params "body"]))))
      (and get? (= "/new" uri)) (respond 200 (new-page-form ctx request))
      (and post? (= "/new" uri)) (create ctx request)
      (and get? tree) (respond 200 (tree-page ctx tree (= "yes" (get-in request [:query-params "archived"]))))
      (nil? id) nil
      (and get? (nil? sub)) (respond 200 (item-page ctx id))
      (and get? (= "view" sub)) (respond 200 (view-of ctx (get-item ctx id)))
      (and get? (= "actions" sub)) (respond 200 (actions-of (get-item ctx id)))
      (and get? (= "blockers" sub)) (respond 200 (blockers-ul (:blocked-by (issues/get-issue ctx {:id id}))))
      (and get? (= "history" sub)) (respond 200 (history-page ctx id))
      (and get? (str/starts-with? sub "r")) (respond 200 (revision-page ctx id (parse-long (subs sub 1))))
      (and get? (= "diff" sub)) (respond 200 (diff-page ctx id request))
      (and get? (= "edit" sub)) (respond 200 (edit-page ctx id))
      (and post? (= "edit" sub)) (save ctx request id)
      (and get? (= "banner" sub)) (banner ctx id request)
      (and post? (= "restore" sub)) (restore ctx request id)
      (and post? (actions sub)) (inline-edit ctx status-of request id sub)
      :else nil)))

(defn handler
  "The UI's Ring handler over ctx, answering nil for paths that are not the UI's. status-of maps an error code to its
  HTTP status, as the API answers it. A refusal on a whole page answers an HTML page naming it."
  [ctx status-of]
  (let [ctx (assoc ctx :actor "web" :project nil)]
    (fn [request]
      (try (route ctx status-of request)
           (catch ExceptionInfo e
             (if-let [r (refusal-of e)]
               (error-page status-of r)
               (throw e)))))))
