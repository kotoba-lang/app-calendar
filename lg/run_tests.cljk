;; lg-calendar clojure.test runner (repo rule: run_tests.clj, NOT a .sh).
;; Usage (from this dir, picks up ./bb.edn for the langgraph-clj classpath):
;;   bb run_tests.clj      OR      bb test
(ns run-tests
  (:require [clojure.test :as t]
            lg-calendar.test-handlers
            lg-calendar.test-datomic-shape))

(let [{:keys [fail error]} (t/run-tests 'lg-calendar.test-handlers
                                        'lg-calendar.test-datomic-shape)]
  (when (pos? (+ fail error))
    (System/exit 1)))
