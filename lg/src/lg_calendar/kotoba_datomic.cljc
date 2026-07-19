(ns lg-calendar.kotoba-datomic
  "kotoba datomic XRPC client for lg-calendar (Clojure port of kotoba_datomic.py).

  Wraps `ai.etzhayyim.apps.kotoba.datomic.{transact,q,pull}` — the canonical
  write/read surface for calendar events (graph `calendar-v1`). httpx ->
  babashka.http-client, json -> cheshire (the actor-tree httpx->bb port pattern).

  Endpoint resolution honors `KOTOBA_URL` (default kotoba Service :8080). Auth =
  Bearer JWT (`KOTOBA_BEARER`); `KOTOBA_DEFAULT_VISIBILITY=authenticated` on the
  kotoba pod keeps the dedicated `calendar-v1` graph JWT-only per ADR-2605302130."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [lg-calendar.edn :as edn]))

(def default-config {:xrpc-url "http://kotoba.kotoba.svc.cluster.local:8080"
                     :bearer "" :graph-label "calendar-v1"})
(def ^:dynamic *http-post* nil)

(def ^:private b32-alphabet "abcdefghijklmnopqrstuvwxyz234567")

(defn- base32-lower-nopad
  "RFC-4648 base32, lowercase, no padding (matches python base64.b32encode lower/strip=)."
  [^bytes data]
  (let [sb (StringBuilder.)
        n (alength data)]
    (loop [i 0 buf 0 bits 0]
      (cond
        ;; enough bits accumulated to emit a 5-bit group
        (>= bits 5)
        (let [bits' (- bits 5)
              idx (bit-and (unsigned-bit-shift-right buf bits') 0x1f)]
          (.append sb (.charAt b32-alphabet idx))
          (recur i (bit-and buf (dec (bit-shift-left 1 bits'))) bits'))
        ;; more input bytes to fold in
        (< i n)
        (recur (inc i)
               (bit-or (bit-shift-left buf 8) (bit-and (aget data i) 0xff))
               (+ bits 8))
        ;; trailing partial group (pad with zero bits on the right)
        (pos? bits)
        (let [idx (bit-and (bit-shift-left buf (- 5 bits)) 0x1f)]
          (.append sb (.charAt b32-alphabet idx)))))
    (.toString sb)))

(defn kotoba-cid [^bytes payload]
  (let [digest (.digest (java.security.MessageDigest/getInstance "SHA-256") payload)
        prefix (byte-array [0x01 0x71 0x12 0x20])
        cid (byte-array (concat (seq prefix) (seq digest)))]
    (str "b" (base32-lower-nopad cid))))

(defn graph-cid-for-label
  "Stable kotoba graph CID from a human-readable label (multibase passthrough)."
  [label]
  (if (and (str/starts-with? label "b") (re-matches #"b[a-z2-7]{58,80}" label))
    label
    (kotoba-cid (.getBytes ^String label "UTF-8"))))

(def default-graph (graph-cid-for-label (:graph-label default-config)))

(defn- bare-attr
  "':cal/summary' -> 'cal/summary'."
  [a]
  (let [s (str a)]
    (if (str/starts-with? s ":") (subs s 1) s)))

(defn datoms->attr-map
  "Fold pull datoms ({a, v_edn, added}) into {bare_attr: value}.

  Matches the proven yatabase `_datoms_to_entity` shape exactly: attribute under
  `a` (with leading colon), EDN-encoded value under `v_edn`, retractions flagged
  `added: false`."
  [datoms]
  (if-not (seq datoms)
    nil
    (let [out (reduce (fn [m d]
                        (if (or (not (map? d)) (false? (get d "added")))
                          m
                          (let [a (get d "a")]
                            (if-not a
                              m
                              (assoc m (bare-attr a) (edn/parse-edn-value (get d "v_edn" "")))))))
                      {} datoms)]
      (when (seq out) out))))

(defrecord KotobaDatomic [graph config])

(defn make-kotoba-datomic
  ([] (make-kotoba-datomic default-config))
  ([config-or-graph]
   (if (map? config-or-graph)
     (let [cfg (merge default-config config-or-graph)]
       (->KotobaDatomic (graph-cid-for-label (:graph-label cfg)) cfg))
     (->KotobaDatomic config-or-graph default-config))))

(defn- post-json [dm path body]
  (when-not (fn? *http-post*)
    (throw (ex-info "Calendar Kotoba client requires an explicit HTTP POST capability"
                    {:capability :calendar/kotoba-http-post})))
  (let [{:keys [xrpc-url bearer]} (:config dm)
        headers (cond-> {"Content-Type" "application/json"}
                  (seq bearer) (assoc "Authorization" (str "Bearer " bearer)))]
  (*http-post* (str (str/replace xrpc-url #"/+$" "") "/xrpc/" path)
             {:headers headers
                  :body (json/generate-string body)
                  :throw false})))

(defn transact
  ([dm ops] (transact dm ops nil))
  ([dm ops expected-parent]
   (let [body (cond-> {"graph" (:graph dm) "tx_edn" (edn/encode-tx-data ops)}
                expected-parent (assoc "expected_parent" expected-parent))
         resp (post-json dm "ai.etzhayyim.apps.kotoba.datomic.transact" body)]
     (when (>= (:status resp) 400)
       (throw (ex-info "kotoba transact failed" {:status (:status resp) :body (:body resp)})))
     (json/parse-string (:body resp)))))

(defn q
  ([dm query-edn] (q dm query-edn nil))
  ([dm query-edn inputs-edn]
   (let [body (cond-> {"graph" (:graph dm) "query_edn" query-edn}
                (seq inputs-edn) (assoc "inputs_edn" inputs-edn))
         resp (post-json dm "ai.etzhayyim.apps.kotoba.datomic.q" body)]
     (when (>= (:status resp) 400)
       (throw (ex-info "kotoba q failed" {:status (:status resp) :body (:body resp)})))
     (let [rows (or (get (json/parse-string (:body resp)) "rows_edn") [])]
       (mapv (fn [row] (mapv edn/parse-edn-value row)) rows)))))

(defn pull
  "Return the entity as a flat {bare_attr: value} map, or nil on miss."
  [dm entity]
  (let [body {"graph" (:graph dm) "entity" entity}
        resp (post-json dm "ai.etzhayyim.apps.kotoba.datomic.pull" body)]
    (cond
      (= 404 (:status resp)) nil
      (>= (:status resp) 400) (throw (ex-info "kotoba pull failed" {:status (:status resp) :body (:body resp)}))
      :else (datoms->attr-map (or (get (json/parse-string (:body resp)) "datoms") [])))))
