(ns codex.review
  "Review target values and reviewer submission."
  (:require [codex.api :as api] [codex.thread :as thread]))
(defn uncommitted "Review staged, unstaged, and untracked changes." [] {:type :uncommitted-changes})
(defn branch "Review against a base branch." [name] {:type :base-branch :branch name})
(defn commit "Review a commit." ([sha] {:type :commit :sha sha}) ([sha title] {:type :commit :sha sha :title title}))
(defn custom "Review using custom instructions." [instructions] {:type :custom :instructions instructions})
(defn start! "Start a review. Preserve both review-thread-id and the initial turn."
  ([c t target] (start! c t target {}))
  ([c t target opts]
   (api/invoke! c {:op :review/start :args (assoc opts :thread-id (::thread/id (thread/ref t)) :target target)})))
