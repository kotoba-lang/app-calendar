(ns lg-calendar.test-handlers
  "Deterministic canonical-handler tests using the in-memory FakeCalendarStore.

  Clojure port of tests/test_handlers.py. Verifies the SSoT behaviors the compat
  skins depend on: create->read round-trip, list filtering + offset/limit/total
  pagination, optimistic-concurrency (ifSequence) on update/delete, not-found,
  rsvp, and provider-id lookup — all without a live kotoba pod."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [lg-calendar.handlers :as handlers]
            [lg-calendar.store :as store]))

(defn- store [] (store/fake-calendar-store))

(defn- last-seg [uri] (last (str/split uri #"/")))

(deftest test-create-get-roundtrip
  (let [st (store)
        res (handlers/create-event st {"summary" "Standup"
                                       "startsAt" "2026-06-02T09:00:00Z"
                                       "endsAt" "2026-06-02T09:15:00Z"
                                       "timezone" "Asia/Tokyo"
                                       "attendees" [{"email" "a@example.com" "responseStatus" "needsAction"}]})]
    (is (str/starts-with? (get res "did") "did:web:calendar.etzhayyim.com:event:"))
    (is (str/ends-with? (get res "iCalUid") "@calendar.etzhayyim.com"))
    (is (= 0 (get-in res ["event" "sequence"])))
    (let [got (handlers/get-event st {"eventId" (last-seg (get-in res ["event" "uri"]))})]
      (is (= true (get got "found")))
      (is (= "Standup" (get-in got ["event" "summary"])))
      (is (= "2026-06-02T09:00:00Z" (get-in got ["event" "startsAt"])))
      (is (= "a@example.com" (get-in got ["event" "attendees" 0 "email"]))))))

(deftest test-get-missing-returns-not-found
  (is (= {"found" false} (handlers/get-event (store) {"eventId" "doesnotexist0001"}))))

(deftest test-lookup-by-ical-uid-and-provider-id
  (let [st (store)
        res (handlers/create-event st {"summary" "Imported"
                                       "startsAt" "2026-06-03T10:00:00Z"
                                       "iCalUid" "abc-123@google.com"
                                       "googleEventId" "gcal_evt_999"})
        by-ical (handlers/get-event st {"eventId" "x" "iCalUid" "abc-123@google.com"})
        by-gid (handlers/get-event st {"eventId" "gcal_evt_999"})]
    (is (= true (get by-ical "found")))
    (is (= true (get by-gid "found")))
    (is (= "Imported" (get-in by-gid ["event" "summary"])))
    (is (= "gcal_evt_999" (get-in res ["event" "googleEventId"])))))

(deftest test-list-filter-and-pagination
  (let [st (store)]
    (doseq [i (range 5)]
      (handlers/create-event st {"summary" (str "E" i)
                                 "startsAt" (str "2026-06-1" i "T08:00:00Z")}))
    (let [page1 (handlers/list-events st {"offset" 0 "limit" 2})]
      (is (= 5 (get page1 "total")))
      (is (= 0 (get page1 "offset")))
      (is (= 2 (get page1 "limit")))
      (is (= 2 (count (get page1 "events"))))
      (is (= "E0" (get-in page1 ["events" 0 "summary"]))))   ; sorted by startsAt asc
    (let [page3 (handlers/list-events st {"offset" 4 "limit" 2})]
      (is (= 1 (count (get page3 "events")))))
    (let [ranged (handlers/list-events st {"startsAfter" "2026-06-12T00:00:00Z"
                                           "startsBefore" "2026-06-14T00:00:00Z"})]
      (is (= #{"E2" "E3"} (set (map #(get % "summary") (get ranged "events"))))))))

(deftest test-update-optimistic-concurrency
  (let [st (store)
        res (handlers/create-event st {"summary" "v0" "startsAt" "2026-06-02T09:00:00Z"})
        slug (last-seg (get-in res ["event" "uri"]))
        ok (handlers/update-event st {"eventId" slug "ifSequence" 0 "summary" "v1"})]
    (is (= true (get ok "ok")))
    (is (= "v1" (get-in ok ["event" "summary"])))
    (is (= 1 (get-in ok ["event" "sequence"])))
    (is (= {"ok" false "conflict" true}
           (handlers/update-event st {"eventId" slug "ifSequence" 0 "summary" "v2"})))
    (is (= {"ok" false "notFound" true}
           (handlers/update-event st {"eventId" "nope0001" "summary" "x"})))))

(deftest test-delete-concurrency-and-removal
  (let [st (store)
        res (handlers/create-event st {"summary" "del" "startsAt" "2026-06-02T09:00:00Z"})
        slug (last-seg (get-in res ["event" "uri"]))]
    (is (= {"ok" false "conflict" true}
           (handlers/delete-event st {"eventId" slug "ifSequence" 9})))
    (is (= {"ok" true} (handlers/delete-event st {"eventId" slug "ifSequence" 0})))
    (is (= {"found" false} (handlers/get-event st {"eventId" slug})))))

(deftest test-rsvp-updates-attendee
  (let [st (store)
        res (handlers/create-event st {"summary" "party"
                                       "startsAt" "2026-06-02T09:00:00Z"
                                       "attendees" [{"did" "did:web:alice.etzhayyim.com" "responseStatus" "needsAction"}]})
        slug (last-seg (get-in res ["event" "uri"]))
        r (handlers/rsvp st {"eventId" slug "respondentDid" "did:web:alice.etzhayyim.com" "response" "accepted"})]
    (is (= true (get r "ok")))
    (is (= "accepted" (get r "response")))
    (let [got (handlers/get-event st {"eventId" slug})]
      (is (= "accepted" (get-in got ["event" "attendees" 0 "responseStatus"])))
      (is (= 1 (get-in got ["event" "sequence"]))))))

(deftest test-list-calendars
  (let [res (handlers/list-calendars (store) {})]
    (is (= 1 (get res "total")))
    (is (= "primary" (get-in res ["calendars" 0 "calendarId"])))
    (is (= true (get-in res ["calendars" 0 "primary"])))))
