(ns lg-calendar.mapping
  "Canonical event <-> :cal/* datom mapping (ADR-2606010500 D3).

  Clojure port of lg_calendar/mapping.py. A canonical event is the
  `ai.etzhayyim.apps.calendar.defs#event` shape. In datomic it is one entity
  `cal:event:{slug}` with `cal/*` attributes; attendees and reminders are stored
  as canonical JSON strings (`cal/attendeesJson` / `cal/remindersJson`) for a
  single-pull round-trip.

  Events are string-keyed maps throughout (mirrors the Python dicts), so this port
  preserves byte-shape with the FastAPI handlers."
  (:require [cheshire.core :as json]
            [lg-calendar.ids :as ids]
            [lg-calendar.edn :as edn]))

;; Scalar canonical field -> bare datomic attribute. Order is STABLE (vector of
;; pairs), mirroring the Python ordered dict, so generated tx-ops are stable.
(def scalar-fields
  [["iCalUid" "cal/iCalUid"]
   ["googleEventId" "cal/googleEventId"]
   ["msEventId" "cal/msEventId"]
   ["calendarId" "cal/calendarId"]
   ["summary" "cal/summary"]
   ["description" "cal/description"]
   ["startsAt" "cal/startsAt"]
   ["endsAt" "cal/endsAt"]
   ["allDay" "cal/allDay"]
   ["timezone" "cal/timezone"]
   ["location" "cal/location"]
   ["url" "cal/url"]
   ["rrule" "cal/rrule"]
   ["visibility" "cal/visibility"]
   ["status" "cal/status"]
   ["sequence" "cal/sequence"]
   ["createdAtMs" "cal/createdAtMs"]
   ["updatedAtMs" "cal/updatedAtMs"]
   ["organizerDid" "cal/organizerDid"]])

(def json-fields
  [["attendees" "cal/attendeesJson"]
   ["reminders" "cal/remindersJson"]])

(def defaults
  {"allDay" false "visibility" "private" "status" "confirmed" "sequence" 0 "calendarId" "primary"})

(defn- json-dump
  "Compact JSON (no spaces, unicode preserved) — matches Python json.dumps
  separators=(',',':'), ensure_ascii=False."
  [arr]
  (json/generate-string arr))

(defn create-ops
  "Full asserting tx-ops for a brand-new event entity."
  [slug event]
  (let [eid (ids/eid-for-slug slug)
        base [(edn/tx-add eid "cal/type" "Event")
              (edn/tx-add eid "cal/id" eid)
              (edn/tx-add eid "cal/slug" slug)]
        scalars (for [[field attr] scalar-fields
                      :let [v (get event field (get defaults field))]
                      :when (some? v)]
                  (edn/tx-add eid attr v))
        jsons (for [[field attr] json-fields
                    :let [arr (or (get event field) [])]
                    :when (seq arr)]
                (edn/tx-add eid attr (json-dump arr)))]
    (vec (concat base scalars jsons))))

(defn update-ops
  "Delete-then-insert tx-ops for the changed fields only (cardinality-safe).

  Retracts the current value of each touched attribute (when present) and asserts
  the new one — avoids relying on a cardinality-one schema default."
  [slug current-attrs patch]
  (let [eid (ids/eid-for-slug slug)]
    (vec
     (concat
      (mapcat
       (fn [[field attr]]
         (if-not (contains? patch field)
           []
           (let [new-v (get patch field)
                 old-v (get current-attrs attr)]
             (if (= old-v new-v)
               []
               (cond-> []
                 (some? old-v) (conj (edn/tx-retract eid attr old-v))
                 (some? new-v) (conj (edn/tx-add eid attr new-v)))))))
       scalar-fields)
      (mapcat
       (fn [[field attr]]
         (if-not (contains? patch field)
           []
           (let [new-json (json-dump (or (get patch field) []))
                 old-json (get current-attrs attr)]
             (if (= old-json new-json)
               []
               (cond-> []
                 (some? old-json) (conj (edn/tx-retract eid attr old-json))
                 true (conj (edn/tx-add eid attr new-json)))))))
       json-fields)))))

(defn attrs-to-event
  "Reconstruct the canonical event map from a bare `cal/*` attr map."
  [attrs]
  (let [slug (or (get attrs "cal/slug")
                 (ids/slug-from-eid (get attrs "cal/id" "cal:event:unknown")))
        base {"did" (ids/did-for-slug slug)
              "uri" (ids/uri-for-slug slug)}
        with-scalars (reduce (fn [ev [field attr]]
                               (let [v (get attrs attr)]
                                 (if (some? v) (assoc ev field v) ev)))
                             base scalar-fields)
        with-json (reduce (fn [ev [field attr]]
                            (let [raw (get attrs attr)]
                              (if raw
                                (assoc ev field
                                       (let [parsed (if (string? raw)
                                                      (try (json/parse-string raw) (catch Exception _ []))
                                                      raw)]
                                         ;; vectorize so the list is index-addressable
                                         ;; (Python list <-> clj vector; cheshire yields a LazySeq)
                                         (if (sequential? parsed) (vec parsed) parsed)))
                                ev)))
                          with-scalars json-fields)]
    ;; ensure defaults surface for required-ish fields
    (reduce (fn [ev [k v]] (if (contains? ev k) ev (assoc ev k v)))
            with-json
            [["calendarId" (defaults "calendarId")]
             ["visibility" (defaults "visibility")]
             ["status" (defaults "status")]
             ["allDay" (defaults "allDay")]
             ["sequence" (defaults "sequence")]])))
