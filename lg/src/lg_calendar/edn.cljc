(ns lg-calendar.edn
  "Minimal Clojure -> EDN encoder + decoder helpers for kotoba datomic.

  Clojure port of lg_calendar/edn.py (which was itself ported from lg_hakken).
  Targets the subset kotoba-server `kotoba_edn::parse` understands for
  `datomic.transact` tx-data: a vector of `[:db/add E A V]` / `[:db/retract E A V]`
  ops and entity maps. Strings are escaped per EDN spec.

  Faithful-port note: the Python `EdnSymbol` (bare symbol, no quoting) maps to a
  native Clojure keyword here — `(str :db/add)` already yields `:db/add`, so tx-op
  attributes/verbs are plain keywords and `encode` prints a keyword verbatim."
  (:require [clojure.string :as str]))

(defn kw
  "Keyword shortcut: (kw \"cal/summary\") -> :cal/summary, (kw \"db/add\") -> :db/add.
  A leading colon is tolerated (kw \":db/add\") -> :db/add."
  [name]
  (keyword (if (str/starts-with? name ":") (subs name 1) name)))

(defn- encode-str [s]
  (let [sb (StringBuilder.)]
    (.append sb \")
    (doseq [ch s]
      (case ch
        \\ (.append sb "\\\\")
        \" (.append sb "\\\"")
        \newline (.append sb "\\n")
        \return (.append sb "\\r")
        \tab (.append sb "\\t")
        (.append sb ch)))
    (.append sb \")
    (.toString sb)))

(declare encode)

(defn- encode-map [m]
  (str "{"
       (str/join " "
                 (mapcat (fn [[k v]]
                           [(encode (if (string? k) (kw k) k)) (encode v)])
                         m))
       "}"))

(defn encode
  "Encode a Clojure value as an EDN string (the subset kotoba understands)."
  [value]
  (cond
    (keyword? value) (str value)
    (nil? value) "nil"
    (boolean? value) (if value "true" "false")
    (integer? value) (str value)
    (float? value) (pr-str value)
    (string? value) (encode-str value)
    (set? value) (str "#{" (str/join " " (map encode value)) "}")
    (sequential? value) (str "[" (str/join " " (map encode value)) "]")
    (map? value) (encode-map value)
    :else (throw (ex-info (str "unsupported EDN value: " (type value)) {:value value}))))

(defn- attr-kw
  "Normalize a bare/colon-prefixed attribute string to a keyword (:cal/summary)."
  [a]
  (if (str/starts-with? a ":") (kw a) (kw a)))

(defn tx-add
  "`[:db/add <e> <a> <v>]` tx op."
  [e a v]
  [(kw "db/add") e (attr-kw a) v])

(defn tx-retract
  "`[:db/retract <e> <a> <v>]` tx op."
  [e a v]
  [(kw "db/retract") e (attr-kw a) v])

(defn tx-retract-entity
  "`[:db.fn/retractEntity <e>]` — atomic full-entity delete (hard delete)."
  [e]
  [(kw "db.fn/retractEntity") e])

(defn encode-tx-data
  "Encode a sequence of tx-ops as a single EDN vector string."
  [ops]
  (str "[" (str/join " " (map encode ops)) "]"))

;; ── EDN scalar decode (server returns rows / datom values as EDN strings) ──────

(def ^:private int-re #"^-?\d+$")
(def ^:private float-re #"^-?\d+\.\d+([eE][+-]?\d+)?$")

(defn parse-edn-value
  "Decode a single EDN scalar string to a Clojure value (tolerant)."
  [s]
  (if-not (string? s)
    s
    (cond
      (and (str/starts-with? s "\"") (str/ends-with? s "\"") (>= (count s) 2))
      (-> (subs s 1 (dec (count s)))
          (str/replace "\\\"" "\"")
          (str/replace "\\\\" "\\")
          (str/replace "\\n" "\n"))
      (= s "true") true
      (= s "false") false
      (= s "nil") nil
      (re-matches int-re s) (parse-long s)
      (re-matches float-re s) (parse-double s)
      :else s)))
