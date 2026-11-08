(ns cloisters.query
  "The query grammar every surface shares: one text syntax, `ready label:bug -assignee:none`, parsed into clauses and
  printed back losslessly. Filters AND together, values within one filter OR together, and a leading `-` negates a
  whole filter. There are no parentheses.

  A parsed query is a vector of clauses, in the order written:

  - {:name \"label\" :negated false :values [\"bug\" \"ux\"]}  a filter
  - {:name \"ready\" :negated false}                          a bare predicate

  Parsing is strict: an unknown filter, a bad value or a malformed token refuses the whole query with invalid_query,
  naming the token and suggesting what would be valid. This namespace knows the grammar only; cloisters.search runs
  queries against the store."
  (:require [cloisters.core :refer [fail]]
            [clojure.string :as str])
  (:import (java.time Duration Instant LocalDate LocalDateTime OffsetDateTime ZoneId)
           (java.time.format DateTimeParseException)))

;; ---------------------------------------------------------------- the grammar

(def kinds #{"issue" "page"})

(def sorts ["rank" "created" "updated" "closed"])

(def filters
  "Each filter: the type of its values, which kind it applies to (nil for both), and its restrictions. :single takes
  one value, :once may appear once, :fixed cannot be negated."
  (merge
   {"kind" {:type :enum :values kinds}
    "project" {:type :project}
    "id" {:type :id}
    "parent" {:type :id}
    "under" {:type :id}
    "mentions" {:type :id}
    "mentioned-by" {:type :id}
    "text" {:type :text}
    "archived" {:type :enum :values #{"no" "yes" "any"} :applies "page" :single true :once true :fixed true}
    "status" {:type :enum :values #{"open" "closed"} :applies "issue"}
    "resolution" {:type :enum :values #{"done" "dropped"} :applies "issue"}
    "assignee" {:type :assignee :applies "issue"}
    "label" {:type :label :applies "issue"}
    "blocks" {:type :id :applies "issue"}
    "blocked-by" {:type :id :applies "issue"}
    "sort" {:type :sort :single true :once true :fixed true}}
   (into {} (for [field ["created" "updated" "closed" "claimed"]
                  edge ["before" "after"]]
              [(str field "-" edge) (cond-> {:type :time :single true}
                                      (#{"closed" "claimed"} field) (assoc :applies "issue"))]))))

(def predicates
  "Bare predicates. ready is exactly status:open unblocked assignee:none."
  {"ready" {:applies "issue"}
   "blocked" {:applies "issue"}
   "unblocked" {:applies "issue"}})

;; ---------------------------------------------------------------- suggestions

(defn- distance
  "The Levenshtein distance between a and b."
  [^String a ^String b]
  (let [n (count b)]
    (peek (reduce (fn [prev [i ca]]
                    (reduce (fn [row j]
                              (conj row (min (inc (peek row))
                                             (inc (nth prev (inc j)))
                                             (+ (nth prev j) (if (= ca (.charAt b j)) 0 1)))))
                            [(inc i)]
                            (range n)))
                  (vec (range (inc n)))
                  (map-indexed vector a)))))

(defn- closest
  "The candidates near word, nearest first: within two edits, or starting with it."
  [word candidates]
  (let [w (str/lower-case word)]
    (->> candidates
         (keep #(let [d (distance w %)]
                  (when (or (<= d 2) (and (seq w) (str/starts-with? % w))) [d %])))
         sort
         (mapv second))))

(defn- refuse
  "Refuses the query for token, saying why, with what would be valid instead."
  [token reason suggestions]
  (fail "invalid_query"
        (str "Invalid query at `" token "`: " reason
             (when (seq suggestions) (str " Try: " (str/join ", " (map #(str "`" % "`") suggestions)) ".")))
        :token token :reason reason :suggestions (vec suggestions)))

;; ---------------------------------------------------------------- tokens

(defn- tokens
  "The query split on unquoted whitespace. Each token is a vector of [char quoted?]: double quotes group text, and
  inside them a backslash escapes the next character."
  [^String s]
  (loop [i 0 quoted false token [] out []]
    (if (= i (count s))
      (do (when quoted
            (refuse (subs s (max 0 (- i (count token) 1))) "a double quote is never closed." []))
          (cond-> out (seq token) (conj token)))
      (let [c (.charAt s i)]
        (cond
          (and quoted (= c \\) (< (inc i) (count s))) (recur (+ i 2) true (conj token [(.charAt s (inc i)) true]) out)
          (= c \") (recur (inc i) (not quoted) (cond-> token (and quoted (empty? token)) (conj [nil true])) out)
          quoted (recur (inc i) true (conj token [c true]) out)
          (Character/isWhitespace c) (recur (inc i) false [] (cond-> out (seq token) (conj token)))
          :else (recur (inc i) false (conj token [c false]) out))))))

(defn- token-text
  "A token as the reader wrote it, near enough to find it in their query."
  [token]
  (apply str (map (fn [[c quoted]] (if quoted (str "\"" c "\"") c)) token)))

(defn- chars-str [cs] (apply str (keep first cs)))

(defn- split-unquoted [token c]
  (loop [token token part [] parts []]
    (if-let [[[ch quoted :as x] & more] (seq token)]
      (if (and (= ch c) (not quoted))
        (recur more [] (conj parts part))
        (recur more (conj part x) parts))
      (conj parts part))))

;; ---------------------------------------------------------------- values

(def ^:private id-re #"[A-Z][A-Z0-9]{1,9}-[1-9][0-9]{0,17}")
(def ^:private key-re #"[A-Z][A-Z0-9]{1,9}")
(def ^:private duration-re #"([1-9][0-9]{0,8})([mhd])")

(defn- wildcard-ok!
  "A value may end in * (a prefix match) but holds no other *."
  [token v]
  (when (str/includes? (subs v 0 (max 0 (dec (count v)))) "*")
    (refuse token (str "`*` matches a prefix only at the end of a value, as in `" (str/replace v "*" "") "*`.") [])))

(defn- time-value
  "A time value's instant: a relative duration (30m, 2h, 3d: that long before now), an ISO date (its midnight in
  zone) or an ISO date-time (in zone unless it carries an offset). nil when value is none of these."
  [^String v ^Instant now ^ZoneId zone]
  (if-let [[_ n unit] (re-matches duration-re v)]
    (.minus now (case unit
                  "m" (Duration/ofMinutes (parse-long n))
                  "h" (Duration/ofHours (parse-long n))
                  "d" (Duration/ofDays (parse-long n))))
    (some (fn [parse] (try (parse) (catch DateTimeParseException _ nil)))
          [#(.toInstant (.atStartOfDay (LocalDate/parse v) zone))
           #(.toInstant (OffsetDateTime/parse v))
           #(.toInstant (.atZone (LocalDateTime/parse v) zone))])))

(defn instant-of
  "The instant a time filter's value names, measured from now in zone."
  [v now zone]
  (time-value v now zone))

(defn- check-value! [token name {:keys [type values]} v]
  (when (= "" v)
    (refuse token (str "`" name ":` has an empty value.") []))
  (case type
    :enum (when-not (contains? values v)
            (refuse token (str "`" name ":` takes " (str/join ", " (sort values)) ".")
                    (map #(str name ":" %) (or (seq (closest v values)) (sort values)))))
    :id (when-not (re-matches id-re v)
          (refuse token (str "`" name ":` takes issue or page ids, like CLO-7.")
                  (when (re-matches id-re (str/upper-case v)) [(str name ":" (str/upper-case v))])))
    :project (when-not (or (= "*" v) (re-matches key-re v))
               (refuse token "`project:` takes project keys, like CLO, or `*` for every project."
                       (when (re-matches key-re (str/upper-case v)) [(str "project:" (str/upper-case v))])))
    (:label :assignee) (wildcard-ok! token v)
    :time (when-not (time-value v (Instant/now) (ZoneId/of "UTC"))
            (refuse token (str "`" name ":` takes a duration ago (30m, 2h, 3d) or an ISO date or date-time "
                               "(2026-10-01, 2026-10-01T09:00, 2026-10-01T09:00Z).")
                    []))
    :sort (when-not (re-matches #"(rank|created|updated|closed)(-asc|-desc)?" v)
            (refuse token "`sort:` takes rank, created, updated or closed, each with -asc or -desc."
                    (map #(str "sort:" %) (or (seq (closest (str/replace v #"-(asc|desc)$" "") sorts)) sorts))))
    :text nil))

;; ---------------------------------------------------------------- parsing

(defn- predicate-clause [text negated name]
  (if (predicates name)
    {:name name :negated negated}
    (refuse text (if (filters name)
                   (str "`" name "` is a filter: give it a value, as in `" name ":…`.")
                   (str "`" name "` is not a predicate or filter."))
            (concat (closest name (concat (keys predicates) (keys filters)))
                    (when-not (filters name) [(str "text:" name)])))))

(defn- filter-clause [text negated name value-chars]
  (let [{:keys [single fixed] :as spec}
        (or (filters name)
            (refuse text (str "`" name ":` is not a filter.")
                    (map #(str % ":") (closest name (keys filters)))))
        values (mapv chars-str (split-unquoted value-chars \,))]
    (when (and negated fixed)
      (refuse text (str "`" name ":` cannot be negated.") []))
    (when (and single (next values))
      (refuse text (str "`" name ":` takes one value.") []))
    (doseq [v values] (check-value! text name spec v))
    (when (and (= "project" name) (some #{"*"} values) (or negated (next values)))
      (refuse text "`project:*` means every project, so it stands alone and cannot be negated." ["project:*"]))
    {:name name :negated negated :values values}))

(defn- clause [token]
  (let [text (token-text token)
        [[c quoted]] token
        negated (and (= \- c) (not quoted))
        token (if negated (subvec token 1) token)
        [head & more] (split-unquoted token \:)
        name (chars-str head)]
    (when (or (empty? token) (empty? name) (some second head))
      (refuse text "every term is a predicate (`ready`) or a filter with a value (`label:bug`)." []))
    (if more
      (filter-clause text negated name (vec (drop (inc (count head)) token)))
      (predicate-clause text negated name))))

(defn parse
  "The clauses of a query string. An empty query is valid and matches everything in scope."
  [s]
  (let [clauses (mapv clause (tokens (or s "")))]
    (doseq [[name n] (frequencies (map :name clauses))
            :when (and (> n 1) (:once (filters name)))]
      (refuse (str name ":") (str "`" name ":` may appear once.") []))
    clauses))

;; ---------------------------------------------------------------- printing

(defn- quote-value [v]
  (if (or (= "" v) (re-find #"[\s,\"\\]" v))
    (str "\"" (str/replace v #"[\"\\]" #(str "\\" %)) "\"")
    v))

(defn- print-clause [{:keys [name negated values]}]
  (str (when negated "-") name (when values (str ":" (str/join "," (map quote-value values))))))

(defn print-query
  "Clauses as a query string that parses back to the same clauses."
  [clauses]
  (str/join " " (map print-clause clauses)))

;; ---------------------------------------------------------------- scope

(defn- applies
  "The kind a clause applies only to, or nil when it applies to both."
  [{:keys [name values]}]
  (or (:applies (filters name))
      (:applies (predicates name))
      (when (and (= "sort" name) (str/starts-with? (first values) "closed")) "issue")))

(defn- allowed-kinds
  "The kinds the query's kind: clauses admit."
  [clauses]
  (reduce (fn [ks {:keys [negated values]}]
            (let [vs (set values)]
              (if negated (apply disj ks vs) (into #{} (filter vs) ks))))
          kinds
          (filter #(= "kind" (:name %)) clauses)))

(defn- only [kind] (if (= "page" kind) "pages" "issues"))

(defn scope
  "Resolves which kinds a query can match, refusing contradictions by naming the clash, and its effective form: the
  clauses with the bound project (when it names none) and any implied kind made explicit, as the query that ran.

  An issue-only filter implies kind:issue, and archived: implies kind:page. Returns :clauses (effective), :kinds and
  :effective (the effective query string)."
  [clauses bound-project]
  (let [explicit (some #(= "kind" (:name %)) clauses)
        by-kind (group-by applies (remove #(= "kind" (:name %)) clauses))
        [issue-only page-only] [(first (by-kind "issue")) (first (by-kind "page"))]
        allowed (allowed-kinds clauses)]
    (when (and issue-only page-only)
      (refuse (print-clause page-only)
              (str "`" (:name page-only) ":` applies only to pages, and `" (:name issue-only)
                   (if (:values issue-only) ":" "") "` only to issues; no item is both.")
              []))
    (doseq [[kind c] [["issue" issue-only] ["page" page-only]]
            :when (and c (not (allowed kind)))]
      (refuse (print-clause c) (str "`" (:name c) (if (:values c) ":" "") "` applies only to " (only kind) ".")
              [(str "kind:" kind)]))
    (let [implied (cond issue-only "issue" page-only "page")
          kinds (if implied (conj #{} implied) allowed)
          clauses (cond->> clauses
                    (and implied (not explicit)) (into [{:name "kind" :negated false :values [implied]}])
                    (and bound-project (not (some #(= "project" (:name %)) clauses)))
                    (into [{:name "project" :negated false :values [bound-project]}]))]
      {:clauses clauses :kinds (into #{} (filter kinds) allowed) :effective (print-query clauses)})))
