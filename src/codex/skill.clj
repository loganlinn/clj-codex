(ns codex.skill "Discover and configure standalone skills." (:require [codex.api :as api]))

(defn list!
  "Discover skills, optionally for :cwds and with :force-reload."
  ([c] (list! c {}))
  ([c opts] (api/invoke! c {:op :skills/list :args opts})))

(defn enable!
  "Enable a skill by server-side path."
  [c path]
  (api/invoke! c {:op :skills.config/write :args {:path path :enabled true}}))

(defn disable!
  "Disable a skill by server-side path."
  [c path]
  (api/invoke! c {:op :skills.config/write :args {:path path :enabled false}}))

(defn extra-roots!
  "Replace process-level extra skill roots."
  [c roots]
  (api/invoke! c {:op :skills.extra-roots/set :args {:extra-roots roots}}))
