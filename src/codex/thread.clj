(ns codex.thread
  "Conversation lifecycle. Thread values are immutable and contain no connection."
  (:refer-clojure :exclude [ref])
  (:require [codex.api :as api]
            [codex.impl.util :as u]))

(defn ref
  "Extract an explicit thread reference from an ID or thread value."
  [x]
  (let [id (if (string? x) x (::id x))]
    (when-not (and (string? id) (seq id)) (throw (u/error :argument "A thread ID is required" {})))
    {::id id}))

(defn start!
  "Create a thread and return its thread/configuration context."
  [conn args]
  (api/invoke! conn {:op :thread/start :args args}))

(defn resume!
  "Load and subscribe to a stored thread."
  ([c t] (resume! c t {}))
  ([c t args] (api/invoke! c {:op :thread/resume :args (assoc args :thread-id (::id (ref t)))})))

(defn fork!
  "Fork history, optionally through :last-turn-id."
  ([c t] (fork! c t {}))
  ([c t args] (api/invoke! c {:op :thread/fork :args (assoc args :thread-id (::id (ref t)))})))

(defn read!
  "Read a stored snapshot without subscribing."
  ([c t] (read! c t {}))
  ([c t args] (api/invoke! c {:op :thread/read :args (assoc args :thread-id (::id (ref t)))})))

(defn list!
  "Fetch one page of stored threads."
  ([c] (list! c {}))
  ([c args] (api/invoke! c {:op :thread/list :args args})))

(defn loaded!
  "Fetch a page of loaded thread IDs."
  ([c] (loaded! c {}))
  ([c args] (api/invoke! c {:op :thread.loaded/list :args args})))

(defn rename!
  "Set a thread's display name."
  [c t name]
  (api/invoke! c {:op :thread.name/set :args {:thread-id (::id (ref t)) :name name}}))

(defn patch!
  "Patch persisted metadata. Omitted fields and explicit nil differ."
  [c t edits]
  (api/invoke! c {:op :thread.metadata/update :args (assoc edits :thread-id (::id (ref t)))}))

(defn archive!
  "Archive a thread and attempt to archive spawned descendants."
  [c t]
  (api/invoke! c {:op :thread/archive :args {:thread-id (::id (ref t))}}))

(defn restore!
  "Restore an archived thread."
  [c t]
  (api/invoke! c {:op :thread/unarchive :args {:thread-id (::id (ref t))}}))

(defn delete!
  "Permanently delete a thread and spawned descendants."
  [c t]
  (api/invoke! c {:op :thread/delete :args {:thread-id (::id (ref t))}}))

(defn unsubscribe!
  "Remove this connection's remote thread subscription."
  [c t]
  (api/invoke! c {:op :thread/unsubscribe :args {:thread-id (::id (ref t))}}))
