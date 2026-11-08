(ns cloisters.json
  "The one JSON mapper for every edge: kebab-case keywords in the daemon, snake_case on the wire, nulls dropped.
  String keys are written as they are, so protocol envelopes (MCP's camelCase) can sit around contract values."
  (:require [clojure.string :as str]
            [jsonista.core :as j])
  (:import (clojure.lang PersistentArrayMap)))

(def mapper
  (j/object-mapper {:encode-key-fn #(if (keyword? %) (str/replace (name %) "-" "_") (str %))
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

(defn write-str-keeping-nils
  "x as JSON without dropping nils, for envelopes that need an explicit null (a JSON-RPC error's id)."
  [x]
  (j/write-value-as-string x mapper))

(defn read-str
  "JSON as kebab-case keyword maps."
  [s]
  (j/read-value s mapper))

(defn read-plain
  "JSON with string keys as written, for protocol envelopes."
  [s]
  (j/read-value s))

(defn kebab-keys
  "Data decoded with string keys (from read-plain) as the contract's kebab-case keyword maps."
  [x]
  (read-str (j/write-value-as-string x)))
