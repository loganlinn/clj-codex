(ns codex.impl.websocket.unix-socket-fixture
  (:import [java.net StandardProtocolFamily UnixDomainSocketAddress]
           [java.nio.channels ServerSocketChannel]
           [java.nio.file Files Paths]
           [java.nio.file.attribute FileAttribute]))

(defn supported? []
  (try
    (with-open [_ (ServerSocketChannel/open StandardProtocolFamily/UNIX)] true)
    (catch UnsupportedOperationException _ false)))

(defn with-server [handler f]
  ;; Keep paths short enough for macOS's Unix socket path limit.
  (let [root (Paths/get (if (Files/isDirectory (Paths/get "/tmp" (make-array String 0))
                                             (make-array java.nio.file.LinkOption 0))
                         "/tmp" (System/getProperty "java.io.tmpdir"))
                       (make-array String 0))
        dir (Files/createTempDirectory root "ws-" (make-array FileAttribute 0))
        path (.resolve dir "s")
        accepted (promise)
        done (promise)]
    (with-open [server (ServerSocketChannel/open StandardProtocolFamily/UNIX)]
      (.bind server (UnixDomainSocketAddress/of path))
      (let [thread (doto (Thread. ^Runnable
                                 (bound-fn []
                                   (try
                                     (with-open [channel (.accept server)]
                                       (deliver accepted channel)
                                       (handler channel))
                                     (deliver done nil)
                                     (catch Throwable e (deliver done e))))
                                 "unix-websocket-test-server")
                     (.setDaemon true)
                     (.start))]
        (try
          (f (str path) done)
          (finally
            (.close server)
            (when (realized? accepted) (.close ^java.nio.channels.SocketChannel @accepted))
            (.join thread 2000)
            (Files/deleteIfExists path)
            (Files/deleteIfExists dir)))))))
