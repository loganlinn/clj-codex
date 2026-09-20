(ns codex.unix-smoke
  "Opt-in live test. Owns one isolated Codex process, home, and Unix socket."
  (:require [codex.app-server :as server])
  (:import [java.nio.file Files Paths]
           [java.nio.file.attribute FileAttribute]
           [java.util.concurrent TimeUnit]))

(defn- check! [condition message]
  (when-not condition (throw (ex-info message {}))))

(defn- read-loaded! [path]
  (let [c (server/connect!
           {:transport {:type :websocket :url "ws://localhost/"
                        :unix-socket path :connect-timeout-ms 5000}
            :request-timeout-ms 5000})]
    (try
      (check! (= :ready (server/status c)) "Initialization failed")
      (let [result (server/await! (server/request! c "thread/loaded/list" {}))]
        (check! (= [] (get result "data")) "Isolated server should have no loaded threads")
        result)
      (finally (server/close! c) (server/close! c)))))

(defn -main [& [codex-bin]]
  (let [root (Files/createTempDirectory (Paths/get "/tmp" (make-array String 0))
                                        "cxu-" (make-array FileAttribute 0))
        home (.resolve root "home")
        socket (.resolve root "s")
        log (.toFile (.resolve root "server.log"))
        child (atom nil)]
    (try
      (Files/createDirectory home (make-array FileAttribute 0))
      (let [builder (ProcessBuilder. ^java.util.List [(or codex-bin "codex") "app-server"
                                                      "--listen" (str "unix://" socket)])]
        (.put (.environment builder) "CODEX_HOME" (str home))
        (.directory builder (.toFile root))
        (.redirectErrorStream builder true)
        (.redirectOutput builder log)
        (reset! child (.start builder)))
      (let [^Process process @child deadline (+ (System/nanoTime) 10000000000)]
        (loop []
          (cond
            (not (.isAlive process)) (throw (ex-info "Codex exited before listening" {:log (slurp log)}))
            (Files/exists socket (make-array java.nio.file.LinkOption 0)) nil
            (> (System/nanoTime) deadline) (throw (ex-info "Timed out waiting for Codex socket" {:log (slurp log)}))
            :else (do (Thread/sleep 25) (recur))))
        (println "initialize/initialized and thread/loaded/list:" (pr-str (read-loaded! (str socket))))
        (check! (.isAlive process) "Closing the client stopped the external server")
        (check! (Files/exists socket (make-array java.nio.file.LinkOption 0)) "Client removed the server socket")
        ;; Reconnect to prove the owned server still accepts clients after disposal.
        (read-loaded! (str socket))
        (check! (.isAlive process) "Server stopped after second client disposal")
        (println "Server alive and reconnect succeeded after client disposal."))
      (finally
        (when-let [^Process process @child]
          (.destroy process)
          (when-not (.waitFor process 5 TimeUnit/SECONDS)
            (.destroyForcibly process)
            (check! (.waitFor process 5 TimeUnit/SECONDS) "Could not stop the smoke-test server")))
        ;; Only the unique directory created above is removed, after its process exits.
        (with-open [paths (Files/walk root (make-array java.nio.file.FileVisitOption 0))]
          ;; Do not follow symlinks while removing the isolated home.
          (doseq [path (reverse (vec (iterator-seq (.iterator paths))))]
            (Files/deleteIfExists path)))))
    (println "Smoke-test process and temporary directory cleaned up.")))
