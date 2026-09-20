(ns codex.run
  "Track a user-input turn through its terminal event. A wait timeout never interrupts work."
  (:require [codex.app-server :as server] [codex.event :as event]
            [codex.thread :as thread] [codex.turn :as turn] [codex.impl.util :as u]))

(defrecord Run [connection thread-id turn-id state result subscription])
(defmethod print-method Run [run w]
  (.write ^java.io.Writer w (str "#codex/run " (pr-str {:thread-id (:thread-id run) :turn-id @(:turn-id run)
                                                       :done? (realized? (:result run))}))))
(defn snapshot "Return the current immutable turn projection." [run]
  (event/turn-snapshot @(:state run) (:thread-id run) @(:turn-id run)))
(defn await!
  "Return the terminal turn projection, including failed-turn data. Transport failures throw."
  ([run] (u/await-result (:result run)))
  ([run timeout-ms timeout-value] (u/await-result (:result run) timeout-ms timeout-value)))
(defn close! "Stop local observation without interrupting the remote turn." [run]
  (when-let [s @(:subscription run)] (event/unlisten! s))
  (u/deliver-error! (:result run) (u/error :closed "Run observation closed" {}))
  nil)
(defn interrupt! "Request interruption of this run's identified turn." [run]
  (turn/interrupt! (:connection run) (turn/ref (:thread-id run) @(:turn-id run))))

(defn start!
  "Start and track an ordinary user-input turn. Installs observation before submission."
  [conn thread args]
  (when (:tool-output args)
    (throw (u/error :argument "Tracked runs require user input; use turn/start! for standalone tool output" {})))
  (when (seq (get-in (server/info conn) [:capabilities :opt-out-notification-methods]))
    (throw (u/error :capability "Tracked runs require a connection without notification opt-outs" {})))
  (let [t (::thread/id (thread/ref thread))
        run (->Run conn t (atom nil) (atom {}) (promise) (atom nil))
        lock (Object.) buffer (atom [])
        finish! (fn []
                  (let [current (snapshot run)]
                    (when (turn/terminal? current)
                      (u/deliver-value! (:result run) current)
                      (when-let [s @(:subscription run)] (event/unlisten! s)))))
        observe! (fn [e]
                   (locking lock
                     (if-let [id @(:turn-id run)]
                       (when (= id (:codex.turn/id e))
                         (swap! (:state run) event/apply-event e) (finish!))
                       (if (< (count @buffer) 4096)
                         (swap! buffer conj e)
                         (throw (u/error :overflow "Run startup event buffer overflow" {}))))))]
    (try
      (reset! (:subscription run)
              (event/listen! conn {:thread-id t :on-error #(u/deliver-error! (:result run) %)} observe!))
      (let [initial (turn/start! conn t args)]
        (locking lock
          (reset! (:turn-id run) (::turn/id initial))
          (when-not (::turn/id initial) (throw (u/error :protocol "Turn response omitted its ID" {})))
          (swap! (:state run) event/apply-event
                 {::event/type :turn/started :codex.thread/id t :codex.turn/id (::turn/id initial)
                  ::event/data {:turn initial}})
          (doseq [e @buffer :when (= (::turn/id initial) (:codex.turn/id e))]
            (swap! (:state run) event/apply-event e))
          (reset! buffer []) (finish!)))
      run
      (catch Exception e (close! run) (throw e)))))
