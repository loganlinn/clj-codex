(ns codex.impl.transport
  (:require [babashka.http-client.websocket :as ws]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [codex.impl.websocket.unix :as unix]
            [codex.impl.util :as u]))

(defn- websocket-message [text]
  ;; parse-string accepts trailing input. A WebSocket message must contain
  ;; exactly one JSON value; realize the tail before dispatching the first.
  (let [messages (json/parsed-seq (java.io.StringReader. text))
        message (first messages)]
    (when (next messages)
      (throw (u/error :protocol "Expected one JSON value per WebSocket message" {})))
    message))

(defn open! [{:keys [type command cwd env url headers token-fn connect-timeout-ms open unix-socket]
              :or {command ["codex" "app-server"] connect-timeout-ms 10000}
              :as opts}
             {:keys [receive! closed! stderr!]}]
  (when (contains? opts :unix-socket)
    (when-not (= :websocket type)
      (throw (u/error :argument ":unix-socket requires :type :websocket" {})))
    (when-not (and (string? unix-socket) (seq unix-socket))
      (throw (u/error :argument ":unix-socket must be a nonempty filesystem path string" {})))
    (when-not (and (string? url) (seq url))
      (throw (u/error :argument "Unix WebSockets require a ws:// :url for the HTTP Upgrade" {})))
    (when-not (and (integer? connect-timeout-ms) (pos? connect-timeout-ms)
                   (<= connect-timeout-ms Long/MAX_VALUE))
      (throw (u/error :argument ":connect-timeout-ms must be a positive integer" {}))))
  (case type
    :custom (open {:receive! receive! :closed! closed! :stderr! stderr!})
    :stdio
    (let [p (process/process command (cond-> {:in :stream :out :stream :err :stream}
                                       cwd (assoc :dir cwd) env (assoc :extra-env env)))
          writer (io/writer (:in p) :encoding "UTF-8")
          reader (io/reader (:out p) :encoding "UTF-8")
          err (io/reader (:err p) :encoding "UTF-8")
          stopped (atom false)]
      (u/worker! "codex-stdout"
                 #(try (loop []
                         (when-let [line (.readLine ^java.io.BufferedReader reader)]
                           (receive! (json/parse-string line)) (recur)))
                       (closed! nil)
                       (catch Exception e (when-not @stopped (closed! e)))))
      (u/worker! "codex-stderr"
                 #(try (loop []
                         (when-let [line (.readLine ^java.io.BufferedReader err)]
                           (when stderr! (try (stderr! line) (catch Exception _ nil)))
                           (recur)))
                       (catch Exception _ nil)))
      {:send! (fn [message]
                (.write ^java.io.Writer writer (str (json/generate-string message) "\n"))
                (.flush ^java.io.Writer writer))
       :close! (fn []
                 (when (compare-and-set! stopped false true)
                   ;; Terminate the owned process before closing a reader blocked in readLine.
                   (process/destroy-tree p)
                   (doseq [stream [writer reader err]]
                     (try (.close ^java.io.Closeable stream) (catch Exception _ nil)))))})
    :websocket
    (let [connect (if (contains? opts :unix-socket) unix/websocket ws/websocket)
          send (if (contains? opts :unix-socket) unix/send! ws/send!)
          abort (if (contains? opts :unix-socket) unix/abort! ws/abort!)
          fragments (atom "")
          socket (connect
                  (cond-> {:uri url :connect-timeout connect-timeout-ms
                           :headers (cond-> (or headers {}) token-fn (assoc "Authorization" (str "Bearer " (token-fn))))
                           :on-message (fn [socket data last?]
                                         (try
                                           (when-not (instance? CharSequence data)
                                             (throw (u/error :protocol "Expected a WebSocket text message" {})))
                                           (let [text (swap! fragments str data)]
                                             (when last?
                                               (reset! fragments "")
                                               (receive! (websocket-message text))))
                                           (catch Exception e
                                     ;; A callback can precede open!'s return. Dispose via
                                     ;; its handle even before the SDK stores the transport.
                                             (reset! fragments "")
                                             (abort socket)
                                             (closed! e))))
                           :on-close (fn [_ _ _] (closed! nil))
                           :on-error (fn [socket e] (abort socket) (closed! e))}
                    (contains? opts :unix-socket) (assoc :unix-socket unix-socket)))]
      {:send! (fn [message] (.get ^java.util.concurrent.CompletableFuture
                             (send socket (json/generate-string message))
                                  (long connect-timeout-ms) java.util.concurrent.TimeUnit/MILLISECONDS))
       :close! #(abort socket)})
    (throw (u/error :transport "Unsupported transport" {:type type}))))
