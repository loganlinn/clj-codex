#!/usr/bin/env bb
(require '[babashka.fs :as fs])
(load-file (str (fs/path (fs/parent *file*) "support.clj")))
(require '[examples.support :as example]
         '[codex.app-server :as server]
         '[codex.event :as event]
         '[codex.repl :as repl]
         '[codex.thread :as thread]
         '[clojure.pprint :refer [pprint]])

(defn watch! [{:keys [id timeout-ms] :as opts}]
  (when-not id (throw (ex-info "--id is required" {})))
  (let [c (example/connect! opts)
        state (atom {})
        failed (promise)]
    (try
      (let [subscription
            (event/listen! c {:thread-id id :on-error #(deliver failed %)}
              (fn [e] (swap! state event/apply-event e) (pprint e) (flush)))]
        (try
          ;; A local listener alone does not establish a remote subscription.
          (thread/resume! c id)
          (let [error (deref failed timeout-ms nil)]
            (when error (throw error)))
          (pprint {:observed-state @state :pending (repl/pending c)})
          (finally (event/unlisten! subscription))))
      (finally (server/close! c)))))

(when (= *file* (System/getProperty "babashka.file"))
  (example/main! "bb examples/watch.clj --id THREAD_ID [--url ws://localhost:4500]"
    (merge example/connection-spec example/timeout-spec
      {:id {:coerce :string :desc "Thread to resume and observe"}})
    [] watch!))
