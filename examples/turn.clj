#!/usr/bin/env bb
(require '[babashka.fs :as fs])
(load-file (str (fs/path (fs/parent *file*) "support.clj")))
(ns examples.turn
  "Start a model turn and wait for its completed projection.

  bb examples/turn.clj --cwd /path/to/repo --prompt 'Explain the test structure.'

  The script creates a read-only thread with approval policy never and prints
  message items as EDN. It uses the existing Codex provider or account and can
  consume paid usage. It does not start a login flow.

  On timeout, the script requests interruption before cleanup. The SDK's
  run/await! alone leaves remote work running after a local timeout."
  (:use examples.support)
  (:require [babashka.fs :as fs]
            [clojure.pprint :refer [pprint]]
            [codex.app-server :as server]
            [codex.input :as input]
            [codex.item :as item]
            [codex.run :as run]
            [codex.thread :as thread]
            [codex.turn :as turn]))

(defn turn! [{:keys [cwd prompt timeout-ms] :as opts}]
  (let [cwd (str (fs/canonicalize cwd))]
    (server/with-connection [c (connect! (assoc opts :cwd cwd))]
      (let [context (thread/start! c {:cwd cwd :sandbox :read-only :approval-policy :never})
            t (::thread/thread context)
            work (run/start! c t {:input [(input/text prompt)]})]
        (try
          (let [result (run/await! work timeout-ms ::timeout)]
            (when (= ::timeout result)
              ;; await! alone does not interrupt. This script explicitly does.
              (run/interrupt! work)
              (throw (ex-info "Turn timed out; interruption requested" {:thread-id (::thread/id t)})))
            (successful! result)
            (pprint (item/messages (::turn/items result))))
          (finally (run/close! work)))))))

(when (= *file* (System/getProperty "babashka.file"))
  (main! "bb examples/turn.clj [--cwd .] [--prompt 'Explain this project.']"
         (merge timeout-spec
                {:cwd {:coerce :string :default "." :desc "Local directory to inspect"}
                 :prompt {:coerce :string :default "Explain the repository structure without changing files."
                          :desc "Input for a new turn"}})
         [] turn!))
