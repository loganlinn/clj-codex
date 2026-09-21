#!/usr/bin/env bb
(require '[babashka.fs :as fs])
(load-file (str (fs/path (fs/parent *file*) "support.clj")))
(ns examples.unix-socket
  "Inspect an existing Codex server through a filesystem Unix socket.

  Start the server in another terminal:
    codex app-server --listen unix:///tmp/codex.sock

  Read connection metadata and one page of loaded thread IDs as EDN:
    bb examples/unix_socket.clj /tmp/codex.sock
    bb examples/unix_socket.clj /tmp/codex.sock --url ws://localhost/

  The transport map needs both :unix-socket and :url. The socket path selects
  the endpoint. The ws:// URL supplies HTTP Upgrade metadata, with no DNS or
  TCP connection. Cleanup closes this client and leaves the server running.

  Unix transport requires Java 16+ with OS Unix socket support, or a compatible
  Babashka runtime such as 1.12.218. Other exploration scripts expose TCP URLs
  through --url. Run bb test:unix-live for the optional live Unix socket check."
  (:use examples.support)
  (:require [clojure.pprint :refer [pprint]]
            [codex.app-server :as server]
            [codex.thread :as thread]))

(defn inspect-unix! [{:keys [socket url connect-timeout-ms]}]
  ;; Close only this client when the scope exits. The external server stays available.
  (server/with-connection [c (server/connect!
                              {:transport {:type :websocket
                                           :url url
                                           :unix-socket socket
                                           :connect-timeout-ms connect-timeout-ms}})]
    ;; :unix-socket selects the endpoint. The ws:// URL supplies HTTP Upgrade
    ;; metadata only: this connection does not use DNS or a TCP socket.
    (pprint {:connection (server/info c)
             :loaded-threads (thread/loaded! c)})))

(when (= *file* (System/getProperty "babashka.file"))
  (main! "bb examples/unix_socket.clj [SOCKET] [--url ws://localhost/]"
         {:socket {:coerce :string :default "/tmp/codex.sock" :desc "Existing app-server Unix socket path"}
          :url {:coerce :string :default "ws://localhost/" :desc "HTTP Upgrade URL (ws:// only)"}
          :connect-timeout-ms {:coerce :long :default 10000 :validate pos?
                               :desc "Connection and send timeout in milliseconds"}}
         [:socket] inspect-unix!))
