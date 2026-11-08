(ns cloisters.diff
  "The one line diff behind every surface: unified text for MCP, the CLI and issue event rendering, and the UI's
  diff view."
  (:require [clojure.string :as str])
  (:import (com.github.difflib DiffUtils UnifiedDiffUtils)
           (com.github.difflib.patch Patch)))

(def context-lines 3)

(defn unified
  "A unified diff of old against new, headed by the labels from and to. Empty when the texts have the same lines."
  [old new from to]
  (let [a (str/split-lines (or old ""))
        b (str/split-lines (or new ""))
        ^Patch patch (DiffUtils/diff ^java.util.List a ^java.util.List b)]
    (if (empty? (.getDeltas patch))
      ""
      (str/join "\n" (UnifiedDiffUtils/generateUnifiedDiff from to a patch context-lines)))))
