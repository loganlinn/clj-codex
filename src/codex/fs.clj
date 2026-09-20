(ns codex.fs
  "Files on the server host. Read/write content as bytes, never implicit local paths."
  (:require [codex.api :as api] [codex.impl.util :as u]))
(defn read! "Read a file as a byte array." [c path]
  (.decode (java.util.Base64/getDecoder)
           ^String (:data-base64 (api/invoke! c {:op :fs/read-file :args {:path path}}))))
(defn write! "Write a byte array to a server-side file." [c path bytes]
  (api/invoke! c {:op :fs/write-file :args {:path path :data-base64 (.encodeToString (java.util.Base64/getEncoder) bytes)}}))
(defn stat! "Read path metadata." [c path] (api/invoke! c {:op :fs/get-metadata :args {:path path}}))
(defn list! "Read a directory." [c path] (api/invoke! c {:op :fs/read-directory :args {:path path}}))
(defn mkdir! "Create a directory." ([c path] (mkdir! c path {}))
  ([c path opts] (api/invoke! c {:op :fs/create-directory :args (assoc opts :path path)})))
(defn copy! "Copy paths using explicit sourcePath/destinationPath equivalents." [c args]
  (api/invoke! c {:op :fs/copy :args args}))
(defn delete! "Remove a server-side path." ([c path] (delete! c path {}))
  ([c path opts] (api/invoke! c {:op :fs/remove :args (assoc opts :path path)})))
(defn watch! "Create a server-side watch. Subscribe to :fs/changed before calling." [c path]
  (let [id (u/uuid)]
    (assoc (api/invoke! c {:op :fs/watch :args {:path path :watch-id id}}) :watch-id id)))
(defn unwatch! "Remove a server-side watch by ID." [c watch-id]
  (api/invoke! c {:op :fs/unwatch :args {:watch-id watch-id}}))
