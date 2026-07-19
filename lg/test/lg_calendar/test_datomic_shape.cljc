(ns lg-calendar.test-datomic-shape
  "Lock the datomic read path to the live-verified kotoba pull shape.

  Clojure port of tests/test_datomic_shape.py. kotoba's datomic.pull returns
  `datoms` as maps with `a` (attribute, leading colon), `v_edn` (EDN-encoded value
  string), and `added` (bool). These tests feed that real shape through our reader
  + the canonical event reconstruction, verifying the KotobaCalendarStore read path
  against the documented contract (not a guessed shape), without a live pod."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [lg-calendar.mapping :as mapping]
            [lg-calendar.graphs.health :as health]
            [lg-calendar.server :as server]
            [lg-calendar.kotoba-datomic :as kd]))

(deftest explicit-kotoba-capability-and-config
  (let [request (atom nil)
        dm (kd/make-kotoba-datomic {:xrpc-url "http://kotoba.internal/"
                                    :bearer "bound" :graph-label "calendar-safe"})]
    (binding [kd/*http-post* (fn [url opts]
                               (reset! request [url opts])
                               {:status 200 :body "{\"rows_edn\":[]}"})]
      (is (= [] (kd/q dm "[:find ?e]")))
      (is (= "http://kotoba.internal/xrpc/ai.etzhayyim.apps.kotoba.datomic.q"
             (first @request)))
      (is (= "Bearer bound" (get-in @request [1 :headers "Authorization"]))))))

(deftest explicit-server-auth-and-health-version
  (let [handler (server/handler-with-config {:api-key "secret"})
        response (handler {:request-method :get
                           :uri "/xrpc/ai.etzhayyim.apps.calendar.listEvents"
                           :headers {"x-api-key" "wrong"}})]
    (is (= 401 (:status response))))
  (is (= "explicit" (:version (health/run {:host-config {:version "explicit"}})))))

(defn- datom
  ([a v-edn] (datom a v-edn true))
  ([a v-edn added] {"e" "cal:event:slug01" "a" a "v_edn" v-edn "added" added}))

(deftest test-datoms-to-attr-map-uses-a-and-v-edn
  (let [datoms [(datom ":cal/type" "\"Event\"")
                (datom ":cal/id" "\"cal:event:slug01\"")
                (datom ":cal/slug" "\"slug01\"")
                (datom ":cal/summary" "\"Standup\"")
                (datom ":cal/startsAt" "\"2026-06-02T09:00:00Z\"")
                (datom ":cal/allDay" "false")
                (datom ":cal/sequence" "3")
                (datom ":cal/createdAtMs" "1764662400000")
                ;; a retraction must be ignored
                (datom ":cal/summary" "\"OldTitle\"" false)]
        attrs (kd/datoms->attr-map datoms)]
    (is (some? attrs))
    (is (= "Standup" (get attrs "cal/summary")))          ; bare key, leading colon stripped
    (is (= "2026-06-02T09:00:00Z" (get attrs "cal/startsAt")))
    (is (= false (get attrs "cal/allDay")))               ; EDN false -> bool
    (is (= 3 (get attrs "cal/sequence")))                 ; EDN int -> int
    (is (= 1764662400000 (get attrs "cal/createdAtMs")))))

(deftest test-datoms-roundtrip-to-canonical-event-with-json-attendees
  ;; attendees are stored as a JSON string in :cal/attendeesJson; its EDN form
  ;; escapes the inner quotes — verify the full decode path reconstructs the list.
  (let [attendees-json "[{\"email\":\"a@x.com\",\"responseStatus\":\"accepted\"}]"
        v-edn (str "\"" (-> attendees-json
                            (str/replace "\\" "\\\\")
                            (str/replace "\"" "\\\"")) "\"")
        datoms [(datom ":cal/slug" "\"slug01\"")
                (datom ":cal/id" "\"cal:event:slug01\"")
                (datom ":cal/summary" "\"Party\"")
                (datom ":cal/startsAt" "\"2026-06-02T09:00:00Z\"")
                (datom ":cal/visibility" "\"private\"")
                (datom ":cal/attendeesJson" v-edn)]
        attrs (kd/datoms->attr-map datoms)
        event (mapping/attrs-to-event attrs)]
    (is (= "did:web:calendar.etzhayyim.com:event:slug01" (get event "did")))
    (is (= "Party" (get event "summary")))
    (is (= [{"email" "a@x.com" "responseStatus" "accepted"}] (get event "attendees")))))

(deftest test-empty-datoms-is-none
  (is (nil? (kd/datoms->attr-map []))))
