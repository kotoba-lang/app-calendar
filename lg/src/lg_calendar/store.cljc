(ns lg-calendar.store
  "Calendar persistence stores (Clojure port of lg_calendar/store.py).

  `CalendarStore` is the narrow interface the handlers use. Two implementations:

  - `KotobaCalendarStore` — production, backed by kotoba datomic (graph
    `calendar-v1`) via lg-calendar.kotoba-datomic.
  - `FakeCalendarStore` — in-memory atom, used by the unit/smoke tests so the
    canonical handler logic is verified deterministically without a live pod.

  Both speak the same EDN tx-op vocabulary (`[:db/add ...]` / `[:db/retract ...]`
  / `[:db.fn/retractEntity ...]`), so the handlers are storage-agnostic.

  Port note: the Python methods are async; this clj port is synchronous (no event
  loop), which is the only behavioral deviation."
  (:require [clojure.string :as str]
            [lg-calendar.ids :as ids]
            [lg-calendar.edn :as edn]
            [lg-calendar.kotoba-datomic :as kd]))

(defprotocol CalendarStore
  (get-event-attrs [store slug])
  (all-event-attrs [store])
  (lookup-slug [store attr value])
  (write-ops [store ops]))

;; ── kotoba datomic implementation ─────────────────────────────────────────────

(defrecord KotobaCalendarStore [dm]
  CalendarStore
  (get-event-attrs [_ slug]
    (kd/pull dm (ids/eid-for-slug slug)))

  (all-event-attrs [this]
    (let [rows (kd/q dm "[:find ?slug :where [?e :cal/type \"Event\"] [?e :cal/slug ?slug]]")]
      (vec (keep (fn [row]
                   (when (seq row)
                     (get-event-attrs this (str (first row)))))
                 rows))))

  (lookup-slug [_ attr value]
    ;; Inline the EDN-encoded value into the query (proven yatabase get_entity
    ;; pattern: `[?e :kg/qid "{qid}"]`), rather than an `:in $ ?v` binding.
    (let [bare (if (str/starts-with? attr ":") (subs attr 1) attr)
          query (str "[:find ?slug :where [?e :" bare " " (edn/encode value) "] [?e :cal/slug ?slug]]")
          rows (kd/q dm query)]
      (when (and (seq rows) (seq (first rows)))
        (str (ffirst rows)))))

  (write-ops [_ ops]
    (when (seq ops)
      (kd/transact dm ops))))

(defn kotoba-calendar-store [dm] (->KotobaCalendarStore dm))

;; ── in-memory fake (tests) ────────────────────────────────────────────────────

(defn- bare [a]
  (let [s (str a)]
    (if (str/starts-with? s ":") (subs s 1) s)))

(defrecord FakeCalendarStore [db]
  CalendarStore
  (get-event-attrs [_ slug]
    (get @db slug))

  (all-event-attrs [_]
    (vec (vals @db)))

  (lookup-slug [_ attr value]
    (let [b (if (str/starts-with? attr ":") (subs attr 1) attr)]
      (some (fn [[slug attrs]] (when (= (get attrs b) value) slug)) @db)))

  (write-ops [_ ops]
    (doseq [op ops]
      (let [kind (str (first op))]
        (if (= kind ":db.fn/retractEntity")
          (let [slug (ids/slug-from-eid (str (nth op 1)))]
            (swap! db dissoc slug))
          (let [eid (nth op 1) attr (str (nth op 2)) value (nth op 3)
                b (bare attr)
                slug (ids/slug-from-eid (str eid))]
            (cond
              (= kind ":db/add")
              (swap! db update slug assoc b value)
              (= kind ":db/retract")
              (swap! db update slug
                     (fn [row] (if (= (get row b) value) (dissoc row b) row))))))))))

(defn fake-calendar-store [] (->FakeCalendarStore (atom {})))
