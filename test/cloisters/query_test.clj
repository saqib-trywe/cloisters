(ns cloisters.query-test
  "The grammar on its own: parsing, printing and strict rejection, with no store."
  (:require [clojure.test :refer [deftest is testing]]
            [cloisters.fixtures :refer [rejection]]
            [cloisters.query :as query]))

(defn- refusal [s]
  (select-keys (rejection #(query/scope (query/parse s) nil)) [:error :token :suggestions]))

(deftest parsing
  (testing "filters, values, negation and predicates, in order"
    (is (= [{:name "ready" :negated false}
            {:name "label" :negated false :values ["bug" "ux"]}
            {:name "assignee" :negated true :values ["none"]}]
           (query/parse "ready label:bug,ux -assignee:none"))))
  (testing "an empty query is valid"
    (is (= [] (query/parse "")))
    (is (= [] (query/parse "   ")))
    (is (= [] (query/parse nil))))
  (testing "double quotes group spaces, commas and colons into one value, and escape with a backslash"
    (is (= [{:name "text" :negated false :values ["two words" "a,b" "say \"hi\"" "x:y"]}]
           (query/parse "text:\"two words\",\"a,b\",\"say \\\"hi\\\"\",x:y")))))

(deftest printing-round-trips
  (doseq [s ["ready label:bug,ux -assignee:none"
             "text:\"two words\",\"a,b\" kind:page archived:any"
             "text:\"say \\\"hi\\\" \\\\ there\""
             "project:* sort:updated-desc created-after:3d updated-before:2026-10-01T09:00Z"
             "assignee:saqib/* label:wayfinder:* -blocked under:CLO-1 mentions:CLO-2 mentioned-by:CLO-3"]]
    (let [clauses (query/parse s)]
      (is (= clauses (query/parse (query/print-query clauses))) s))))

(deftest strict-rejection
  (testing "an unknown filter names the token and suggests the nearest"
    (is (= {:error "invalid_query" :token "lable:bug" :suggestions ["label:"]} (refusal "ready lable:bug")))
    (is (= ["status:"] (:suggestions (refusal "Status:open")))))
  (testing "a bare word that is not a predicate suggests the nearest predicate, or text:"
    (is (= ["ready" "text:redy"] (:suggestions (refusal "redy"))))
    (is (= "label" (:token (refusal "label")))))
  (testing "a bad value lists the valid ones"
    (is (= ["status:open"] (:suggestions (refusal "status:opne"))))
    (is (= ["kind:issue" "kind:page"] (:suggestions (refusal "kind:wiki"))))
    (is (= ["sort:updated"] (:suggestions (refusal "sort:upated-desc"))))
    (is (= ["parent:CLO-1"] (:suggestions (refusal "parent:clo-1"))))
    (is (= ["project:CLO"] (:suggestions (refusal "project:clo")))))
  (testing "malformed tokens"
    (doseq [s ["text:\"unclosed" "label:" "label:a,,b" "-" ":bug" "\"label\":bug" "label:a*b" "assignee:*x*"
               "created-before:yesterday" "created-before:3w" "created-before:1d,2d" "-sort:rank" "sort:rank sort:created"
               "archived:no archived:yes" "-archived:yes" "project:*,CLO" "-project:*" "id:CLO-0" "ready:yes"]]
      (is (= "invalid_query" (:error (refusal s))) s))))

(deftest kinds-implied-and-contradictions
  (letfn [(effective [s bound] (:effective (query/scope (query/parse s) bound)))
          (kinds [s] (:kinds (query/scope (query/parse s) nil)))]
    (testing "an issue-only filter implies kind:issue; archived: implies kind:page; both show in effective_query"
      (is (= "project:CLO kind:issue ready parent:CLO-1" (effective "ready parent:CLO-1" "CLO")))
      (is (= "kind:issue -label:bug" (effective "-label:bug" nil)))
      (is (= "kind:issue sort:closed-desc" (effective "sort:closed-desc" nil)))
      (is (= "kind:page archived:yes" (effective "archived:yes" nil)))
      (is (= #{"issue" "page"} (kinds "text:x")))
      (is (= #{"page"} (kinds "-kind:issue")))
      (is (= #{"issue"} (kinds "kind:issue,page status:open"))))
    (testing "an explicit project always wins over the binding"
      (is (= "kind:issue project:* ready" (effective "project:* ready" "CLO")))
      (is (= "-project:CLO" (effective "-project:CLO" "CLO"))))
    (testing "contradictions are refused, naming the clash"
      (is (= {:error "invalid_query" :token "label:bug" :suggestions ["kind:issue"]} (refusal "kind:page label:bug")))
      (is (= "`label:` applies only to issues." (:reason (rejection #(query/scope (query/parse "kind:page label:bug")
                                                                                  nil)))))
      (is (= "ready" (:token (refusal "-kind:issue ready"))))
      (is (= "archived:any" (:token (refusal "kind:issue archived:any"))))
      (is (= "archived:yes" (:token (refusal "status:open archived:yes"))))
      (is (= "sort:closed" (:token (refusal "kind:page sort:closed")))))))
