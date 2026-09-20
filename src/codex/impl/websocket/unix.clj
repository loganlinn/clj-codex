(ns codex.impl.websocket.unix
  "Private Unix WebSocket facade. No dependency on babashka.http-client."
  {:no-doc true}
  (:require [codex.impl.websocket.transport :as transport])
  (:import [java.util.concurrent CompletableFuture]))

(defn websocket
  "Connect to :unix-socket using :uri for the HTTP Upgrade request.
  Requires Java 16+ or Babashka with Unix socket support.
  Options: :headers, :subprotocols, :connect-timeout (milliseconds), :async,
  :on-open, :on-message, :on-ping, :on-pong, :on-close, and :on-error.
  Returns an opaque connection, or a CompletableFuture when :async is true."
  [opts]
  (when-not (resolve 'java.net.UnixDomainSocketAddress)
    (throw (UnsupportedOperationException.
            "Unix WebSockets require Java 16+ or Babashka with Unix socket support")))
  ((requiring-resolve 'codex.impl.websocket.unix-transport/websocket) opts))

(defn send!
  "Send text, bytes, or a ByteBuffer. :last defaults to true.
  Returns a CompletableFuture of the connection."
  ([connection data] (send! connection data nil))
  ([connection data {:keys [last] :or {last true}}]
   (transport/-send! connection data last)))

(defn ping! ^CompletableFuture [connection data]
  (transport/-ping! connection data))

(defn pong! ^CompletableFuture [connection data]
  (transport/-pong! connection data))

(defn close!
  "Send a close frame. Completion means the frame was written."
  (^CompletableFuture [connection] (close! connection 1000 ""))
  (^CompletableFuture [connection status reason]
   (transport/-close! connection status reason)))

(defn abort!
  "Release the connection immediately. Returns nil and emits no terminal callback."
  [connection]
  (transport/-abort! connection))
