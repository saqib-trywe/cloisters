(ns cloisters.markdown
  "One markdown parser for every surface: nextjournal/markdown's data tree. Mention detection walks it here, and the
  UI renders the same tree, so the UI never links what the daemon didn't count.

  A mention is the token KEY-N with no letter, digit, _ or - on either side, outside code, or a link whose path is
  exactly /KEY-N, whatever the host."
  (:require [nextjournal.markdown :as md]))

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
