(ns codex.impl.execution
  (:require [codex.api :as api] [codex.app-server :as server]
            [codex.impl.schema :as schema] [codex.impl.util :as u]))

(defrecord Execution [connection kind id result subscription request output lock])
(defmethod print-method Execution [p w]
  (.write ^java.io.Writer w (str "#codex/execution " (pr-str {:kind (:kind p) :id (:id p) :done? (realized? (:result p))}))))

(defn release! [execution]
  (when-let [s @(:subscription execution)] (server/unlisten! s)))

(defn output [execution]
  (locking (:lock execution)
    (let [state @(:output execution)]
      {:stdout (.toByteArray ^java.io.ByteArrayOutputStream (:stdout state))
       :stderr (.toByteArray ^java.io.ByteArrayOutputStream (:stderr state))
       :truncated? (:truncated? state)})))

(defn start! [conn kind args {:keys [capture-limit-bytes on-output] :or {capture-limit-bytes 1048576}}]
  (when-not (and (integer? capture-limit-bytes) (<= 0 capture-limit-bytes))
    (throw (u/error :argument "capture-limit-bytes must be nonnegative" {})))
  (let [required (if (= kind :command)
                   #{"command/exec/outputDelta"}
                   #{"process/outputDelta" "process/exited"})
        disabled (filterv required (get-in (server/info conn) [:capabilities :opt-out-notification-methods]))]
    (when (seq disabled)
      (throw (u/error :capability "Tracked execution requires notifications that this connection has opted out of"
                      {:methods disabled}))))
  (let [id-key (if (= kind :command) :process-id :process-handle)
        wire-id (if (= kind :command) "processId" "processHandle")
        id (or (get args id-key) (u/uuid))
        p (->Execution conn kind id (promise) (atom nil) (atom nil)
                       (atom {:stdout (java.io.ByteArrayOutputStream.) :stderr (java.io.ByteArrayOutputStream.)
                              :captured 0 :truncated? false}) (Object.))
        buffered (atom [])
        finish! (fn [result]
                  (u/deliver-value! (:result p) (merge result (output p))) (release! p))
        process! (fn [raw]
                   (let [method (get raw "method") params (get raw "params")
                         pending @(:request p)]
                     (cond
                       (and (= id (get params wire-id))
                            (= method (if (= kind :command) "command/exec/outputDelta" "process/outputDelta")))
                       (let [bytes (.decode (java.util.Base64/getDecoder) ^String (get params "deltaBase64"))
                             stream (keyword (get params "stream"))
                             {:keys [captured] :as state} @(:output p)
                             n (min (alength bytes) (- capture-limit-bytes captured))]
                         (when-not (#{:stdout :stderr} stream)
                           (throw (u/error :protocol "Unknown output stream" {})))
                         (.write ^java.io.ByteArrayOutputStream (get state stream) bytes 0 n)
                         (swap! (:output p) assoc :captured (+ captured n)
                                :truncated? (or (:truncated? state) (true? (get params "capReached")) (< n (alength bytes))))
                         (when on-output (on-output {:stream stream :bytes bytes})))
                       (and (= kind :process) (= method "process/exited") (= id (get params wire-id)))
                       (finish! (schema/decode-type "ProcessExitedNotification" params))
                       (and pending (= (get raw "id") (get-in pending [:request :id])) (not method))
                       (if (or (= kind :command) (contains? raw "error"))
                         (finish! (api/await! pending))
                         nil))))
        observer (fn [raw]
                   (locking (:lock p)
                     (if @(:request p) (process! raw)
                         (if (< (count @buffered) 4096) (swap! buffered conj raw)
                             (throw (u/error :overflow "Execution startup buffer overflow" {}))))))]
    (try
      (reset! (:subscription p)
              (server/listen! conn {:on-error #(do (u/deliver-error! (:result p) %) (release! p))} observer))
      (let [pending (api/submit! conn
                                 {:op (if (= kind :command) :command/exec :process/spawn)
                                  :args (assoc args id-key id :stream-stdout-stderr true)}
                                 {:timeout-ms nil})]
        (locking (:lock p)
          (reset! (:request p) pending)
          (doseq [raw @buffered] (process! raw))
          (reset! buffered [])))
      ;; RPC errors without a received response (transport loss) also resolve the handle.
      (u/worker! "codex-execution-rpc"
                 #(try (api/await! @(:request p))
                       (catch Exception e (u/deliver-error! (:result p) e) (release! p))))
      p
      (catch Exception e (release! p) (throw e)))))

(defn await!
  ([p] (u/await-result (:result p)))
  ([p timeout-ms timeout-value] (u/await-result (:result p) timeout-ms timeout-value)))
(defn write! [p bytes close?]
  (api/invoke! (:connection p)
               {:op (if (= :command (:kind p)) :command.exec/write :process/write-stdin)
                :args (cond-> {(if (= :command (:kind p)) :process-id :process-handle) (:id p)
                               :close-stdin (boolean close?)}
                        bytes (assoc :delta-base64 (.encodeToString (java.util.Base64/getEncoder) bytes)))}))
(defn resize! [p size]
  (api/invoke! (:connection p)
               {:op (if (= :command (:kind p)) :command.exec/resize :process/resize-pty)
                :args {(if (= :command (:kind p)) :process-id :process-handle) (:id p) :size size}}))
(defn terminate! [p]
  (api/invoke! (:connection p)
               {:op (if (= :command (:kind p)) :command.exec/terminate :process/kill)
                :args {(if (= :command (:kind p)) :process-id :process-handle) (:id p)}}))
