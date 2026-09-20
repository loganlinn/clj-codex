#!/usr/bin/env bb
(require '[babashka.fs :as fs])
(load-file (str (fs/path (fs/parent *file*) "support.clj")))
(require '[examples.support :as example]
         '[clojure.pprint :refer [pprint]]
         '[codex.app-server :as server]
         '[codex.input :as input]
         '[codex.item :as item]
         '[codex.run :as run]
         '[codex.thread :as thread]
         '[codex.turn :as turn])

(defn turn! [{:keys [cwd prompt timeout-ms] :as opts}]
  (let [cwd (str (fs/canonicalize cwd))
        c (example/connect! (assoc opts :cwd cwd))]
    (try
      (let [context (thread/start! c {:cwd cwd :sandbox :read-only :approval-policy :never})
            t (::thread/thread context)
            work (run/start! c t {:input [(input/text prompt)]})]
        (try
          (let [result (run/await! work timeout-ms ::timeout)]
            (when (= ::timeout result)
              ;; await! alone does not interrupt. This script explicitly does.
              (run/interrupt! work)
              (throw (ex-info "Turn timed out; interruption requested" {:thread-id (::thread/id t)})))
            (example/successful! result)
            (pprint (item/messages (::turn/items result))))
          (finally (run/close! work))))
      (finally (server/close! c)))))

(when (= *file* (System/getProperty "babashka.file"))
  (example/main! "bb examples/turn.clj [--cwd .] [--prompt 'Explain this project.']"
                 (merge example/timeout-spec
                        {:cwd {:coerce :string :default "." :desc "Local directory to inspect"}
                         :prompt {:coerce :string :default "Explain the repository structure without changing files."
                                  :desc "Input for a new turn"}})
                 [] turn!))
