(ns lg-calendar.server
  "HTTP server for lg-calendar (Clojure port of lg_calendar/server.py).

  FastAPI -> org.httpkit.server (bundled with babashka). Surfaces the canonical
  calendar XRPC methods the atproto actor-worker pipethrough forwards to:

    GET  /health /ok
    POST /xrpc/ai.etzhayyim.apps.calendar.createEvent
    GET  /xrpc/ai.etzhayyim.apps.calendar.getEvent
    GET  /xrpc/ai.etzhayyim.apps.calendar.listEvents
    POST /xrpc/ai.etzhayyim.apps.calendar.updateEvent
    POST /xrpc/ai.etzhayyim.apps.calendar.deleteEvent
    POST /xrpc/ai.etzhayyim.apps.calendar.rsvp
    GET  /xrpc/ai.etzhayyim.apps.calendar.listCalendars

  Persistence = kotoba datomic (graph `calendar-v1`). Auth: optional
  `LG_CALENDAR_API_KEY`; the edge actor-worker (x-internal-trust) is the real
  trust boundary."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [lg-calendar.handlers :as handlers]
            [lg-calendar.store :as store]
            [lg-calendar.kotoba-datomic :as kd]))

(defn- store-impl [kotoba-config]
  (store/kotoba-calendar-store (kd/make-kotoba-datomic kotoba-config)))

(defn- enforce-auth!
  "Throws a 401 ex-info when LG_CALENDAR_API_KEY is set and the header mismatches."
  [expected x-api-key]
  (when (and (seq expected) (not= x-api-key expected))
    (throw (ex-info "x-api-key mismatch" {:status 401}))))

(defn- url-decode [s]
  (java.net.URLDecoder/decode (str s) "UTF-8"))

(defn- parse-query [qs]
  (if (str/blank? qs)
    {}
    (into {}
          (for [pair (str/split qs #"&")
                :let [[k v] (str/split pair #"=" 2)]
                :when (seq k)]
            [(url-decode k) (url-decode (or v ""))]))))

(defn- json-resp
  ([status body] {:status status
                  :headers {"Content-Type" "application/json"}
                  :body (json/generate-string body)}))

(defn- read-body [req]
  (let [b (:body req)]
    (if (or (nil? b) (and (string? b) (str/blank? b)))
      {}
      (json/parse-string (if (string? b) b (slurp b))))))

(defn handler-with-config [{:keys [api-key kotoba http-post] :or {api-key "" kotoba kd/default-config}}]
 (fn [req]
  (binding [kd/*http-post* http-post]
  (let [uri (:uri req)
        method (:request-method req)
        x-api-key (get-in req [:headers "x-api-key"])]
    (try
      (cond
        (and (= :get method) (#{"/health" "/ok"} uri))
        (json-resp 200 {"ok" true "app" "lg-calendar" "ts" (System/currentTimeMillis)})

        (str/starts-with? uri "/xrpc/ai.etzhayyim.apps.calendar.")
        (let [op (subs uri (count "/xrpc/ai.etzhayyim.apps.calendar."))
              st (store-impl kotoba)]
          (enforce-auth! api-key x-api-key)
          (case [method op]
            [:post "createEvent"] (json-resp 200 (handlers/create-event st (read-body req)))
            [:post "updateEvent"] (json-resp 200 (handlers/update-event st (read-body req)))
            [:post "deleteEvent"] (json-resp 200 (handlers/delete-event st (read-body req)))
            [:post "rsvp"] (json-resp 200 (handlers/rsvp st (read-body req)))
            [:get "getEvent"] (json-resp 200 (handlers/get-event st (parse-query (:query-string req))))
            [:get "listEvents"] (json-resp 200 (handlers/list-events st (parse-query (:query-string req))))
            [:get "listCalendars"] (json-resp 200 (handlers/list-calendars st (parse-query (:query-string req))))
            (json-resp 404 {"error" "method not found" "op" op})))

        :else (json-resp 404 {"error" "not found"}))
      (catch clojure.lang.ExceptionInfo e
        (json-resp (or (:status (ex-data e)) 500) {"error" (.getMessage e)}))
      (catch Exception e
        (json-resp 500 {"error" (.getMessage e)})))))))

(def handler (handler-with-config {}))

(defn run-server-with [run-server port host-config]
  (when-not (fn? run-server)
    (throw (ex-info "server capability not configured" {:capability :run-server})))
  (when-not (and (integer? port) (<= 1 port 65535))
    (throw (ex-info "invalid server port" {:port port})))
  (run-server (handler-with-config host-config) {:port port}))

(defn -main [& _args]
  (throw (ex-info "host adapter must provide server, port and configuration"
                  {:capability :run-server})))
