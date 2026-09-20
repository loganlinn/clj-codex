#!/usr/bin/env bb
(require '[babashka.fs :as fs])
(load-file (str (fs/path (fs/parent *file*) "support.clj")))
(require '[examples.support :as example]
         '[clojure.pprint :refer [pprint]]
         '[codex.api :as api]
         '[codex.schema :as schema])

(defn catalog! [{:keys [op schema-name]}]
  ;; All three branches use bundled data. No connection or account is needed.
  (pprint (cond
            schema-name (schema/describe schema-name)
            op (api/describe op)
            :else {:provenance (api/provenance)
                   :operations (into (sorted-map)
                                     (map (fn [[k v]] [k (select-keys v [:experimental? :args-schema :result-schema])]))
                                     (api/operations))})))

(when (= *file* (System/getProperty "babashka.file"))
  (example/main! "bb examples/catalog.clj [--op thread/read | --schema-name Thread]"
                 {:op {:coerce :keyword :desc "Operation keyword without the leading colon"}
                  :schema-name {:coerce :string :desc "Bundled schema name"}}
                 [] catalog!))
