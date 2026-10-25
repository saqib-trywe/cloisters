(ns cloisters.ui
  "PROTOTYPE. The web UI as a third thin adapter over the core: server-rendered hiccup, htmx for fragments, one SSE
  stream turned into htmx triggers by assets/live.js. Writes record `web`."
  (:require [clojure.string :as str]
            [cloisters.core :as core]
            [cloisters.markdown :as markdown]
            [hiccup2.core :as h]))

(defn- layout [title & body]
  (str "<!doctype html>"
       (h/html [:html
                [:head [:meta {:charset "utf-8"}] [:title (str title " · cloisters skeleton")]
                 [:link {:rel "stylesheet" :href "/assets/app.css"}]
                 [:script {:src "/assets/htmx.min.js"}] [:script {:src "/assets/live.js"}]]
                [:body [:nav [:a {:href "/"} "cloisters"] " " [:span.proto "PROTOTYPE skeleton"]]
                 (into [:main] body)]])))

(defn- html [status s] {:status status :headers {"Content-Type" "text/html; charset=utf-8"} :body s})
(defn- fragment [hiccup] (html 200 (str (h/html hiccup))))

(defn- ref-li [{:keys [id kind title status archived]}]
  [:li [:a {:href (str "/" id) :class (when archived "archived")} id] " "
   [:span.kind (cond (= kind "issue") status archived "archived" kind "page" :else "not yet created")]
   " " title])

(defn- refs [label xs]
  (when (seq xs) [:section [:h3 label] (into [:ul] (map ref-li xs))]))

(defn- view [ctx id]
  (let [kind (try (core/get-issue ctx {:id id}) :issue
                  (catch clojure.lang.ExceptionInfo e
                    (if (= "wrong_kind" (:error (ex-data e))) :page (throw e))))
        item (if (= kind :issue) (core/get-issue ctx {:id id}) (core/get-page ctx {:id id}))]
    [:article
     [:header [:h1 (:title item)]
      [:p.meta id " · " (if (= kind :issue) (:status item) (str "page · r" (:version item)))
       (when-let [p (:parent item)] (list " · under " [:a {:href (str "/" p)} p]))
       (when (= kind :page) (list " · " [:a {:href (str "/" id "/edit")} "edit"]))]]
     (markdown/render (:body item) (core/lookup ctx))
     (refs "Children" (:children item))
     (refs "Mentions" (:mentions item))
     (refs "Backlinks" (:backlinks item))]))

(defn- editor [ctx id {:keys [title body summary version stale]}]
  (layout (str "Edit " id)
          [:h1 "Edit " [:a {:href (str "/" id)} id]]
          [:div#banner {:hx-get (str "/" id "/banner?version=" version) :hx-trigger "cloisters:change from:body"}]
          (when stale
            [:div.stale
             [:p [:strong "Not saved: "] id " changed underneath you; it is now r" (:current-version stale)
              ". Your text is kept below. Merge by hand and save again."]
             [:p "The current body:"] [:pre (:body stale)]])
          [:form {:method "post" :action (str "/" id "/edit")}
           [:input {:type "hidden" :name "expected_version" :value version}]
           [:p [:input {:name "title" :value title :size 60}]]
           [:div.split
            [:textarea {:name "body" :rows 20 :cols 70 :hx-post (str "/" id "/preview")
                        :hx-trigger "input changed delay:300ms" :hx-target "#preview" :hx-swap "innerHTML"}
             body]
            [:div#preview (markdown/render body (core/lookup ctx))]]
           [:p [:input {:name "summary" :value summary :placeholder "edit summary" :size 60}]]
           [:p [:button {:type "submit"} "Save"]]]))

(defn- route [path]
  (re-matches #"/([A-Z][A-Z0-9]{1,9}-[1-9][0-9]*)(/edit|/fragment|/preview|/banner)?" path))

(defn- home [ctx q]
  (let [{:keys [results effective-query]} (core/search ctx {:q q})]
    (layout "Search"
            [:form {:method "get" :action "/"} [:input {:name "q" :value q :size 60}] [:button "Search"]]
            [:p.meta effective-query]
            (into [:ul] (map ref-li results)))))

(defn handler [ctx]
  (let [ctx (assoc ctx :project "CLO")]
    (fn [{:keys [uri request-method params]}]
      (try
        (if (= uri "/")
          (html 200 (home ctx (get params "q" "")))
          (let [[_ id action] (route uri)]
            (case [request-method action]
              [:get nil] (html 200 (layout id [:div#view {:hx-get (str "/" id "/fragment")
                                                          :hx-trigger "cloisters:change from:body"}
                                               (view ctx id)]))
              [:get "/fragment"] (fragment (view ctx id))
              [:get "/edit"] (let [p (core/get-page ctx {:id id})]
                               (html 200 (editor ctx id (assoc p :summary ""))))
              [:get "/banner"] (let [v (:version (core/get-page ctx {:id id}))]
                                 (fragment (when (not= (str v) (get params "version"))
                                             [:p.banner "Changed since you opened it: now r" v ". "
                                              [:a {:href (str "/" id) :target "_blank"} "see it"]])))
              [:post "/preview"] (fragment (markdown/render (get params "body") (core/lookup ctx)))
              [:post "/edit"]
              (let [form {:title (get params "title") :body (str/replace (get params "body" "") "\r\n" "\n")
                          :summary (not-empty (get params "summary"))}
                    version (parse-long (get params "expected_version"))]
                (try
                  (core/update-page ctx (merge {:id id :expected-version version}
                                               (into {} (remove (comp nil? val)) form))
                                    "web")
                  {:status 303 :headers {"Location" (str "/" id)} :body ""}
                  (catch clojure.lang.ExceptionInfo e
                    (if (= "stale_version" (:error (ex-data e)))
                      (html 409 (editor ctx id (assoc form :version (:current-version (ex-data e)) :stale (ex-data e))))
                      (throw e)))))
              (html 404 (layout "Not found" [:p "No such page."])))))
        (catch clojure.lang.ExceptionInfo e
          (if-let [code (:error (ex-data e))]
            (html (:status (ex-data e)) (layout code [:p [:strong code] ": " (ex-message e)]))
            (throw e)))))))
