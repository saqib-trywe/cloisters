(ns cloisters.client
  "Calling the daemon's Ring handler in-process, as a local client would over HTTP: requests carry the Host the
  hardening expects, and JSON bodies go through the wire's own mapper."
  (:require [cloisters.http :as http]
            [cloisters.json :as json]
            [clojure.string :as str])
  (:import (java.io ByteArrayInputStream)
           (java.net URLEncoder)
           (java.nio.charset StandardCharsets)))

(def port 7290)

(defn handler
  "The daemon's handler over ctx, as served on port."
  [ctx]
  (:handler (http/app (assoc ctx :port port :version "test"))))

(defn- query-string [params]
  (when (seq params)
    (str/join "&" (for [[k v] params]
                    (str (name k) "=" (URLEncoder/encode (str v) StandardCharsets/UTF_8))))))

(defn request
  "A Ring request: method, uri, and optionally :query (a map), :body (a string, or data sent as JSON) and :headers."
  [method uri & {:keys [query body headers]}]
  (let [body (if (or (nil? body) (string? body)) body (json/write-str body))]
    (cond-> {:request-method method
             :uri uri
             :scheme :http
             :server-name "127.0.0.1"
             :server-port port
             :remote-addr "127.0.0.1"
             :headers (merge {"host" (str "127.0.0.1:" port)} headers)}
      query (assoc :query-string (query-string query))
      body (assoc :body (ByteArrayInputStream. (.getBytes ^String body StandardCharsets/UTF_8))))))

(defn call
  "Sends a request to h: the response, its :json body decoded with kebab-case keys when it is JSON."
  [h method uri & opts]
  (let [{:keys [headers body] :as response} (h (apply request method uri opts))
        body (if (string? body) body (some-> body slurp))]
    (cond-> (assoc response :body body)
      (str/starts-with? (str (get headers "Content-Type")) "application/json")
      (assoc :json (json/read-str body)))))
