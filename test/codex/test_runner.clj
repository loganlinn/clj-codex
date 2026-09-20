(ns codex.test-runner
  (:require [clojure.test :as t] [codex.impl.websocket.loading-test]))
(defn -main [& args]
  ;; Run before any suite can open a Unix connection. Also supports a fresh
  ;; Java 11 process checking only the lazy facade and unsupported-runtime error.
  (let [loading (t/run-tests 'codex.impl.websocket.loading-test)
        loading-only? (= ["--loading-only"] (vec args))
        supported? (and (not loading-only?)
                        (resolve 'java.net.UnixDomainSocketAddress)
                        ((requiring-resolve 'codex.impl.websocket.unix-socket-fixture/supported?)))
        result (when-not loading-only?
                 (require 'codex.core-test 'codex.transport-test 'codex.review-test)
                 (when supported?
                   (require 'codex.impl.websocket.unix-test 'codex.unix-transport-test))
                 (apply t/run-tests
                        (cond-> ['codex.core-test 'codex.transport-test 'codex.review-test]
                          supported? (into ['codex.impl.websocket.unix-test 'codex.unix-transport-test]))))
        unavailable? (and (not loading-only?) (not supported?))]
    (when unavailable?
      (println "Unix sockets unavailable: Unix suites were NOT run."))
    (shutdown-agents)
    (when (or unavailable?
              (pos? (+ (:fail loading) (:error loading)
                       (or (:fail result) 0) (or (:error result) 0))))
      (System/exit 1))))
