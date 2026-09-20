(ns codex.config "Effective configuration and persistent edits." (:require [codex.api :as api]))
(defn read! "Read effective configuration, optionally with layers." ([c] (read! c {})) ([c opts] (api/invoke! c {:op :config/read :args opts})))
(defn requirements! "Read managed requirements." [c] (api/invoke! c {:op :config-requirements/read}))
(defn write! "Write a configuration value at a wire key path. Preserve user JSON keys."
  ([c path value] (write! c path value {}))
  ([c path value opts] (api/invoke! c {:op :config.value/write :args (merge {:merge-strategy :replace} opts {:key-path path :value value})})))
(defn patch! "Apply a vector of configuration edits atomically." ([c edits] (patch! c edits {}))
  ([c edits opts] (api/invoke! c {:op :config/batch-write :args (assoc opts :edits (vec edits))})))
