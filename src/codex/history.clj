(ns codex.history
  "Stored history queries and explicit context changes."
  (:require [codex.api :as api] [codex.thread :as thread]))
(defn- args [t opts] (assoc opts :thread-id (::thread/id (thread/ref t))))
(defn turns! "Fetch a page of persisted turns (experimental)." ([c t] (turns! c t {}))
  ([c t opts] (api/invoke! c {:op :thread.turns/list :args (args t opts)})))
(defn items! "Fetch a page of persisted items (experimental)." ([c t] (items! c t {}))
  ([c t opts] (api/invoke! c {:op :thread.items/list :args (args t opts)})))
(defn compact! "Request compaction and return acknowledgement; progress arrives as events." [c t]
  (api/invoke! c {:op :thread.compact/start :args (args t {})}))
(defn inject! "Append string-keyed Responses API items without starting a turn." [c t items]
  (api/invoke! c {:op :thread/inject-items :args (args t {:items items})}))
