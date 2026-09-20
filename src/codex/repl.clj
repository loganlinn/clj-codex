(ns codex.repl
  "Explicit, bounded REPL observation. Loading this namespace performs no I/O."
  (:require [clojure.pprint :as pprint] [codex.api :as api]
            [codex.app-server :as server] [codex.event :as event] [codex.interaction :as interaction]))
(defn watch!
  "Observe and print domain events. :history-limit defaults to 200; :print? defaults true."
  ([c] (watch! c {}))
  ([c {:keys [history-limit print?] :or {history-limit 200 print? true} :as opts}]
   (when-not (and (integer? history-limit) (pos? history-limit))
     (throw (ex-info "history-limit must be positive" {:codex.error/category :argument})))
   (let [events (atom []) out *out*
         subscription (event/listen! c opts
                                     (fn [e]
                                       (swap! events #(let [v (conj % e)]
                                                        (if (> (count v) history-limit) (into [] (subvec v 1)) v)))
                                       (when print? (binding [*out* out] (pprint/pprint e) (flush)))))]
     {:subscription subscription :events events})))
(defn unwatch! "Stop a REPL watcher." [watch] (event/unlisten! (:subscription watch)))
(defn history "Return the bounded event history of a watcher." [watch] @(:events watch))
(defn pending "Inspect typed pending server requests." [conn] (interaction/pending conn))
(defn inspect "Print and return a value. Connections use their safe info snapshot." [value]
  (let [v (if (instance? codex.app_server.Connection value) (server/info value) value)]
    (pprint/pprint v) v))
(defn explain "Print and return an operation descriptor." [op] (inspect (api/describe op)))
