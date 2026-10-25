(ns cloisters.text
  "PROTOTYPE. The compact text rendering: MCP's text block, and what the CLI prints via Accept: text/plain."
  (:require [clojure.string :as str]))

(defn- ref-line [{:keys [id kind title status archived]}]
  (str id "  " (cond (= kind "issue") status archived "archived" (= kind "page") "page" :else "unknown")
       (when title (str "  " title))))

(defn row [{:keys [kind status archived] :as r}]
  (ref-line (cond-> r (= kind "page") (assoc :archived archived) (= kind "issue") (assoc :status status))))

(defn- refs [label xs]
  (when (seq xs) (str "\n\n" label ":\n" (str/join "\n" (map #(str "  " (ref-line %)) xs)))))

(defn- detail [{:keys [body mentions backlinks children version parent] :as r}]
  (str (row r)
       (when version (str "  r" (:revision r) (when (not= (:revision r) version) (str " (latest r" version ")"))))
       (when parent (str "\nparent: " parent))
       "\n\n" (if (str/blank? body) "(no body)" body)
       (refs "children" children)
       (refs "mentions" mentions)
       (refs "backlinks" backlinks)))

(defmulti render (fn [op _] op))
(defmethod render :get-issue [_ r] (detail r))
(defmethod render :get-page [_ r] (detail r))
(defmethod render :search [_ {:keys [effective-query results has-more]}]
  (str "query: " effective-query "\n"
       (if (seq results) (str/join "\n" (map row results)) "(no results)")
       (when has-more "\n(more)")))
(defmethod render :default [_ r] (str (row r) (when-let [v (:version r)] (str "  r" v))))

(defn error [{:keys [error]} message] (str error ": " message))
