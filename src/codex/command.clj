(ns codex.command
  "Sandboxed command execution. Streaming handles preserve byte boundaries."
  (:require [codex.api :as api] [codex.impl.execution :as execution] [codex.impl.util :as u]))
(defn exec! "Run a buffered command and return stdout/stderr strings and exit status." [c args]
  (when (or (:tty args) (:stream-stdin args) (:stream-stdout-stderr args))
    (throw (u/error :argument "Use command/start! for streaming or PTY execution" {})))
  (api/invoke! c {:op :command/exec :args args} {:timeout-ms nil}))
(defn start! "Start a streaming command. opts: :on-output, :capture-limit-bytes (default 1 MiB)."
  ([c args] (start! c args {})) ([c args opts] (execution/start! c :command args opts)))
(defn write! "Write stdin bytes; nil bytes with close? closes stdin." ([p bytes] (write! p bytes false))
  ([p bytes close?] (execution/write! p bytes close?)))
(defn resize! "Resize a PTY with {:rows n :cols n}." [p size] (execution/resize! p size))
(defn terminate! "Request command termination." [p] (execution/terminate! p))
(defn await! "Await exit and collected byte output. A local timeout does not terminate the command."
  ([p] (execution/await! p)) ([p ms timeout-value] (execution/await! p ms timeout-value)))
(defn output "Snapshot collected stdout/stderr bytes and truncation status." [p] (execution/output p))
