(ns codex.impl.transport
  (:require [babashka.http-client.websocket :as ws]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [codex.impl.util :as u]))

(defn open! [{:keys [type command cwd env url headers token-fn connect-timeout-ms open]
              :or {command ["codex" "app-server"] connect-timeout-ms 10000}}
             {:keys [receive! closed! stderr!]}]
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
    (let [fragments (atom "")
          socket (ws/websocket
                  {:uri url :connect-timeout connect-timeout-ms
                   :headers (cond-> (or headers {}) token-fn (assoc "Authorization" (str "Bearer " (token-fn))))
                   :on-message (fn [_ data last?]
                                 (try
                                   (when-not (instance? CharSequence data)
                                     (throw (u/error :protocol "Expected a WebSocket text message" {})))
                                   (let [text (swap! fragments str data)]
                                     (when last?
                                       (reset! fragments "")
                                       (receive! (json/parse-string text))))
                                   (catch Exception e (closed! e))))
                   :on-close (fn [_ _ _] (closed! nil))
                   :on-error (fn [_ e] (closed! e))})]
      {:send! (fn [message] (.get ^java.util.concurrent.CompletableFuture
                                 (ws/send! socket (json/generate-string message))
                                 (long connect-timeout-ms) java.util.concurrent.TimeUnit/MILLISECONDS))
       :close! #(ws/abort! socket)})
    (throw (u/error :transport "Unsupported transport" {:type type}))))
