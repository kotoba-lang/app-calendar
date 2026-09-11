(ns lg-calendar.handlers
  "Canonical calendar method handlers (ai.etzhayyim.apps.calendar.*).

  Clojure port of lg_calendar/handlers.py. Storage-agnostic: each handler takes a
  lg-calendar.store/CalendarStore. The Cloudflare `calendar-compat` worker reshapes
  these canonical results into Google Calendar v3 / Microsoft Graph JSON; the
  handlers themselves are the SSoT for behavior (concurrency, not-found, pagination).

  Events / inputs are string-keyed maps throughout (mirrors the Python dicts)."
  (:require [lg-calendar.ids :as ids]
            [lg-calendar.mapping :as mapping]
            [lg-calendar.edn :as edn]
            [lg-calendar.store :as store]))

(defn- now-ms [] (System/currentTimeMillis))

(defn- resolve-event
  "Resolve [slug attrs] from a caller id (slug/eid/DID), iCalUid, or provider id.
  Returns [nil nil] on miss."
  ([store-impl event-id] (resolve-event store-impl event-id nil))
  ([store-impl event-id ical-uid]
   (let [slug (ids/resolve-slug (or event-id ""))]
     (if-let [attrs (and slug (store/get-event-attrs store-impl slug))]
       [slug attrs]
       (loop [pairs [["cal/iCalUid" ical-uid]
                     ["cal/iCalUid" event-id]
                     ["cal/googleEventId" event-id]
                     ["cal/msEventId" event-id]]]
         (if-let [[attr val] (first pairs)]
           (if (or (nil? val) (= "" val))
             (recur (rest pairs))
             (if-let [found (store/lookup-slug store-impl attr val)]
               (if-let [attrs (store/get-event-attrs store-impl found)]
                 [found attrs]
                 (recur (rest pairs)))
               (recur (rest pairs))))
           [nil nil]))))))

;; ── createEvent ───────────────────────────────────────────────────────────────

(defn create-event [store-impl inp]
  (let [slug (ids/new-slug)
        now (now-ms)
        ical (or (get inp "iCalUid") (ids/ical-uid-for-slug slug))
        base {"calendarId" (get inp "calendarId" "primary")
              "iCalUid" ical
              "summary" (get inp "summary")
              "startsAt" (get inp "startsAt")
              "allDay" (boolean (get inp "allDay" false))
              "visibility" (get inp "visibility" "private")
              "status" "confirmed"
              "sequence" 0
              "createdAtMs" now
              "updatedAtMs" now
              "attendees" (get inp "attendees" [])
              "reminders" (get inp "reminders" [])}
        event (reduce (fn [ev opt]
                        (if (some? (get inp opt)) (assoc ev opt (get inp opt)) ev))
                      base
                      ["description" "endsAt" "timezone" "location" "url" "rrule"
                       "googleEventId" "msEventId" "organizerDid"])]
    (store/write-ops store-impl (mapping/create-ops slug event))
    (let [did (ids/did-for-slug slug)
          uri (ids/uri-for-slug slug)
          event (assoc event "did" did "uri" uri)]
      {"did" did "uri" uri "iCalUid" ical "event" event})))

;; ── getEvent ──────────────────────────────────────────────────────────────────

(defn get-event [store-impl params]
  (let [[_ attrs] (resolve-event store-impl (get params "eventId") (get params "iCalUid"))]
    (if-not attrs
      {"found" false}
      {"found" true "event" (mapping/attrs-to-event attrs)})))

;; ── listEvents ────────────────────────────────────────────────────────────────

(defn- ->int [v default]
  (cond (nil? v) default (integer? v) v :else (parse-long (str v))))

(defn list-events [store-impl params]
  (let [calendar-id (get params "calendarId" "primary")
        starts-after (get params "startsAfter")
        starts-before (get params "startsBefore")
        visibility (get params "visibility")
        attendee-did (get params "attendeeDid")
        order-by (get params "orderBy" "startsAt")
        offset (->int (get params "offset") 0)
        limit (->int (get params "limit") 50)
        events (mapv mapping/attrs-to-event (store/all-event-attrs store-impl))
        keep? (fn [ev]
                (and
                 (or (not calendar-id) (= (get ev "calendarId" "primary") calendar-id))
                 (let [s (get ev "startsAt" "")]
                   (and (or (not starts-after) (not (neg? (compare s starts-after))))
                        (or (not starts-before) (neg? (compare s starts-before)))))
                 (or (not visibility) (= (get ev "visibility") visibility))
                 (or (not attendee-did)
                     (some (fn [a] (= (get a "did") attendee-did)) (get ev "attendees" [])))))
        filtered (filterv keep? events)
        key (if (= order-by "updatedAtMs") "updatedAtMs" "startsAt")
        ;; key = (nil? v, v) — mirrors Python's (v is None, v); nil-valued rows sort
        ;; last (true > false) and never get compared against a non-nil value.
        sorted-events (sort-by (fn [e] [(nil? (get e key)) (get e key)]) filtered)
        page (->> sorted-events (drop offset) (take limit) vec)]
    {"events" page "total" (count filtered) "offset" offset "limit" limit}))

;; ── updateEvent ───────────────────────────────────────────────────────────────

(defn update-event [store-impl inp]
  (let [[slug attrs] (resolve-event store-impl (get inp "eventId"))]
    (cond
      (not attrs) {"ok" false "notFound" true}
      (and (contains? inp "ifSequence") (some? (get inp "ifSequence"))
           (not= (get attrs "cal/sequence") (get inp "ifSequence")))
      {"ok" false "conflict" true}
      :else
      (let [patch (reduce (fn [p f]
                            (if (and (contains? inp f) (some? (get inp f)))
                              (assoc p f (get inp f)) p))
                          {}
                          ["summary" "description" "startsAt" "endsAt" "allDay" "timezone"
                           "location" "url" "rrule" "visibility" "status" "attendees" "reminders"])
            patch (assoc patch
                         "sequence" (inc (int (get attrs "cal/sequence" 0)))
                         "updatedAtMs" (now-ms))]
        (store/write-ops store-impl (mapping/update-ops slug attrs patch))
        (let [new-attrs (or (store/get-event-attrs store-impl slug) attrs)]
          {"ok" true "event" (mapping/attrs-to-event new-attrs)})))))

;; ── deleteEvent ───────────────────────────────────────────────────────────────

(defn delete-event [store-impl inp]
  (let [[slug attrs] (resolve-event store-impl (get inp "eventId"))]
    (cond
      (not attrs) {"ok" false "notFound" true}
      (and (contains? inp "ifSequence") (some? (get inp "ifSequence"))
           (not= (get attrs "cal/sequence") (get inp "ifSequence")))
      {"ok" false "conflict" true}
      :else
      (do (store/write-ops store-impl [(edn/tx-retract-entity (ids/eid-for-slug slug))])
          {"ok" true}))))

;; ── rsvp ──────────────────────────────────────────────────────────────────────

(defn rsvp [store-impl inp]
  (let [[slug attrs] (resolve-event store-impl (get inp "eventId"))]
    (if-not attrs
      {"ok" false "notFound" true}
      (let [event (mapping/attrs-to-event attrs)
            attendees (get event "attendees" [])
            respondent-did (get inp "respondentDid")
            respondent-email (get inp "respondentEmail")
            response (get inp "response")
            match? (fn [a]
                     (or (and respondent-did (= (get a "did") respondent-did))
                         (and respondent-email (= (get a "email") respondent-email))))
            matched (some match? attendees)
            attendees' (if matched
                         (mapv (fn [a] (if (match? a) (assoc a "responseStatus" response) a)) attendees)
                         (conj (vec attendees)
                               {"did" respondent-did
                                "email" respondent-email
                                "responseStatus" response}))
            patch {"attendees" attendees'
                   "sequence" (inc (int (get attrs "cal/sequence" 0)))
                   "updatedAtMs" (now-ms)}]
        (store/write-ops store-impl (mapping/update-ops slug attrs patch))
        {"ok" true "eventId" slug "response" response}))))

;; ── listCalendars ─────────────────────────────────────────────────────────────

(defn list-calendars [_store-impl params]
  (let [offset (->int (get params "offset") 0)
        limit (->int (get params "limit") 50)
        calendars [{"calendarId" "primary"
                    "summary" "Primary"
                    "primary" true
                    "accessRole" "owner"}]
        page (->> calendars (drop offset) (take limit) vec)]
    {"calendars" page "total" (count calendars) "offset" offset "limit" limit}))
