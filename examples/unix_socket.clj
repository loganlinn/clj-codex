#!/usr/bin/env bb
(require '[babashka.fs :as fs])
(load-file (str (fs/path (fs/parent *file*) "support.clj")))
(require '[examples.support :as example]
         '[clojure.pprint :refer [pprint]]
         '[codex.app-server :as server]
         '[codex.thread :as thread])

(defn inspect-unix! [{:keys [socket url connect-timeout-ms]}]
  ;; Start the server separately:
  ;; codex app-server --listen unix:///tmp/codex.sock
  (let [c (server/connect!
            {:transport {:type :websocket
                         :url url
                         :unix-socket socket
                         :connect-timeout-ms connect-timeout-ms}})]
    (try
      ;; :socket selects the endpoint. The ws:// URL supplies HTTP Upgrade
      ;; metadata only: this connection does not use DNS or a TCP socket.
      (pprint {:connection (server/info c)
               :loaded-threads (thread/loaded! c)})
      ;; Close only this client. The external server and socket stay available.
      (finally (server/close! c)))))

(when (= *file* (System/getProperty "babashka.file"))
  (example/main! "bb examples/unix_socket.clj [SOCKET] [--url ws://localhost/]"
    {:socket {:coerce :string :default "/tmp/codex.sock" :desc "Existing app-server Unix socket path"}
     :url {:coerce :string :default "ws://localhost/" :desc "HTTP Upgrade URL (ws:// only)"}
     :connect-timeout-ms {:coerce :long :default 10000 :validate pos?
                          :desc "Connection and send timeout in milliseconds"}}
    [:socket] inspect-unix!))
