(ns codex.test-runner
  (:require [clojure.test :as t] [codex.core-test] [codex.transport-test]))
(defn -main [& _]
  (let [result (t/run-tests 'codex.core-test 'codex.transport-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
