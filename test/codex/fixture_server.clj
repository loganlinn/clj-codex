(ns codex.fixture-server
  "Deterministic JSONL peer for transport tests; requires no account or network."
  (:require [cheshire.core :as json] [clojure.java.io :as io]))
(defn -main [& _]
  (binding [*out* *err*] (println "fixture stderr is separate from protocol output"))
  (doseq [line (line-seq (io/reader System/in))]
    (let [m (json/parse-string line)]
      (when (contains? m "id")
        (println (json/generate-string
                  {"id" (get m "id")
                   "result" (if (= "initialize" (get m "method"))
                              {"userAgent" "fixture"} (get m "params"))}))
        (flush)))))
