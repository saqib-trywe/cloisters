(ns cloisters.json
  "The one JSON mapper for every edge: kebab-case keywords in the daemon, snake_case on the wire, nulls dropped."
  (:require [clojure.string :as str]
            [jsonista.core :as j])
  (:import (clojure.lang PersistentArrayMap)))

(def mapper
  (j/object-mapper {:encode-key-fn #(str/replace (name %) "-" "_")
                    :decode-key-fn #(keyword (str/replace % "_" "-"))}))

(defn drop-nils
  "x without nil map values, at any depth. Array maps keep their key order."
  [x]
  (cond
    (map? x) (let [kvs (for [[k v] x :when (some? v)] [k (drop-nils v)])]
               (if (instance? PersistentArrayMap x)
                 (apply array-map (apply concat kvs))
                 (into {} kvs)))
    (sequential? x) (mapv drop-nils x)
    :else x))

(defn write-str [x]
  (j/write-value-as-string (drop-nils x) mapper))

(defn read-str [s]
  (j/read-value s mapper))
