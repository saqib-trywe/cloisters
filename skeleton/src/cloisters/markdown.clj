(ns cloisters.markdown
  "PROTOTYPE. One markdown parser for mention detection and rendering: nextjournal/markdown's data tree in,
  our own safe hiccup out. Code never counts; a link counts when its path is exactly /KEY-N."
  (:require [clojure.string :as str]
            [nextjournal.markdown :as md]))

(def ^:private id-re #"(?<![A-Za-z0-9_-])([A-Z][A-Z0-9]{1,9})-([1-9][0-9]*)(?![A-Za-z0-9_-])")
(def ^:private link-re #"^/([A-Z][A-Z0-9]{1,9})-([1-9][0-9]*)$")

(defn- ids-in-text [s]
  (for [[_ k n] (re-seq id-re s)] [k (parse-long n)]))

(defn- walk-mentions [node]
  (case (:type node)
    (:monospace :code :html-inline :html-block) nil
    :text (ids-in-text (:text node))
    :link (concat (when-let [[_ k n] (re-matches link-re (str (get-in node [:attrs :href])))] [[k (parse-long n)]])
                  (mapcat walk-mentions (:content node)))
    (mapcat walk-mentions (:content node))))

(defn mentions
  "Ids [key number] named in a title and a markdown body, in order of first appearance."
  [title body]
  (distinct (concat (ids-in-text (or title "")) (walk-mentions (md/parse (or body ""))))))

;; ---------------------------------------------------------------- rendering

(defn- safe-url? [u]
  (let [u (str/trim (str u))]
    (or (re-find #"^(?i)(https?:|mailto:)" u)
        (and (str/starts-with? u "/") (not (str/starts-with? u "//"))))))

(defn- id-link [lookup k n]
  (let [{:keys [title archived kind]} (lookup [k n])
        id (str k "-" n)]
    (if kind
      [:a {:href (str "/" id) :title title :class (when archived "archived")}
       id (when archived " (archived)")]
      id)))

(defn- text->hiccup [lookup s]
  (let [m (re-matcher id-re s)]
    (loop [at 0 out []]
      (if (.find m)
        (recur (.end m) (conj out (subs s at (.start m)) (id-link lookup (.group m 1) (parse-long (.group m 2)))))
        (conj out (subs s at))))))

(declare node->hiccup)

(defn- children [lookup node] (mapcat #(node->hiccup lookup %) (:content node)))

(defn- plain-text [node]
  (if (= :text (:type node)) (:text node) (apply str (map plain-text (:content node)))))

(defn- node->hiccup [lookup node]
  (let [kids #(children lookup node)
        el (fn [tag] [(into [tag] (kids))])]
    (case (:type node)
      :doc (kids)
      :text (text->hiccup lookup (:text node))
      :paragraph (el :p)
      :plain (kids)
      :heading (el (keyword (str "h" (:heading-level node))))
      :em (el :em)
      :strong (el :strong)
      :strikethrough (el :del)
      :monospace [[:code (plain-text node)]]
      :code [[:pre [:code (plain-text node)]]]
      :blockquote (el :blockquote)
      :bullet-list (el :ul)
      :numbered-list (el :ol)
      :list-item (el :li)
      :ruler [[:hr]]
      :softbreak ["\n"]
      :hardbreak [[:br]]
      :link (let [href (get-in node [:attrs :href])]
              (if (safe-url? href) [(into [:a {:href href}] (kids))] (kids)))
      :image (let [src (get-in node [:attrs :src])]
               (if (safe-url? src) [[:img {:src src :alt (plain-text node)}]] [(plain-text node)]))
      ;; raw HTML is escaped as text, never passed through
      (:html-inline :html-block) [(plain-text node)]
      (kids))))

(defn render
  "Hiccup for a markdown body. lookup maps [key number] to {:kind :title :archived} for ids that exist."
  [body lookup]
  (into [:div.prose] (node->hiccup lookup (md/parse (or body "")))))
