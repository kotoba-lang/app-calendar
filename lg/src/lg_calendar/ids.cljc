(ns lg-calendar.ids
  "Identity helpers for calendar events (Clojure port of lg_calendar/ids.py).

  Canonical entity id = `cal:event:{slug}` (the datomic entity ref).
  Path-based DID    = `did:web:calendar.etzhayyim.com:event:{slug}`.
  AT URI            = `at://{did}/ai.etzhayyim.apps.calendar.event/{slug}`.

  Faithful-port note: the Python `nanoid` generator (deployed) falls back to
  stdlib `secrets`; this clj port uses a `java.security.SecureRandom` draw over
  the same 36-char alphabet — equivalent entropy, no nanoid dependency."
  (:require [clojure.string :as str]))

(def ^:private slug-alphabet "0123456789abcdefghijklmnopqrstuvwxyz")
(def ^:private domain "calendar.etzhayyim.com")
(def ^:private collection "ai.etzhayyim.apps.calendar.event")

(def ^:private secure-random (java.security.SecureRandom.))

(defn new-slug []
  (let [n (count slug-alphabet)]
    (apply str (repeatedly 16 #(nth slug-alphabet (.nextInt secure-random n))))))

(defn eid-for-slug [slug] (str "cal:event:" slug))

(defn slug-from-eid [eid] (last (str/split eid #":")))

(defn did-for-slug [slug] (str "did:web:" domain ":event:" slug))

(defn uri-for-slug [slug] (str "at://" (did-for-slug slug) "/" collection "/" slug))

(defn ical-uid-for-slug [slug] (str slug "@" domain))

(def ^:private slug-re #"^[0-9a-z]{6,32}$")

(defn resolve-slug
  "Resolve a caller-supplied id (slug, eid, or DID) to a bare slug.

  Returns nil when the input is a provider-native or iCalUid form that needs a
  datomic lookup instead (handled by the store)."
  [event-id]
  (cond
    (or (nil? event-id) (= "" event-id)) nil
    (str/starts-with? event-id "cal:event:") (slug-from-eid event-id)
    (and (str/starts-with? event-id "did:web:") (str/includes? event-id ":event:"))
    (last (str/split event-id #":event:"))
    (re-matches slug-re event-id) event-id
    :else nil))
