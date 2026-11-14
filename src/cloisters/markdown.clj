(ns cloisters.markdown
  "One markdown parser for every surface: nextjournal/markdown's data tree. Mention detection walks it here, and the
  UI renders the same tree here, so the UI never links what the daemon didn't count.

  A mention is the token KEY-N with no letter, digit, _ or - on either side, outside code, or a link whose path is
  exactly /KEY-N, whatever the host.

  The renderer is our own, data tree in and hiccup out: raw HTML shows as text, and only http, https, mailto and
  site-relative URLs become links or images (nextjournal would pass javascript: through)."
  (:require [clojure.string :as str]
            [nextjournal.markdown :as md]))

(def ^:private token
  #"(?<![\p{L}\p{N}_-])([A-Z][A-Z0-9]{1,9})-([1-9][0-9]{0,17})(?![\p{L}\p{N}_-])")

(def ^:private url-path
  ;; optional scheme and authority, then the path, then an optional query or fragment
  #"(?:[A-Za-z][A-Za-z0-9+.-]*:)?(?://[^/?#]*)?([^?#]*)(?:[?#].*)?")

(def ^:private id-path #"/([A-Z][A-Z0-9]{1,9})-([1-9][0-9]{0,17})")

(defn- tokens [s]
  (for [[_ k n] (re-seq token (or s ""))] [k (parse-long n)]))

(defn- link-target [href]
  (when-let [[_ k n] (some->> href (re-matches url-path) second (re-matches id-path))]
    [[k (parse-long n)]]))

(defn- walk [node]
  (case (:type node)
    ;; code never counts; raw HTML and image alt text are not prose the UI links
    (:monospace :code :formula :block-formula :html-inline :html-block :image) nil
    :text (tokens (:text node))
    :link (concat (link-target (get-in node [:attrs :href])) (mapcat walk (:content node)))
    (mapcat walk (:content node))))

(defn mentions
  "The ids [key number] a markdown text mentions, in order of first appearance."
  [markdown]
  (let [doc (md/parse (or markdown ""))]
    (distinct (concat (walk doc) (mapcat walk (:footnotes doc))))))

(defn title-mentions
  "The ids [key number] a title mentions. Titles are plain text: every token counts."
  [title]
  (distinct (tokens title)))

;; ---------------------------------------------------------------- rendering

(defn safe-url?
  "Whether a link or image URL may render: http, https and mailto, or a site-relative path. Anything else, such as
  javascript: or data:, renders as its text alone."
  [url]
  (let [u (str/trim (str url))]
    (boolean (or (re-find #"^(?i)(https?|mailto):" u)
                 (and (str/starts-with? u "/") (not (str/starts-with? u "//")) (not (str/starts-with? u "/\\")))))))

(defn ref-title
  "The hover text for a link to ref: its title, then its status or archived."
  [{:keys [kind title status archived]}]
  (str title (cond (= "issue" kind) (str " (" status ")") archived " (archived)")))

(defn ref-class
  "The classes a link to ref takes: a closed issue is struck through, an archived page muted."
  [{:keys [kind status archived]}]
  (cond (and (= "issue" kind) (= "closed" status)) "ref closed"
        archived "ref archived"
        :else "ref"))

(defn mention
  "An id as the UI shows it: a link titled on hover, marked when archived; muted when nothing has it yet."
  [id info]
  (if (map? info)
    [:a {:href (str "/" id) :class (ref-class info) :title (ref-title info)}
     id (when (:archived info) [:span.flag " archived"])]
    [:span.missing {:title (str id " doesn't exist yet")} id]))

(defn- linked-text
  "A text node with each mention linked. refer says how an id renders: a ref, :missing, or nil for a token that is
  not a mention (its key names no project)."
  [refer s]
  (let [m (re-matcher token s)]
    (loop [at 0 out []]
      (if (.find m)
        (let [k (.group m 1) n (parse-long (.group m 2))
              info (refer [k n])]
          (recur (.end m) (cond-> (conj out (subs s at (.start m)))
                            info (conj (mention (str k "-" n) info))
                            (not info) (conj (.group m 0)))))
        (filterv not-empty (conj out (subs s at)))))))

(defn- plain-text [node]
  (if (= :text (:type node)) (:text node) (apply str (map plain-text (:content node)))))

(declare nodes)

(defn- align [node]
  (when-let [a (:alignment node)] {:style (str "text-align:" (name a))}))

(defn- node
  "The hiccup for one node, as a sequence. in-link stops mentions inside a link becoming links of their own."
  [{:keys [refer in-link] :as env} {:keys [type attrs] :as n}]
  (let [kids #(nodes env (:content n))
        el (fn [tag & [attrs]] [(into (if attrs [tag attrs] [tag]) (kids))])]
    (case type
      :doc (kids)
      :text (if in-link [(:text n)] (linked-text refer (:text n)))
      :paragraph (el :p)
      :plain (kids)
      :heading (el (keyword (str "h" (min 6 (max 1 (:heading-level n))))))
      :em (el :em)
      :strong (el :strong)
      :strikethrough (el :del)
      :monospace [[:code (plain-text n)]]
      :code [[:pre [:code (when-let [l (:language n)] {:class (str "language-" l)}) (plain-text n)]]]
      (:formula :inline-formula) [[:code (plain-text n)]]
      :block-formula [[:pre [:code (plain-text n)]]]
      :blockquote (el :blockquote)
      :bullet-list (el :ul)
      :numbered-list (el :ol (when-let [s (:start attrs)] (when (not= 1 s) {:start s})))
      :todo-list (el :ul {:class "todo"})
      :list-item (el :li)
      :todo-item [(into [:li [:input {:type "checkbox" :disabled true :checked (boolean (:checked attrs))}] " "]
                        (kids))]
      :table (el :table)
      :table-head (el :thead)
      :table-body (el :tbody)
      :table-row (el :tr)
      :table-header (el :th (align n))
      :table-data (el :td (align n))
      :ruler [[:hr]]
      :softbreak ["\n"]
      :hardbreak [[:br]]
      :footnote-ref [[:sup [:a {:href (str "#fn-" (:ref n)) :id (str "fnref-" (:ref n))} (:label n)]]]
      :link (let [{:keys [href title]} attrs
                  [target] (link-target href)
                  info (some-> target refer)]
              (if (safe-url? href)
                [(into [:a (cond-> {:href href}
                             title (assoc :title title)
                             (map? info) (assoc :class (ref-class info) :title (ref-title info)))]
                       (nodes (assoc env :in-link true) (:content n)))]
                (kids)))
      :image (let [{:keys [src title]} attrs]
               (if (safe-url? src)
                 [[:img (cond-> {:src src :alt (plain-text n)} title (assoc :title title))]]
                 [(plain-text n)]))
      ;; raw HTML shows as the text it is, never as markup
      :html-inline [(plain-text n)]
      :html-block [[:p.html (plain-text n)]]
      (kids))))

(defn- nodes [env ns]
  (mapcat #(node env %) ns))

(defn render
  "Hiccup for a markdown text: a div.prose. refer maps an id [key number] to how it renders: the ref (as an edge or
  mention list carries it) when the item exists, :missing when its project exists but the item doesn't yet, or nil
  when the token is not a mention."
  [markdown refer]
  (let [doc (md/parse (or markdown ""))
        env {:refer refer}
        notes (:footnotes doc)]
    (cond-> (into [:div.prose] (nodes env (:content doc)))
      (seq notes)
      (conj [:section.footnotes
             (into [:ol] (for [f notes]
                           (into [:li {:id (str "fn-" (:ref f))}]
                                 (concat (nodes env (:content f))
                                         [" " [:a {:href (str "#fnref-" (:ref f))} "↩"]]))))]))))
