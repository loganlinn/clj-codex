(ns codex.model "Discover models and provider bounds." (:require [codex.api :as api]))
(defn list! "Fetch one model page." ([c] (list! c {})) ([c opts] (api/invoke! c {:op :model/list :args opts})))
(defn capabilities! "Read provider capabilities for model/provider arguments." [c opts]
  (api/invoke! c {:op :model-provider.capabilities/read :args opts}))
