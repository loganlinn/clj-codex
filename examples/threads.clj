#!/usr/bin/env bb
(require '[babashka.fs :as fs])
(load-file (str (fs/path (fs/parent *file*) "support.clj")))
(ns examples.threads
  "Read stored thread history or reduce across thread-list pages.

  bb examples/threads.clj --limit 10
  bb examples/threads.clj --id THREAD_ID
  bb examples/threads.clj --url ws://127.0.0.1:4500 --limit 20

  Output is EDN. With --id, read the thread with its turns and items without
  resuming or subscribing. Without --id, api/entries supplies a reducible
  collection and take stops pagination after the requested number of threads.

  Stored history can be shared through the same Codex home. It differs from
  the loaded threads of a particular server. See examples.support for shared
  connection options and instructions for an existing WebSocket server."
  (:use examples.support)
  (:require [clojure.pprint :refer [pprint]]
            [codex.app-server :as server]
            [codex.api :as api]
            [codex.thread :as thread]))

(defn threads! [{:keys [id limit] :as opts}]
  (server/with-connection [c (connect! opts)]
    (pprint
     (if id
       ;; A read returns stored history without resuming or subscribing.
       (thread/read! c id {:include-turns true})
       ;; entries is reducible: take stops pagination after enough records.
       (into [] (take limit)
             (api/entries c {:op :thread/list :args {:limit (min limit 100)}}))))))

(when (= *file* (System/getProperty "babashka.file"))
  (main! "bb examples/threads.clj [--id THREAD_ID] [--limit 10]"
         (merge connection-spec
                {:id {:coerce :string :desc "Read this thread, including turns and items"}
                 :limit {:coerce :long :default 10 :validate pos? :desc "Maximum stored threads to print"}})
         [] threads!))
