(ns codex.account "Codex account state. Separate from transport authentication." (:require [codex.api :as api]))
(defn read! "Read account state and whether provider authentication is needed." ([c] (read! c {}))
  ([c opts] (api/invoke! c {:op :account/read :args opts})))
(defn login! "Start a login ceremony. Completion arrives through account events." [c args]
  (api/invoke! c {:op :account.login/start :args args}))
(defn cancel-login! "Cancel a managed login by ID." [c login-id]
  (api/invoke! c {:op :account.login/cancel :args {:login-id login-id}}))
(defn logout! "Sign out of the Codex account." [c] (api/invoke! c {:op :account/logout}))
(defn limits! "Read ChatGPT rate limits." ([c] (limits! c {})) ([c opts] (api/invoke! c {:op :account.rate-limits/read :args opts})))
(defn usage! "Read account token usage." ([c] (usage! c {})) ([c opts] (api/invoke! c {:op :account.usage/read :args opts})))
