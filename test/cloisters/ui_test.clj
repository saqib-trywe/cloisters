(ns cloisters.ui-test
  "The web UI through the daemon's handler: the safe renderer and its URL allow-list, the side-by-side diff, routing
  by what an id names, inline edits recording web, and the editor's stale-save guard. scripts/ui-test.js drives the
  same UI with htmx and live updates under jsdom."
  (:require [cloisters.client :as client :refer [call]]
            [cloisters.diff :as diff]
            [cloisters.events :as events]
            [cloisters.fixtures :as fx :refer [with-ctx]]
            [cloisters.issues :as issues]
            [cloisters.markdown :as markdown]
            [cloisters.pages :as pages]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hiccup2.core :as h]))

(defn- html [markdown refer]
  (str (h/html (markdown/render markdown refer))))

(deftest the-url-allow-list
  (testing "http, https, mailto and site-relative URLs pass"
    (is (every? markdown/safe-url? ["http://x.org" "HTTPS://x.org/a?b#c" "mailto:a@b.c" "/CLO-7" "/assets/x.png"
                                    "  https://x.org"])))
  (testing "anything else does not: scripts, data, protocol-relative, other schemes, relative paths and fragments"
    (is (not-any? markdown/safe-url? ["javascript:alert(1)" "JavaScript:alert(1)" " javascript:x" "data:text/html,x"
                                      "vbscript:x" "//evil.org/x" "/\\evil.org" "file:///etc/passwd" "ftp://x"
                                      "relative/path" "#top" "" nil]))))

(deftest the-safe-renderer
  (let [refer {["CLO" 1] {:id "CLO-1" :kind "issue" :title "Map" :status "closed"}
               ["CLO" 2] {:id "CLO-2" :kind "page" :title "Old" :archived true}
               ["CLO" 9] :missing}]
    (testing "raw HTML shows as text, inline or as a block"
      (let [s (html "a <b onclick=x>b</b>\n\n<script>alert(1)</script>\n\n<div>x</div>" refer)]
        (is (not (str/includes? s "<script")))
        (is (not (str/includes? s "<b ")))
        (is (str/includes? s "&lt;script&gt;alert(1)&lt;/script&gt;"))))
    (testing "an unsafe link keeps its text and loses its link; an unsafe image becomes its alt text"
      (let [s (html "[click](javascript:alert(1)) ![pic](data:image/png;base64,AA) [ok](https://x.org)" refer)]
        (is (not (str/includes? s "javascript:")))
        (is (not (str/includes? s "data:")))
        (is (not (str/includes? s "<img")))
        (is (str/includes? s "click"))
        (is (str/includes? s "pic"))
        (is (str/includes? s "<a href=\"https://x.org\">ok</a>"))))
    (testing "attributes are escaped"
      (is (str/includes? (html "[x](https://x.org/\"onmouseover=\"alert(1) \"t\\\"x\")" refer) "&quot;")))
    (testing "mentions: closed struck through, archived muted and marked, missing muted, code never linked"
      (let [s (html "CLO-1 CLO-2 CLO-9 UTF-8 `CLO-1`" refer)]
        (is (str/includes? s "<a class=\"ref closed\" href=\"/CLO-1\" title=\"Map (closed)\">CLO-1</a>"))
        (is (str/includes? s "<a class=\"ref archived\" href=\"/CLO-2\" title=\"Old (archived)\">CLO-2"))
        (is (str/includes? s "<span class=\"flag\"> archived</span>"))
        (is (str/includes? s "<span class=\"missing\" title=\"CLO-9 doesn&apos;t exist yet\">CLO-9</span>"))
        (is (str/includes? s "UTF-8"))
        (is (str/includes? s "<code>CLO-1</code>"))))
    (testing "a mention inside a link is not linked again, and a link to /KEY-N takes the target's state"
      (let [s (html "[see CLO-1](/CLO-2)" refer)]
        (is (= 1 (count (re-seq #"<a " s))))
        (is (str/includes? s "class=\"ref archived\""))))
    (testing "the renderer links exactly what detection counts"
      (let [text "CLO-1 `CLO-9` [x](/CLO-2) ![CLO-9](/i.png) <i>CLO-9</i>"
            rendered (html text refer)
            shown (into (set (map second (re-seq #"href=\"/(CLO-\d+)\"" rendered)))
                        (map second (re-seq #"class=\"missing\"[^>]*>(CLO-\d+)<" rendered)))]
        (is (= #{"CLO-1" "CLO-2" "CLO-9"} shown) "the CLO-9 between raw tags is prose")
        (is (= #{["CLO" 1] ["CLO" 2] ["CLO" 9]} (set (markdown/mentions text))))))
    (testing "tables, task lists and footnotes render"
      (let [s (html "| a |\n|:-:|\n| 1 |\n\n- [x] done\n\nnote[^1]\n\n[^1]: CLO-1" refer)]
        (is (str/includes? s "<th style=\"text-align:center\">a</th>"))
        (is (str/includes? s "checked"))
        (is (str/includes? s "class=\"footnotes\""))
        (is (str/includes? s "href=\"/CLO-1\""))))))

(deftest side-by-side
  (let [rows (diff/side-by-side "a\nthe quick fox\ngone\nend" "a\nthe slow fox\nend\nnew")]
    (testing "rows carry their tag and line numbers on each side"
      (is (= [:equal :change :delete :equal :insert] (map :tag rows)))
      (is (= [[1 1] [2 2] [3 nil] [4 3] [nil 4]] (map (juxt (comp :line :old) (comp :line :new)) rows))))
    (testing "changed words are marked"
      (is (= [["the " false] ["quick" true] [" fox" false]] (:segments (:old (second rows)))))
      (is (= [["the " false] ["slow" true] [" fox" false]] (:segments (:new (second rows)))))))
  (testing "the mark characters cannot be forged by the text: they are stripped first"
    (is (= [["ab" false]] (-> (diff/side-by-side "a\u0001b" "a\u0001b") first :new :segments))))
  (testing "identical texts are all equal rows"
    (is (every? #(= :equal (:tag %)) (diff/side-by-side "a\nb" "a\nb")))))

(defn- form [params]
  {:body (str/join "&" (for [[k v] params] (str (name k) "=" (java.net.URLEncoder/encode (str v) "UTF-8"))))
   :headers {"content-type" "application/x-www-form-urlencoded"}})

(defn- post [h uri params & {:keys [htmx headers]}]
  (let [{:keys [body] form-headers :headers} (form params)]
    (call h :post uri :body body :headers (cond-> (merge form-headers headers) htmx (assoc "hx-request" "true")))))

(deftest routes
  (with-ctx [c (fx/ctx)]
    (let [h (client/handler c)
          issue (:id (issues/create-issue c {:title "Map <b>" :body "See CLO-2."}))
          page (:id (pages/create-page c {:title "Context" :body "Tracked by CLO-1."}))]
      (testing "an id routes by what it names"
        (is (str/includes? (:body (call h :get (str "/" issue))) "Map &lt;b&gt;"))
        (is (str/includes? (:body (call h :get (str "/" page))) "/CLO-2/edit")))
      (testing "an id not yet created, an unknown project and a non-UI path"
        (is (= 404 (:status (call h :get "/CLO-99"))))
        (is (= 404 (:status (call h :get "/NOPE-1"))))
        (is (= "application/json" (get-in (call h :get "/nowhere") [:headers "Content-Type"]))))
      (testing "/ remembers the last query"
        (is (= "/?q=ready" (get-in (call h :get "/") [:headers "Location"])))
        (let [r (call h :get "/" :query {:q "kind:page"})]
          (is (= 200 (:status r)))
          (is (= "/?q=kind%3Apage"
                 (get-in (call h :get "/" :headers {"cookie" (first (str/split (get-in r [:headers "Set-Cookie"]) #";"))})
                         [:headers "Location"])))))
      (testing "the UI is localhost-hardened like the API"
        (is (= 403 (:status (call h :get "/" :headers {"host" "evil.example:7290"}))))
        (is (= 403 (:status (post h (str "/" issue "/comment") {:comment "x"} :headers {"origin" "http://evil"})))))
      (testing "an inline edit answers the refreshed item, recording web"
        (let [r (post h (str "/" issue "/comment") {:comment "Noted CLO-2."} :htmx true)]
          (is (= 200 (:status r)))
          (is (str/starts-with? (:body r) "<div id=\"item\">"))
          (is (str/includes? (:body r) "Noted <a class=\"ref\" href=\"/CLO-2\"")))
        (is (= "web" (:actor (peek (:events (events/feed c {})))))))
      (testing "a refused inline edit shows the refusal at its status"
        (let [r (post h (str "/" issue "/close") {:resolution "done" :comment " "} :htmx true)]
          (is (= 400 (:status r)))
          (is (str/includes? (:body r) "invalid_argument"))))
      (testing "without htmx, an inline edit redirects back"
        (is (= 303 (:status (post h (str "/" issue "/labels") {:add "ux"}))))
        (is (= ["ux"] (:labels (issues/get-issue c {:id issue})))))
      (testing "a stale save keeps the text and moves the form to the current version"
        (pages/update-page c {:id page :body "Underneath." :expected-version 1})
        (let [r (post h (str "/" page "/edit") {:title "Context" :body "Mine\r\nhere" :expected_version 1})]
          (is (= 409 (:status r)))
          (is (str/includes? (:body r) "Mine\nhere</textarea>"))
          (is (str/includes? (:body r) "name=\"expected_version\" type=\"hidden\" value=\"2\""))
          (is (str/includes? (:body r) "<ins>Underneath</ins>")))
        (let [r (post h (str "/" page "/edit") {:title "Context" :body "Mine\r\nhere" :expected_version 2
                                                :summary "merged"})]
          (is (= 303 (:status r)))
          (is (= "Mine\nhere" (:body (pages/get-page c {:id page}))) "line endings are the store's")))
      (testing "the banner names who changed the page since it was loaded"
        (is (= "" (:body (call h :get (str "/" page "/banner") :query {:version 3}))))
        (is (str/includes? (:body (call h :get (str "/" page "/banner") :query {:version 1}))
                           "Changed since you opened it, by test, web"))))))
