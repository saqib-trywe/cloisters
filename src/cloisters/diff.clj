(ns cloisters.diff
  "The one line diff behind every surface: unified text for MCP, the CLI and issue event rendering, and side-by-side
  rows with word highlights for the UI's diff view."
  (:require [clojure.string :as str])
  (:import (com.github.difflib DiffUtils UnifiedDiffUtils)
           (com.github.difflib.patch Patch)
           (com.github.difflib.text DiffRow DiffRow$Tag DiffRowGenerator DiffRowGenerator$Builder)
           (java.util.function Function)))

(def context-lines 3)

(defn- lines [s]
  (if (empty? s) [] (str/split-lines s)))

(defn unified
  "A unified diff of old against new, headed by the labels from and to. Empty when the texts have the same lines."
  [old new from to]
  (let [a (lines (or old ""))
        b (lines (or new ""))
        ^Patch patch (DiffUtils/diff ^java.util.List a ^java.util.List b)]
    (if (empty? (.getDeltas patch))
      ""
      (str/join "\n" (UnifiedDiffUtils/generateUnifiedDiff from to a patch context-lines)))))

;; ---------------------------------------------------------------- side by side

;; The generator marks changed words with these control characters, stripped from the texts first, so its output
;; splits back into plain segments: nothing here is HTML, and the UI escapes every segment.
(def ^:private open-mark "\u0001")
(def ^:private close-mark "\u0002")

(def ^:private ^Function marks (reify Function (apply [_ start?] (if start? open-mark close-mark))))

(def ^:private ^DiffRowGenerator generator
  (let [^DiffRowGenerator$Builder b (DiffRowGenerator/create)]
    (.showInlineDiffs b true)
    (.inlineDiffByWord b true)
    (.oldTag b marks)
    (.newTag b marks)
    (.lineNormalizer b (reify Function (apply [_ s] s)))
    (.build b)))

(defn- segments
  "A marked line as [text changed?] pairs, dropping empty ones."
  [^String line]
  (loop [[part & more] (str/split line #"(?=[\u0001\u0002])|(?<=[\u0001\u0002])") changed false out []]
    (cond (nil? part) out
          (= open-mark part) (recur more true out)
          (= close-mark part) (recur more false out)
          (empty? part) (recur more changed out)
          :else (recur more changed (conj out [part changed])))))

(def ^:private tags
  {DiffRow$Tag/EQUAL :equal DiffRow$Tag/INSERT :insert DiffRow$Tag/DELETE :delete DiffRow$Tag/CHANGE :change})

(defn side-by-side
  "old against new as rows {:tag :old :new}, tag one of :equal :insert :delete :change. Each side is nil when the row
  has no line there, else {:line n :segments [[text changed?] …]}, with changed words marked on a :change row."
  [old new]
  (let [clean #(str/replace (or % "") #"[\u0001\u0002]" "")
        rows (.generateDiffRows generator ^java.util.List (lines (clean old)) ^java.util.List (lines (clean new)))]
    (loop [[^DiffRow r & more] rows a 0 b 0 out []]
      (if-not r
        out
        (let [tag (tags (.getTag r))
              old? (not= :insert tag)
              new? (not= :delete tag)
              a (cond-> a old? inc)
              b (cond-> b new? inc)]
          (recur more a b
                 (conj out {:tag tag
                            :old (when old? {:line a :segments (segments (.getOldLine r))})
                            :new (when new? {:line b :segments (segments (.getNewLine r))})})))))))
