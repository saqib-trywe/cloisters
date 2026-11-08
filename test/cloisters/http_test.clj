(ns cloisters.http-test
  "The HTTP API through the daemon's handler, and the event stream through a real Jetty: routes, statuses by error
  code, text by content negotiation, the bound project, the actor, localhost hardening and the catch-all."
  (:require [cloisters.client :as client :refer [call]]
            [cloisters.events :as events]
            [cloisters.fixtures :as fx :refer [with-ctx]]
            [cloisters.http :as http]
            [cloisters.issues :as issues]
            [cloisters.store :as store]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import (java.io BufferedReader)
           (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)))

(def ^:private cli {"cloisters-client" "cli" "cloisters-project" "CLO"})

(deftest issue-routes
  (with-ctx [c (fx/ctx)]
    (let [h (client/handler c)
          send (fn [method uri & [body]] (call h method uri :body body :headers cli))]
      (testing "create answers 201 with the row and version"
        (let [{:keys [status json]} (send :post "/api/issues" {:title "Map" :labels ["wayfinder:map"]})]
          (is (= 201 status))
          (is (= {:id "CLO-1" :kind "issue" :version 1 :labels ["wayfinder:map"]}
                 (select-keys json [:id :kind :version :labels])))
          (is (not (contains? json :assignee)) "nulls are dropped")))
      (send :post "/api/issues" {:title "Ticket" :parent "CLO-1" :body "see CLO-1"})
      (testing "get, with events on request"
        (let [{:keys [json]} (call h :get "/api/issues/CLO-2" :query {:include "events"})]
          (is (= "see CLO-1" (:body json)))
          (is (= 1 (:events-total json)))))
      (testing "update takes snake_case arguments"
        (is (= 2 (:version (:json (send :patch "/api/issues/CLO-2"
                                        {:body_edits [{:old "see" :new "See"}] :expected_version 1}))))))
      (testing "the intent actions"
        (is (= "saqib/wf-1" (:assignee (:json (send :post "/api/issues/CLO-2/claim" {:assignee "saqib/wf-1"})))))
        (is (= [409 "claim_conflict" "saqib/wf-1"]
               ((juxt :status (comp :error :json) (comp :holder :json))
                (send :post "/api/issues/CLO-2/claim" {:assignee "other/wf-2"}))))
        (is (nil? (:assignee (:json (send :post "/api/issues/CLO-2/release" {:assignee "saqib/wf-1"})))))
        (is (= "x/1" (:assignee (:json (send :post "/api/issues/CLO-2/reassign" {:from "none" :to "x/1"})))))
        (is (= "closed" (:status (:json (send :post "/api/issues/CLO-2/close" {:resolution "done" :comment "ok"})))))
        (is (= "open" (:status (:json (send :post "/api/issues/CLO-2/reopen" {:comment "again"}))))))
      (testing "the full history pages by sequence"
        (let [{:keys [json]} (call h :get "/api/issues/CLO-2/events" :query {:limit 2})]
          (is (= 2 (count (:events json))))
          (is (:has-more json))
          (is (= 5 (count (:events (:json (call h :get "/api/issues/CLO-2/events"
                                                :query {:cursor (:next-cursor json)}))))))))
      (testing "the actor is the client the CLI names"
        (is (every? #{"cli"} (map :actor (:events (events/feed c {})))))))))

(deftest page-routes
  (with-ctx [c (fx/ctx)]
    (let [h (client/handler c)
          send (fn [method uri & [body]] (call h method uri :body body :headers cli))]
      (is (= 201 (:status (send :post "/api/pages" {:title "Glossary" :body "one"}))))
      (send :post "/api/pages" {:title "Terms" :parent "CLO-1"})
      (send :patch "/api/pages/CLO-1" {:body "two" :expected_version 1 :summary "more"})
      (testing "get, an old revision, revisions and diff"
        (is (= "two" (:body (:json (call h :get "/api/pages/CLO-1")))))
        (is (= "one" (:body (:json (call h :get "/api/pages/CLO-1" :query {:revision 1})))))
        (is (= [1 2] (map :revision (:json (call h :get "/api/pages/CLO-1/revisions")))))
        (is (str/includes? (:diff (:json (call h :get "/api/pages/CLO-1/diff" :query {:from 1 :to 2}))) "+two")))
      (testing "a stale save is 409 with the current body"
        (let [{:keys [status json]} (send :patch "/api/pages/CLO-1" {:body "three" :expected_version 1})]
          (is (= [409 "stale_version" 2 "two"] [status (:error json) (:version json) (:body json)]))))
      (testing "archiving with live children names them"
        (let [{:keys [status json]} (send :post "/api/pages/CLO-1/archive" {:summary "old"})]
          (is (= [409 "live_children" ["CLO-2"]] [status (:error json) (map :id (:children json))]))))
      (testing "archive, unarchive and restore"
        (is (:archived (:json (send :post "/api/pages/CLO-2/archive" {:summary "old"}))))
        (is (false? (:archived (:json (send :post "/api/pages/CLO-2/unarchive" {:summary "back"})))))
        (is (= 3 (:version (:json (send :post "/api/pages/CLO-1/restore" {:revision 1 :summary "undo"})))))))))

(deftest search-and-projects
  (with-ctx [c (fx/ctx)]
    (let [h (client/handler c)]
      (call h :post "/api/issues" :body {:title "Tool"} :headers cli)
      (call h :post "/api/pages" :body {:title "Glossary"} :headers cli)
      (testing "one search, mixed rows, scoped by the bound project"
        (let [{:keys [json]} (call h :get "/api/search" :query {:q "text:o"} :headers cli)]
          (is (= "project:CLO text:o" (:effective-query json)))
          (is (= ["issue" "page"] (map :kind (:results json))))))
      (testing "projects are created and listed"
        (is (= 201 (:status (call h :post "/api/projects" :body {:key "OTH" :name "Other"}))))
        (is (= ["CLO" "OTH"] (map :key (:json (call h :get "/api/projects"))))))
      (testing "an unknown bound project is refused, naming those that exist"
        (let [{:keys [status json]} (call h :get "/api/search" :headers {"cloisters-project" "NOPE"})]
          (is (= [404 "unknown_project" ["CLO" "OTH"]] [status (:error json) (:projects json)])))))))

(deftest statuses-and-text
  (with-ctx [c (fx/ctx)]
    (let [h (client/handler c)
          text {"accept" "text/plain"}]
      (call h :post "/api/pages" :body {:title "Glossary"} :headers cli)
      (testing "each error code keeps its status"
        (is (= [404 "wrong_kind" "page"] ((juxt :status (comp :error :json) (comp :kind :json))
                                          (call h :get "/api/issues/CLO-1"))))
        (is (= [404 "not_found"] ((juxt :status (comp :error :json)) (call h :get "/api/pages/CLO-9"))))
        (is (= [400 "invalid_query"] ((juxt :status (comp :error :json))
                                      (call h :get "/api/search" :query {:q "bogus:1"}))))
        (is (= [400 "invalid_argument" "limit"] ((juxt :status (comp :error :json) (comp :argument :json))
                                                 (call h :get "/api/search" :query {:limit "lots"})))))
      (testing "malformed JSON is invalid_argument, never a plain-text error"
        (is (= [400 "invalid_argument"] ((juxt :status (comp :error :json))
                                         (call h :post "/api/issues" :body "{nope" :headers cli)))))
      (testing "Accept: text/plain gives the text rendering, and refusals led by their code"
        (let [{:keys [status body]} (call h :get "/api/pages/CLO-1" :headers text)]
          (is (= 200 status))
          (is (str/starts-with? body "CLO-1  page  Glossary\n")))
        (let [{:keys [status body]} (call h :get "/api/issues/CLO-1" :headers text)]
          (is (= 404 status))
          (is (= "wrong_kind: CLO-1 is a page, not an issue.\n" body))))
      (testing "meta reports the version and data directory"
        (is (= {:version "test"} (select-keys (:json (call h :get "/api/meta")) [:version]))))
      (testing "unknown routes and assets are 404"
        (is (= 404 (:status (call h :get "/api/nothing"))))
        (is (= 404 (:status (call h :get "/assets/nothing.js"))))))))

(deftest localhost-hardening
  (with-ctx [c (fx/ctx)]
    (let [h (client/handler c)]
      (is (= 200 (:status (call h :get "/api/meta" :headers {"host" (str "localhost:" client/port)}))))
      (is (= 200 (:status (call h :get "/api/meta" :headers {"origin" (str "http://127.0.0.1:" client/port)}))))
      (testing "a rebinding Host or a foreign Origin is refused, on every route"
        (is (= 403 (:status (call h :get "/api/meta" :headers {"host" (str "evil.example:" client/port)}))))
        (is (= 403 (:status (call h :get "/api/meta" :headers {"host" "127.0.0.1:9999"}))))
        (is (= 403 (:status (call h :post "/mcp" :headers {"origin" "http://evil.example"}))))))))

(deftest the-catch-all
  (let [c (fx/ctx)
        h (client/handler c)]
    (store/close (:store c))
    (testing "an unexpected failure answers in the contract's shape"
      (let [{:keys [status json]} (call h :get "/api/projects")]
        (is (= 500 status))
        (is (= "internal_error" (:error json)))))))

;; ---------------------------------------------------------------- the event stream, over a real server

(defn- stream-lines
  "Opens the event stream on port with headers, and returns [lines close]: lines takes the next n lines, waiting up
  to 10 s."
  [port headers]
  (let [req (-> (HttpRequest/newBuilder (URI. (str "http://127.0.0.1:" port "/api/events/stream")))
                (as-> b (reduce (fn [b [k v]] (.header b k v)) b headers))
                (.build))
        client (HttpClient/newHttpClient)
        response (.send client req (HttpResponse$BodyHandlers/ofInputStream))
        ^BufferedReader reader (io/reader (.body response))]
    [(fn [n]
       (deref (future (vec (repeatedly n #(.readLine reader)))) 10000 ::timeout))
     #(do (.close reader) (.close client))
     response]))

(defn- http-post
  "POSTs a JSON body to the server on port: its status."
  [port path body]
  (let [req (-> (HttpRequest/newBuilder (URI. (str "http://127.0.0.1:" port path)))
                (.header "Content-Type" "application/json")
                (.POST (HttpRequest$BodyPublishers/ofString body))
                (.build))]
    (.statusCode (.send (HttpClient/newHttpClient) req (HttpResponse$BodyHandlers/discarding)))))

(deftest the-event-stream
  (with-ctx [c (fx/ctx)]
    (let [port (fx/free-port)
          server (http/start! (assoc c :version "test") port)]
      (try
        ;; written straight through the core, so only the replay can show them
        (issues/create-issue c {:title "one"})
        (issues/create-issue c {:title "two"})
        (testing "it opens with a comment, replays after Last-Event-ID, then streams live writes"
          (let [[lines close response] (stream-lines port {"Last-Event-ID" "1"})]
            (try
              (is (= "text/event-stream" (first (.allValues (.headers response) "content-type"))))
              (is (= [": open" ""] (lines 2)))
              (let [[id data blank] (lines 3)]
                (is (= "id: 2" id))
                (is (str/includes? data "\"title\""))
                (is (= "" blank)))
              (let [p (promise)]
                (future (deliver p (lines 3)))
                (Thread/sleep 200)
                (http-post port "/api/issues" "{\"title\":\"three\",\"project\":\"CLO\"}")
                (is (= "id: 3" (first (deref p 10000 ::timeout)))))
              (finally (close)))))
        (testing "without Last-Event-ID or after, it starts at the newest event"
          (let [[lines close] (stream-lines port {})]
            (try
              (is (= [": open" ""] (lines 2)))
              (let [p (promise)]
                (future (deliver p (lines 1)))
                (Thread/sleep 200)
                (http-post port "/api/issues" "{\"title\":\"four\",\"project\":\"CLO\"}")
                (is (= ["id: 4"] (deref p 10000 ::timeout))))
              (finally (close)))))
        (finally (http/stop! server))))))
