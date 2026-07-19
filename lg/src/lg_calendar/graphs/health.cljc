(ns lg-calendar.graphs.health
  "lg-calendar `health` graph — minimal liveness probe.

  Clojure port of lg_calendar/graphs/health.py from langgraph-python (StateGraph)
  to langgraph-clj (io.github.com-junkawasaki/langgraph-clj). Same topology:

      START -> :probe -> END

  State is a plain map; the single :probe node returns {ok, ts, version}."
  (:require [langgraph.graph :as g]))

(defn probe [state]
  {:ok true
   :ts (System/currentTimeMillis)
   :version (or (get-in state [:host-config :version]) "0.1.0")})

(def graph
  (-> (g/state-graph)
      (g/add-node :probe probe)
      (g/set-entry-point :probe)
      (g/set-finish-point :probe)
      (g/compile-graph)))

(defn run
  "Invoke the health graph once; returns the final state map."
  ([] (run {}))
  ([input] (g/invoke graph input)))
