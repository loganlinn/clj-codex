(ns codex.app "Connector metadata and effective runtime state." (:require [codex.api :as api]))

(defn list!
  "Fetch a page of available apps."
  ([c] (list! c {}))
  ([c opts] (api/invoke! c {:op :app/list :args opts})))

(defn read!
  "Read app metadata, preserving missing IDs."
  ([c ids] (read! c ids {}))
  ([c ids opts] (api/invoke! c {:op :app/read :args (assoc opts :app-ids (vec ids))})))

(defn installed!
  "Read enabled/callable app runtime state."
  ([c] (installed! c {}))
  ([c opts] (api/invoke! c {:op :app/installed :args opts})))
