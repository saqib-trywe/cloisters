(ns cloisters.http
  "PROTOTYPE. The HTTP adapter: JSON (or Accept: text/plain) over the core, the resumable SSE event stream, the
  localhost hardening, and the router that mounts /api, /mcp, /assets and the UI."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cloisters.contract :as contract]
            [cloisters.core :as core]
            [cloisters.mcp :as mcp]
            [cloisters.text :as text]
            [cloisters.ui :as ui]
            [jsonista.core :as json]
            [reitit.ring :as ring]
            [ring.core.protocols :as protocols]
            [ring.middleware.params :as params])
  (:import (java.io IOException OutputStream)
           (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(def ^:private mapper (json/object-mapper {:encode-key-fn contract/snake :decode-key-fn contract/kebab}))

;; ---------------------------------------------------------------- events

(defonce subscribers (atom #{}))

(defn publish [event]
  (doseq [^LinkedBlockingQueue q @subscribers] (.offer q event)))

(defn- sse-line [{:keys [seq] :as e}]
  (str "id: " seq "\nevent: change\ndata: " (json/write-value-as-string e mapper) "\n\n"))

(defn- stream [ctx after]
  (reify protocols/StreamableResponseBody
    (write-body-to-stream [_ _ out]
      (let [q (LinkedBlockingQueue.)
            w (io/writer ^OutputStream out)]
        ;; subscribe before replaying, so nothing committed in between is lost; seq dedupes the overlap
        (swap! subscribers conj q)
        (try
          ;; Jetty commits the headers on the first write, so open with a comment: the client sees the stream at once
          (.write w ": open\n\n") (.flush w)
          (loop [sent after
                 backlog (core/events-after ctx after)]
            (if-let [e (first backlog)]
              (do (.write w ^String (sse-line e)) (.flush w) (recur (:seq e) (rest backlog)))
              (let [e (.poll q 15 TimeUnit/SECONDS)]
                (cond (nil? e) (do (.write w ": keep-alive\n\n") (.flush w) (recur sent nil))
                      (<= (:seq e) sent) (recur sent nil)
                      :else (recur sent [e])))))
          (catch IOException _ nil)
          (finally (swap! subscribers disj q) (.close w)))))))

(defn- events-stream [ctx request respond _]
  (let [after (or (some-> (get-in request [:headers "last-event-id"]) parse-long)
                  (some-> (get-in request [:query-params "after"]) parse-long)
                  (core/latest-seq ctx))]
    ;; respond from another thread: the stream runs there, the Jetty request thread is released
    (future (respond {:status 200
                      :headers {"Content-Type" "text/event-stream" "Cache-Control" "no-cache"}
                      :body (stream ctx after)}))))

;; ---------------------------------------------------------------- API

(defn- text? [request] (str/includes? (str (get-in request [:headers "accept"])) "text/plain"))

(defn- respond-with [request op status result]
  (if (text? request)
    {:status status :headers {"Content-Type" "text/plain; charset=utf-8"} :body (str (text/render op result) "\n")}
    {:status status :headers {"Content-Type" "application/json"} :body (json/write-value-as-string result mapper)}))

(defn- error-response [request e]
  (let [{:keys [status] :as d} (ex-data e)
        body (-> d (dissoc :status) (assoc :message (ex-message e)))]
    (if (text? request)
      {:status status :headers {"Content-Type" "text/plain; charset=utf-8"} :body (str (text/error d (ex-message e)) "\n")}
      {:status status :headers {"Content-Type" "application/json"} :body (json/write-value-as-string body mapper)})))

(defn- json-body [request]
  (let [s (slurp (:body request))]
    (if (str/blank? s)
      {}
      (try (let [v (json/read-value s mapper)]
             (if (map? v) v (throw (ex-info "body must be a JSON object" {:error "invalid_argument" :status 400}))))
           (catch com.fasterxml.jackson.core.JacksonException _
             (throw (ex-info "malformed JSON body" {:error "invalid_argument" :status 400})))))))

(defn- actor [request]
  (str "http:" (or (get-in request [:headers "cloisters-actor"]) "api")))

(defn- api [ctx op f & {:keys [status write from] :or {status 200}}]
  (fn [request]
    (try
      (let [ctx (assoc ctx :project (or (get-in request [:headers "cloisters-project"]) (:project ctx)))
            args (from request)
            result (if write (f ctx args (actor request)) (f ctx args))]
        (respond-with request op status result))
      (catch clojure.lang.ExceptionInfo e
        (if (:error (ex-data e)) (error-response request e) (throw e))))))

(defn- id-param [r] {:id (get-in r [:path-params :id])})

(defn- query-args [r keys]
  (into {} (keep (fn [k] (when-let [v (get-in r [:query-params (contract/snake k)])]
                           [k (if (= k :revision) (or (parse-long v) v) v)])))
        keys))

;; ---------------------------------------------------------------- hardening

(defn- harden [handler port]
  (let [hosts #{(str "localhost:" port) (str "127.0.0.1:" port)}
        origins #{(str "http://localhost:" port) (str "http://127.0.0.1:" port)}
        refuse {:status 403 :headers {"Content-Type" "text/plain"} :body "forbidden: not a local request\n"}
        ok? (fn [{:keys [headers]}]
              (and (hosts (get headers "host"))
                   (let [o (get headers "origin")] (or (nil? o) (origins o)))))]
    (fn
      ([request] (if (ok? request) (handler request) refuse))
      ([request respond raise] (if (ok? request) (handler request respond raise) (respond refuse))))))

(defn- sync->async [h]
  (fn ([r] (h r)) ([r respond raise] (try (respond (h r)) (catch Throwable t (raise t))))))

;; ---------------------------------------------------------------- the app

(defn app [ctx port]
  (let [mcp-route (fn [request]
                    (let [p (get-in request [:query-params "project"])]
                      (if (and p (not= p "CLO"))
                        {:status 404 :headers {"Content-Type" "text/plain"}
                         :body (str "no project " p "; known projects: CLO\n")}
                        (mcp/handler (assoc ctx :project p) request))))
        s (fn [h] {:handler (sync->async h)})]
    (-> (ring/ring-handler
         (ring/router
          [["/api"
            ["/meta" {:get (s (fn [_] {:status 200 :headers {"Content-Type" "application/json"}
                                       :body (json/write-value-as-string
                                              {:version "0.0.0-skeleton" :data-dir (str (get-in ctx [:dirs :data]))}
                                              mapper)}))}]
            ["/search" {:get (s (api ctx :search core/search :from #(query-args % [:q :project])))}]
            ["/issues" {:post (s (api ctx :create-issue core/create-issue :write true :status 201 :from json-body))}]
            ["/issues/:id" {:get (s (api ctx :get-issue core/get-issue :from id-param))}]
            ["/pages" {:post (s (api ctx :create-page core/create-page :write true :status 201 :from json-body))}]
            ["/pages/:id" {:get (s (api ctx :get-page core/get-page :from #(merge (id-param %) (query-args % [:revision]))))
                           :patch (s (api ctx :update-page core/update-page :write true
                                          :from #(merge (json-body %) (id-param %))))}]
            ["/events/stream" {:get {:handler (fn ([_] {:status 501}) ([r res rai] (events-stream ctx r res rai)))}}]]
           ["/mcp" {:handler (sync->async mcp-route)}]])
         (ring/routes
          (ring/create-resource-handler {:path "/assets" :root "assets"})
          (sync->async (ui/handler ctx))))
        params/wrap-params
        (harden port))))
