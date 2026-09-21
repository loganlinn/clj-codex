#!/usr/bin/env bb
(require '[babashka.fs :as fs])
(load-file (str (fs/path (fs/parent *file*) "support.clj")))
(ns examples.watch
  "Resume a thread and observe domain events on the connected server.

  Start a shared server in another terminal:
    codex app-server --listen ws://127.0.0.1:4500

  Attach an observer:
    bb examples/watch.clj --url ws://127.0.0.1:4500 --id THREAD_ID --timeout-ms 60000

  The listener is installed before thread/resume! establishes the remote
  subscription. Output includes events as EDN, the final observed state,
  and pending requests. That state contains only events seen during this
  invocation, not the complete stored history. An idle thread can emit none.

  The timeout ends observation without requesting turn interruption.
  Cleanup closes the client and leaves an external server running.
  See examples.support for authentication and other connection options.
  To observe a local session across server processes, use examples.core-async."
  (:use examples.support)
  (:require [codex.app-server :as server]
            [codex.event :as event]
            [codex.repl :as repl]
            [codex.thread :as thread]
            [clojure.pprint :refer [pprint]]))

(defn watch! [{:keys [id timeout-ms] :as opts}]
  (when-not id (throw (ex-info "--id is required" {})))
  (server/with-connection [c (connect! opts)]
    (let [state (atom {})
          failed (promise)
          subscription
          (event/listen! c {:thread-id id :on-error #(deliver failed %)}
                         (fn [e] (swap! state event/apply-event e) (pprint e) (flush)))]
      (try
        ;; A local listener alone does not establish a remote subscription.
        (thread/resume! c id)
        (when-let [error (deref failed timeout-ms nil)] (throw error))
        (pprint {:observed-state @state :pending (repl/pending c)})
        (finally (event/unlisten! subscription))))))

(when (= *file* (System/getProperty "babashka.file"))
  (main! "bb examples/watch.clj --id THREAD_ID [--url ws://localhost:4500]"
         (merge connection-spec timeout-spec
                {:id {:coerce :string :desc "Thread to resume and observe"}})
         [] watch!))
