(ns codex.permission "Permission discovery and pure policy values." (:require [codex.api :as api]))

(defn profiles!
  "Fetch available permission profiles for a working directory."
  ([c] (profiles! c {}))
  ([c opts] (api/invoke! c {:op :permission-profile/list :args opts})))

(defn read-only
  "Read-only sandbox policy with optional boolean network access."
  ([] {:type :read-only})
  ([network-access] {:type :read-only :network-access network-access}))

(defn workspace-write
  "Workspace-write policy for explicit server-side roots."
  ([roots] (workspace-write roots {}))
  ([roots opts] (assoc opts :type :workspace-write :writable-roots (vec roots))))

(defn external-sandbox
  "Policy for an externally sandboxed execution host."
  ([] (external-sandbox :restricted))
  ([network-access] {:type :external-sandbox :network-access network-access}))

(defn grant
  "Permission reply value. Only supply a requested subset."
  ([permissions] {:permissions permissions})
  ([permissions scope] {:permissions permissions :scope scope}))
