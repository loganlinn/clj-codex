(ns codex.unix-transport-test
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [codex.app-server :as server]
            [codex.impl.websocket.unix-test :as peer])
  (:import [java.io EOFException]
           [java.net UnixDomainSocketAddress]
           [java.nio.channels SocketChannel]
           [java.nio.file Files Paths]
           [java.util Arrays UUID]))

(use-fixtures :each
  (fn [run]
    (try (run)
         (finally
           (doseq [^Thread worker (peer/unix-workers)] (.join worker 2000))
           (is (empty? (peer/unix-workers)) "SDK disposal must release Unix workers")))))

(defn read-json! [channel]
  (let [frame (peer/read-frame! channel)]
    (is (= 1 (:opcode frame)))
    (is (:fin? frame))
    (json/parse-string (peer/text-payload frame))))

(defn write-json! [channel data]
  (peer/write-frame! channel 1 true (json/generate-string data)))

(defn initialize! [channel]
  (peer/upgrade! channel)
  (let [request (read-json! channel)]
    (is (= "initialize" (get request "method")))
    (write-json! channel {"id" (get request "id") "result" {"userAgent" "unix-fixture"}}))
  (is (= "initialized" (get (read-json! channel) "method"))))

(defn connect! [path & [opts]]
  (server/connect!
    (merge {:transport {:type :websocket :url "ws://no-dns.invalid/rpc?q=1"
                       :unix-socket path :connect-timeout-ms 2000}
            :request-timeout-ms 2000} opts)))

(defn drain-until-eof! [channel]
  (try (loop [] (peer/read-frame! channel) (recur))
       (catch EOFException _ true)))

(deftest unix-sdk-roundtrip-and-ownership
  (let [headers (promise) notification (promise) reply (promise)]
    (peer/with-server
      (fn [channel]
        (deliver headers (peer/upgrade! channel))
        (let [request (read-json! channel)]
          (is (= "initialize" (get request "method")))
          (write-json! channel {"id" (get request "id") "result" {"userAgent" "unix-fixture"}}))
        (is (= "initialized" (get (read-json! channel) "method")))
        (let [request (read-json! channel)]
          (write-json! channel {"method" "fixture/notice" "params" {"ok" true}})
          (write-json! channel {"id" "server-1" "method" "fixture/question" "params" {"answer" 42}})
          (deliver reply (read-json! channel))
          ;; Split inside the four-byte UTF-8 character, not just between strings.
          (let [bytes (.getBytes (json/generate-string {"id" (get request "id") "result" (get request "params")}) "UTF-8")
                split (inc (first (keep-indexed #(when (= 0xf0 (bit-and 255 %2)) %1) bytes)))]
            (peer/write-frame! channel 1 false (Arrays/copyOfRange bytes 0 split))
            (peer/write-frame! channel 0 true (Arrays/copyOfRange bytes split (alength bytes)))))
        (drain-until-eof! channel))
      (fn [path done]
        (let [token-calls (atom 0)
              c (connect! path
                  {:transport {:type :websocket :url "ws://no-dns.invalid/rpc?q=1"
                               :unix-socket path :connect-timeout-ms 2000
                               :headers {"X-Fixture" "sdk"}
                               :token-fn #(do (swap! token-calls inc) "fixture-token")}
                   :handlers {"fixture/question" (fn [request] {:result (:params request)})}})
              observer (server/listen! c #(when (= "fixture/notice" (get % "method")) (deliver notification %)))]
          (try
            (is (= :ready (server/status c)))
            (is (= "unix-fixture" (get-in (server/info c) [:server "userAgent"])))
            (is (= 1 @token-calls))
            (is (str/starts-with? (peer/await! headers) "GET /rpc?q=1 HTTP/1.1\r\n"))
            (is (str/includes? @headers "Host: no-dns.invalid\r\n"))
            (is (str/includes? @headers "Authorization: Bearer fixture-token\r\n"))
            (is (str/includes? @headers "X-Fixture: sdk\r\n"))
            (is (= {"text" "split 🌱 and λ"}
                   (server/await! (server/request! c "echo" {"text" "split 🌱 and λ"}) 3000 ::timeout)))
            (is (= {"id" "server-1" "result" {"answer" 42}} (peer/await! reply)))
            (is (= {"ok" true} (get (peer/await! notification) "params")))
            (finally (server/unlisten! observer) (server/close! c) (server/close! c)))
          (peer/await! done)
          (is (= :closed (server/status c)))
          (is (Files/exists (Paths/get path (make-array String 0)) (make-array java.nio.file.LinkOption 0)))
          ;; The external listener still accepts connections after SDK disposal.
          (with-open [socket (SocketChannel/open (UnixDomainSocketAddress/of path))]
            (is (.isConnected socket))))))))

(deftest unix-disconnect-fails-pending-call
  (doseq [graceful? [false true]]
    (peer/with-server
      (fn [channel]
        (initialize! channel)
        (read-json! channel)
        (when graceful?
          (peer/write-frame! channel 8 true (byte-array [3 (unchecked-byte 232)]))
          (drain-until-eof! channel)))
      (fn [path done]
        (let [c (connect! path)]
          (try
            (let [pending (server/request! c "never-replied" {} {:timeout-ms nil})]
              (is (thrown? Exception (server/await! pending 3000 ::timeout)))
              (is (= :failed (server/status c)))
              (is (empty? @(:pending c))))
            (peer/await! done)
            (finally (server/close! c) (server/close! c))))))))

(deftest unix-local-disposal-fails-pending-call
  (let [requested (promise)]
    (peer/with-server
      (fn [channel]
        (initialize! channel)
        (deliver requested (read-json! channel))
        (drain-until-eof! channel))
      (fn [path done]
        (let [c (connect! path)]
          (try
            (let [pending (server/request! c "never-replied" {} {:timeout-ms nil})]
              (peer/await! requested)
              (server/close! c)
              (server/close! c)
              (is (thrown? clojure.lang.ExceptionInfo (server/await! pending 3000 ::timeout)))
              (is (= :closed (server/status c)))
              (is (empty? @(:pending c))))
            (peer/await! done)
            (finally (server/close! c))))))))

(deftest unix-rejects-malformed-messages-and-releases-socket
  (doseq [[description opcode payload] [["invalid JSON" 1 "{"]
                                        ["trailing garbage" 1 "{\"method\":\"fixture/notice\"}garbage"]
                                        ["multiple JSON values" 1 "{\"method\":\"fixture/notice\"} {}"]
                                        ["binary JSON" 2 "{}"]
                                        ["non-object JSON" 1 "[]"]
                                        ["invalid RPC" 1 "{}"]]]
    (testing description
      (peer/with-server
        (fn [channel]
          (initialize! channel)
          (read-json! channel)
          (peer/write-frame! channel opcode true payload)
          (drain-until-eof! channel))
        (fn [path done]
          (let [c (connect! path) error (promise)
                observer (server/listen! c {:on-error #(deliver error %)} (fn [_]))]
            (try
              (is (thrown? Exception
                    (server/await! (server/request! c "bad-response" {} {:timeout-ms nil}) 3000 ::timeout)))
              (is (instance? Throwable (deref error 3000 nil)))
              (is (= :failed (server/status c)))
              ;; EOF must arrive before explicitly disposing the SDK connection.
              (peer/await! done)
              (finally (server/unlisten! observer) (server/close! c) (server/close! c)))))))))

(deftest unix-early-close-and-malformed-message
  (doseq [[opcode data] [[1 "{"] [2 "{}"] [8 (byte-array [3 (unchecked-byte 232)])]]]
    (peer/with-server
      (fn [channel]
        (peer/upgrade! channel)
        ;; Terminal callback may run before open! returns its transport handle.
        (peer/write-frame! channel opcode true data)
        (drain-until-eof! channel))
      (fn [path done]
        (is (thrown? Exception (connect! path)))
        (peer/await! done)))))

(deftest unix-connect-timeout-and-missing-socket
  (peer/with-server
    (fn [channel] (peer/read-request! channel) (drain-until-eof! channel))
    (fn [path done]
      (let [start (System/nanoTime)]
        (is (thrown? Exception
              (connect! path {:transport {:type :websocket :url "ws://localhost/"
                                         :unix-socket path :connect-timeout-ms 100}})))
        (is (< (/ (- (System/nanoTime) start) 1e6) 3000))
        (peer/await! done))))
  (is (thrown? Exception (connect! (str "/tmp/missing-" (UUID/randomUUID))))))

(deftest unix-invalid-options
  (doseq [transport [{:type :stdio :unix-socket "/unused"}
                    {:type :websocket :url "ws://localhost/" :unix-socket nil}
                    {:type :websocket :url "ws://localhost/" :unix-socket ""}
                    {:type :websocket :url "ws://localhost/" :unix-socket 123}
                    {:type :websocket :unix-socket "/unused"}
                    {:type :websocket :url "wss://localhost/" :unix-socket "/unused"}
                    {:type :websocket :url "ws://localhost/" :unix-socket "/unused" :connect-timeout-ms 0}
                    {:type :websocket :url "ws://localhost/" :unix-socket "/unused" :connect-timeout-ms nil}
                    {:type :websocket :url "ws://localhost/" :unix-socket "/unused" :headers {"Host" "override"}}]]
    (testing (pr-str transport)
      (is (thrown? Exception (server/connect! {:transport transport}))))))
