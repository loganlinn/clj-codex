#!/usr/bin/env bb
(require '[babashka.fs :as fs])
(load-file (str (fs/path (fs/parent *file*) "support.clj")))
(require '[examples.support :as example]
         '[clojure.pprint :refer [pprint]]
         '[codex.app-server :as server]
         '[codex.api :as api]
         '[codex.thread :as thread])

(defn threads! [{:keys [id limit] :as opts}]
  (let [c (example/connect! opts)]
    (try
      (pprint
       (if id
          ;; A read returns stored history without resuming or subscribing.
         (thread/read! c id {:include-turns true})
          ;; entries is reducible: take stops pagination after enough records.
         (into [] (take limit)
               (api/entries c {:op :thread/list :args {:limit (min limit 100)}}))))
      (finally (server/close! c)))))

(when (= *file* (System/getProperty "babashka.file"))
  (example/main! "bb examples/threads.clj [--id THREAD_ID] [--limit 10]"
                 (merge example/connection-spec
                        {:id {:coerce :string :desc "Read this thread, including turns and items"}
                         :limit {:coerce :long :default 10 :validate pos? :desc "Maximum stored threads to print"}})
                 [] threads!))
