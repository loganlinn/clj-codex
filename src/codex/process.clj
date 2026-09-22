(ns codex.process
  "Explicit unsandboxed process control. Requires :experimental-api true."
  (:require [codex.impl.execution :as execution]))

(defn start!
  "Start an unsandboxed process with explicit :command and :cwd. Returns a tracked handle."
  ([c args] (start! c args {}))
  ([c args opts] (execution/start! c :process args opts)))

(defn write!
  "Write process stdin bytes."
  ([p bytes] (write! p bytes false))
  ([p bytes close?] (execution/write! p bytes close?)))

(defn resize!
  "Resize a process PTY with {:rows n :cols n}."
  [p size]
  (execution/resize! p size))

(defn kill!
  "Request process termination."
  [p]
  (execution/terminate! p))

(defn await!
  "Await process exit and collected bytes. Local timeout does not kill the process."
  ([p] (execution/await! p))
  ([p ms timeout-value] (execution/await! p ms timeout-value)))

(defn output
  "Snapshot collected stdout/stderr bytes and truncation status."
  [p]
  (execution/output p))
