(ns codex.goal
  "Goal operations and pure budget accounting."
  (:require [codex.api :as api]
            [codex.thread :as thread]))

(defn read!
  "Read a thread's current goal."
  [c t]
  (:goal (api/invoke! c {:op :thread.goal/get :args {:thread-id (::thread/id (thread/ref t))}})))

(defn set!
  "Set or update a goal. A new objective resets usage accounting."
  [c t fields]
  (:goal (api/invoke! c {:op :thread.goal/set :args (assoc fields :thread-id (::thread/id (thread/ref t)))})))

(defn clear!
  "Clear a thread's goal."
  [c t]
  (api/invoke! c {:op :thread.goal/clear :args {:thread-id (::thread/id (thread/ref t))}}))

(defn remaining-tokens
  "Remaining token budget, or nil for an unbudgeted goal."
  [goal]
  (when-some [budget (::token-budget goal)] (max 0 (- budget (or (::tokens-used goal) 0)))))
