(ns codex.impl.websocket.unix-test
  (:require [codex.impl.websocket.unix :as ws]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [org.httpkit.server :as httpkit])
  (:import [java.io ByteArrayOutputStream EOFException]
           [java.net InetSocketAddress]
           [java.nio ByteBuffer]
           [java.nio.channels SocketChannel]
           [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [java.util Base64]
           [java.util.concurrent CompletableFuture TimeUnit]))

(use-fixtures :once
  (fn [run]
    (if (and (resolve 'java.net.UnixDomainSocketAddress)
             ((requiring-resolve 'codex.impl.websocket.unix-socket-fixture/supported?)))
      (run)
      (println "Skipping Unix WebSocket integration tests: Unix sockets are unavailable"))))

(defn unix-workers []
  (filter #(str/starts-with? (.getName ^Thread %) "codex-unix-websocket-")
          (keys (Thread/getAllStackTraces))))

(use-fixtures :each
  (fn [run]
    (try
      (run)
      (finally
        (let [deadline (+ (System/nanoTime) 2000000000)]
          (loop []
            (when (and (seq (unix-workers)) (< (System/nanoTime) deadline))
              (Thread/sleep 10)
              (recur)))
          (is (empty? (unix-workers)) "Unix WebSocket workers must terminate"))))))

(defn with-server [handler f]
  ((requiring-resolve 'codex.impl.websocket.unix-socket-fixture/with-server) handler f))

(defn await! [x]
  (if (instance? CompletableFuture x)
    (.get ^CompletableFuture x 3 TimeUnit/SECONDS)
    (let [result (deref x 3000 ::timeout)]
      (when (= ::timeout result) (throw (ex-info "Test timed out" {})))
      (when (instance? Throwable result) (throw result))
      result)))

(defn read-bytes! [^SocketChannel channel n]
  (let [buffer (ByteBuffer/allocate n)]
    (while (.hasRemaining buffer)
      (when (neg? (.read channel buffer)) (throw (EOFException.))))
    (.array buffer)))

(defn write-bytes! [^SocketChannel channel data]
  (let [buffer (ByteBuffer/wrap ^bytes data)]
    (while (.hasRemaining buffer) (.write channel buffer))))

(defn read-request! [channel]
  (let [out (ByteArrayOutputStream.)]
    (loop []
      (.write out ^bytes (read-bytes! channel 1))
      (let [s (.toString out "ISO-8859-1")]
        (if (str/ends-with? s "\r\n\r\n") s (recur))))))

(defn response [request & [extra-headers]]
  (let [key (second (re-find #"Sec-WebSocket-Key: ([^\r]+)" request))
        accept (.encodeToString (Base64/getEncoder)
                                (.digest (MessageDigest/getInstance "SHA-1")
                                         (.getBytes (str key "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
                                                    StandardCharsets/ISO_8859_1)))]
    (str "HTTP/1.1 101 Switching Protocols\r\n"
         "Upgrade: websocket\r\nConnection: keep-alive, Upgrade\r\n"
         "Sec-WebSocket-Accept: " accept "\r\n" extra-headers "\r\n")))

(defn upgrade! [channel & [extra-headers]]
  (let [request (read-request! channel)]
    (write-bytes! channel (.getBytes (response request extra-headers) StandardCharsets/ISO_8859_1))
    request))

(defn frame-bytes [opcode fin? payload]
  (let [^bytes payload (if (string? payload) (.getBytes ^String payload StandardCharsets/UTF_8) payload)
        n (alength payload)
        buffer (ByteBuffer/allocate (+ 10 n))]
    (.put buffer (unchecked-byte (bit-or opcode (if fin? 128 0))))
    (cond
      (< n 126) (.put buffer (byte n))
      (< n 65536) (do (.put buffer (byte 126)) (.putShort buffer (unchecked-short n)))
      :else (do (.put buffer (byte 127)) (.putLong buffer (long n))))
    (.put buffer payload)
    (java.util.Arrays/copyOf (.array buffer) (.position buffer))))

(defn write-frame! [channel opcode fin? payload]
  (write-bytes! channel (frame-bytes opcode fin? payload)))

(defn read-frame! [channel]
  (let [[a b] (map #(bit-and 255 %) (read-bytes! channel 2))
        _ (assert (bit-test b 7) "Clients must mask frames")
        n (case (bit-and b 127)
            126 (bit-and 65535 (.getShort (ByteBuffer/wrap (read-bytes! channel 2))))
            127 (.getLong (ByteBuffer/wrap (read-bytes! channel 8)))
            (bit-and b 127))
        mask (read-bytes! channel 4)
        payload (read-bytes! channel n)]
    (dotimes [i n]
      (aset-byte payload i (unchecked-byte (bit-xor (aget payload i) (aget mask (mod i 4))))))
    {:opcode (bit-and a 15) :fin? (bit-test a 7) :payload payload}))

(defn text-payload [frame]
  (String. ^bytes (:payload frame) StandardCharsets/UTF_8))

(defn connection [path opts]
  (ws/websocket (merge {:uri "ws://localhost/events" :unix-socket path :connect-timeout 2000} opts)))

(defn echo-close! [channel]
  (let [{:keys [opcode payload]} (read-frame! channel)]
    (assert (= 8 opcode))
    (write-frame! channel 8 true payload)))

(deftest handshake-and-callback-send-test
  (let [request (promise) received (promise) closed (promise)]
    (with-server
      (fn [channel]
        (deliver request (upgrade! channel "Sec-WebSocket-Protocol: events\r\n"))
        (let [frame (read-frame! channel)]
          (write-frame! channel 1 true (:payload frame)))
        (echo-close! channel))
      (fn [path done]
        (let [result (connection path {:uri {:scheme "ws" :host "no-dns.invalid"
                                             :path "/events/a b" :query "q=a+b"}
                                       :headers {:authorization "Bearer example"}
                                       :subprotocols ["events"] :async true
                                       :on-open (fn [socket] (await! (ws/send! socket "hello")))
                                       :on-message (fn [socket data last?]
                                                     (deliver received [socket data last?]))
                                       :on-close (fn [_ code reason] (deliver closed [code reason]))})
              socket (await! result)]
          (try
            (is (instance? CompletableFuture result))
            (is (not (instance? java.net.http.WebSocket socket)))
            (is (= [socket "hello" true] (await! received)))
            (is (str/starts-with? (await! request) "GET /events/a%20b?q=a+b HTTP/1.1\r\n"))
            (is (str/includes? @request "Host: no-dns.invalid\r\n"))
            (is (str/includes? @request "authorization: Bearer example\r\n"))
            (is (identical? socket (await! (ws/close! socket))))
            (is (= [1000 ""] (await! closed)))
            (await! done)
            (finally (ws/abort! socket))))))))

(deftest fragmentation-and-control-test
  (let [messages (atom []) ping (promise) pong (promise) finished (promise)]
    (with-server
      (fn [channel]
        (upgrade! channel)
        ;; Split the UTF-8 euro sign between data frames and interleave a ping.
        (write-frame! channel 1 false (byte-array [(unchecked-byte 0xe2)]))
        (write-frame! channel 9 true "ping")
        (let [frame (read-frame! channel)]
          (is (= [10 "ping"] [(:opcode frame) (text-payload frame)])))
        (write-frame! channel 0 true (byte-array [(unchecked-byte 0x82) (unchecked-byte 0xac)]))
        (let [frame (read-frame! channel)]
          (is (= [9 "outgoing"] [(:opcode frame) (text-payload frame)]))
          (write-frame! channel 10 true (:payload frame)))
        (let [a (read-frame! channel) b (read-frame! channel)]
          (is (= [1 false "a"] [(:opcode a) (:fin? a) (text-payload a)]))
          (is (= [0 true "b"] [(:opcode b) (:fin? b) (text-payload b)])))
        (echo-close! channel))
      (fn [path done]
        (let [socket (connection path {:on-message (fn [_ data last?]
                                                     (swap! messages conj [data last?])
                                                     (when last? (deliver finished true)))
                                       :on-ping (fn [_ data] (deliver ping (.remaining ^ByteBuffer data)))
                                       :on-pong (fn [_ data] (deliver pong (.remaining ^ByteBuffer data)))})]
          (try
            (await! finished)
            (is (= "€" (apply str (map first @messages))))
            (is (= [false true] (mapv second @messages)))
            (is (= 4 (await! ping)))
            (await! (ws/ping! socket "outgoing"))
            (is (= 8 (await! pong)))
            (await! (ws/send! socket "a" {:last false}))
            (is (thrown? java.util.concurrent.ExecutionException
                         (await! (ws/send! socket (byte-array [1])))))
            (await! (ws/send! socket "b"))
            (await! (ws/close! socket))
            (await! done)
            (finally (ws/abort! socket))))))))

(deftest binary-and-extended-lengths-test
  (doseq [n [0 125 126 65535 65536]]
    (let [received (ByteArrayOutputStream.) finished (promise)]
      (with-server
        (fn [channel]
          (upgrade! channel)
          (let [frame (read-frame! channel)]
            (is (= 2 (:opcode frame)))
            (is (= n (alength ^bytes (:payload frame))))
            (write-frame! channel 2 true (:payload frame)))
          (echo-close! channel))
        (fn [path done]
          (let [socket (connection path {:on-message (fn [_ data last?]
                                                       (let [b (byte-array (.remaining ^ByteBuffer data))]
                                                         (.get ^ByteBuffer data b)
                                                         (.write received b))
                                                       (when last? (deliver finished true)))})
                buffer (ByteBuffer/wrap (byte-array (repeat (+ n 2) (byte 42))))]
            (try
              (.position buffer 1)
              (.limit buffer (inc n))
              (await! (ws/send! socket buffer))
              (is (zero? (.remaining buffer)))
              (await! finished)
              (is (= (vec (repeat n 42)) (vec (.toByteArray received))))
              (await! (ws/close! socket))
              (await! done)
              (finally (ws/abort! socket)))))))))

(deftest handshake-and-first-frame-in-one-write-test
  (let [message (promise) closed (promise)]
    (with-server
      (fn [channel]
        (let [request (read-request! channel) out (ByteArrayOutputStream.)]
          (.write out (.getBytes (response request) StandardCharsets/ISO_8859_1))
          (.write out ^bytes (frame-bytes 1 true "ready"))
          (.write out ^bytes (frame-bytes 8 true (byte-array [3 (unchecked-byte 232)])))
          (write-bytes! channel (.toByteArray out)))
        (is (= 8 (:opcode (read-frame! channel)))))
      (fn [path done]
        (let [socket (connection path {:on-message (fn [_ data _] (deliver message data))
                                       :on-close (fn [_ status _] (deliver closed status))})]
          (try
            (is (= "ready" (await! message)))
            (is (= 1000 (await! closed)))
            (is (thrown? java.util.concurrent.ExecutionException (await! (ws/send! socket "late"))))
            (await! done)
            (finally (ws/abort! socket))))))))

(deftest invalid-handshake-test
  (doseq [transform [#(str/replace % "101 Switching Protocols" "403 Forbidden")
                     #(str/replace % #"Sec-WebSocket-Accept: [^\r]+" "Sec-WebSocket-Accept: wrong")
                     #(str/replace % "Upgrade: websocket" "Upgrade: other")
                     #(str/replace % "Connection: keep-alive, Upgrade" "Connection: close")
                     #(str/replace % "\r\n\r\n" "\r\nSec-WebSocket-Protocol: unsolicited\r\n\r\n")
                     #(str/replace % "\r\n\r\n" "\r\nSec-WebSocket-Extensions: permessage-deflate\r\n\r\n")]]
    (with-server
      (fn [channel]
        (write-bytes! channel (.getBytes ^String (transform (response (read-request! channel)))
                                         StandardCharsets/ISO_8859_1)))
      (fn [path done]
        (is (thrown? java.util.concurrent.ExecutionException (connection path {})))
        (await! done)))))

(deftest malformed-frames-test
  (doseq [frame [(byte-array [(unchecked-byte 0xc1) 0]) ; reserved bit
                 (byte-array [(unchecked-byte 0x81) (unchecked-byte 0x80)]) ; masked server
                 (byte-array [(unchecked-byte 0x83) 0]) ; reserved opcode
                 (byte-array [9 0]) ; fragmented ping
                 (byte-array [(unchecked-byte 0x89) 126 0 126]) ; oversized ping
                 (byte-array [(unchecked-byte 0x80) 0]) ; unsolicited continuation
                 (byte-array [(unchecked-byte 0x81) 126 0 1]) ; nonminimal length
                 (byte-array [(unchecked-byte 0x82) 127 (unchecked-byte 128) 0 0 0 0 0 0 0])
                 (frame-bytes 1 true (byte-array [(unchecked-byte 0xff)]))
                 (frame-bytes 8 true (byte-array [0]))
                 (frame-bytes 8 true (byte-array [3 (unchecked-byte 237)]))]] ; reserved close 1005
    (let [error (promise)]
      (with-server
        (fn [channel]
          (upgrade! channel)
          (write-bytes! channel frame)
          (is (= 8 (:opcode (read-frame! channel)))))
        (fn [path done]
          (let [socket (connection path {:on-error (fn [_ e] (deliver error e))})]
            (try
              (is (instance? Throwable (deref error 3000 nil)))
              (await! done)
              (finally (ws/abort! socket)))))))))

(deftest timeout-and-cancellation-test
  (doseq [cancel? [false true]]
    (let [accepted (promise) disconnected (promise)]
      (with-server
        (fn [channel]
          (read-request! channel)
          (deliver accepted true)
          (deliver disconnected (.read ^SocketChannel channel (ByteBuffer/allocate 1))))
        (fn [path done]
          (let [result (connection path {:async true :connect-timeout (if cancel? 2000 100)})]
            (await! accepted)
            (if cancel?
              (is (.cancel ^CompletableFuture result true))
              (try
                (await! result)
                (is false "The handshake must time out")
                (catch java.util.concurrent.ExecutionException e
                  (is (instance? java.net.http.HttpTimeoutException (.getCause e))))))
            (is (= -1 (await! disconnected)))
            (await! done)))))))

(deftest abort-callback-error-and-disconnect-test
  (doseq [mode [:abort :callback :disconnect]]
    (let [terminal (promise) disconnected (promise) calls (atom 0)]
      (with-server
        (fn [channel]
          (upgrade! channel)
          (when (= :callback mode) (write-frame! channel 1 true "throw"))
          (when-not (= :disconnect mode)
            (deliver disconnected (.read ^SocketChannel channel (ByteBuffer/allocate 1)))))
        (fn [path done]
          (let [socket (connection path {:on-message (fn [& _] (throw (ex-info "callback failed" {})))
                                         :on-error (fn [_ e] (swap! calls inc) (deliver terminal e))})]
            (try
              (if (= :abort mode)
                (do (is (nil? (ws/abort! socket))) (is (nil? (ws/abort! socket))))
                (is (instance? Throwable (deref terminal 3000 nil))))
              (when-not (= :disconnect mode) (is (= -1 (await! disconnected))))
              (await! done)
              (is (= (if (= :abort mode) 0 1) @calls))
              (finally (ws/abort! socket)))))))))

(deftest invalid-options-and-missing-socket-test
  (doseq [opts [{:unix-socket nil} {:unix-socket ""} {:uri "wss://localhost/"}
                {:uri "ws://localhost/#fragment"} {:client :custom} {:client false}
                {:connect-timeout 0} {:connect-timeout false}
                {:headers {:foo "bar\r\nInjected: true"}}
                {:headers {:host "override"}} {:subprotocols ["duplicate" "duplicate"]}]]
    (is (thrown? IllegalArgumentException (connection "/missing/socket" opts))))
  (is (thrown? java.util.concurrent.ExecutionException (connection "/missing/socket" {}))))

(deftest invalid-sends-do-not-close-connection-test
  (with-server
    (fn [channel]
      (upgrade! channel)
      (is (= "still open" (text-payload (read-frame! channel))))
      (let [pong (read-frame! channel)]
        (is (= [10 "manual"] [(:opcode pong) (text-payload pong)])))
      (echo-close! channel))
    (fn [path done]
      (let [socket (connection path {})]
        (try
          (doseq [result [(ws/ping! socket (byte-array 126))
                          (ws/pong! socket (byte-array 126))
                          (ws/close! socket 1005 "")
                          (ws/close! socket 1002 "")
                          (ws/close! socket 1000 (apply str (repeat 124 "a")))
                          (ws/send! socket (String. (char-array [(char 0xd800)])))]]
            (is (instance? CompletableFuture result))
            (is (thrown? java.util.concurrent.ExecutionException (await! result))))
          (await! (ws/send! socket "still open"))
          (await! (ws/pong! socket "manual"))
          (await! (ws/close! socket))
          (await! done)
          (finally (ws/abort! socket)))))))

(deftest pending-sends-and-abort-test
  (let [release (promise)]
    (with-server
      (fn [channel]
        (upgrade! channel)
        (await! release)
        ;; Drain bytes buffered before abort, then observe EOF.
        (let [buffer (ByteBuffer/allocate 8192)]
          (loop []
            (.clear buffer)
            (when-not (neg? (.read ^SocketChannel channel buffer)) (recur)))))
      (fn [path done]
        (let [socket (connection path {})]
          (try
            (let [send (ws/send! socket (byte-array (* 8 1024 1024)))
                  ping (ws/ping! socket "queued")]
              (is (not (.isDone ^CompletableFuture send)))
              (is (thrown? java.util.concurrent.ExecutionException
                           (await! (ws/send! socket "concurrent"))))
              (is (thrown? java.util.concurrent.ExecutionException
                           (await! (ws/pong! socket "concurrent"))))
              (ws/abort! socket)
              (is (thrown? java.util.concurrent.ExecutionException (await! send)))
              (is (thrown? java.util.concurrent.ExecutionException (await! ping))))
            (finally
              (ws/abort! socket)
              (deliver release true)))
          (await! done))))))

(deftest incomplete-and-oversized-handshake-test
  (doseq [oversized? [false true]]
    (with-server
      (fn [channel]
        (read-request! channel)
        (try
          (write-bytes! channel (.getBytes (if oversized?
                                             (str "HTTP/1.1 101 OK\r\nX-Large: "
                                                  (apply str (repeat 65536 "x")))
                                             "HTTP/1.1 101")
                                           StandardCharsets/ISO_8859_1))
          (catch java.io.IOException _)))
      (fn [path done]
        (is (thrown? java.util.concurrent.ExecutionException (connection path {})))
        (await! done)))))

(deftest partial-reads-and-empty-close-test
  (let [received (promise) closed (promise)]
    (with-server
      (fn [channel]
        (let [request (read-request! channel)
              out (ByteArrayOutputStream.)]
          (.write out (.getBytes (response request) StandardCharsets/ISO_8859_1))
          (.write out ^bytes (frame-bytes 1 true "small pieces"))
          (.write out ^bytes (frame-bytes 8 true (byte-array 0)))
          (doseq [b (.toByteArray out)] (write-bytes! channel (byte-array [b]))))
        (let [close (read-frame! channel)]
          (is (= 8 (:opcode close)))
          (is (zero? (alength ^bytes (:payload close))))))
      (fn [path done]
        (let [socket (connection path {:on-message (fn [_ data _] (deliver received data))
                                       :on-close (fn [_ code reason] (deliver closed [code reason]))})]
          (try
            (is (= "small pieces" (await! received)))
            (is (= [1005 ""] (await! closed)))
            (await! done)
            (finally (ws/abort! socket))))))))

(deftest on-open-failure-test
  (let [disconnected (promise) error (promise)]
    (with-server
      (fn [channel]
        (upgrade! channel)
        (deliver disconnected (.read ^SocketChannel channel (ByteBuffer/allocate 1))))
      (fn [path done]
        (is (thrown? java.util.concurrent.ExecutionException
                     (connection path {:on-open (fn [_] (throw (ex-info "open failed" {})))
                                       :on-error (fn [_ e] (deliver error (.getMessage ^Throwable e)))})))
        (is (= "open failed" (await! error)))
        (is (= -1 (await! disconnected)))
        (await! done)))))

(deftest binary-fragmentation-test
  (let [messages (atom []) received (promise)]
    (with-server
      (fn [channel]
        (upgrade! channel)
        (doseq [[opcode fin?] [[2 false] [0 true]]]
          (let [frame (read-frame! channel)]
            (is (= [opcode fin?] [(:opcode frame) (:fin? frame)]))
            (write-frame! channel opcode fin? (:payload frame))))
        (echo-close! channel))
      (fn [path done]
        (let [socket (connection path {:on-message
                                       (fn [_ data last?]
                                         (let [b (byte-array (.remaining ^ByteBuffer data))]
                                           (.get ^ByteBuffer data b)
                                           (swap! messages conj [(vec b) last?]))
                                         (when last? (deliver received true)))})]
          (try
            (await! (ws/send! socket (byte-array [1 2]) {:last false}))
            (await! (ws/send! socket (byte-array [3 4])))
            (await! received)
            (is (= [[[1 2] false] [[3 4] true]] @messages))
            (await! (ws/close! socket))
            (await! done)
            (finally (ws/abort! socket))))))))

(deftest large-advertised-frame-is-streamed-test
  (let [received (promise) disconnected (promise)]
    (with-server
      (fn [channel]
        (upgrade! channel)
        ;; A frame larger than an int must not cause a whole-frame allocation.
        (let [header (doto (ByteBuffer/allocate 10)
                       (.put (unchecked-byte 130)) (.put (byte 127))
                       (.putLong 4294967297))]
          (write-bytes! channel (.array header))
          (write-bytes! channel (byte-array 8192)))
        (deliver disconnected (.read ^SocketChannel channel (ByteBuffer/allocate 1))))
      (fn [path done]
        (let [socket (connection path {:on-message (fn [_ data last?]
                                                     (deliver received [(.remaining ^ByteBuffer data) last?]))})]
          (try
            (is (= [8192 false] (await! received)))
            (ws/abort! socket)
            (is (= -1 (await! disconnected)))
            (is (.exists (java.io.File. path)) "The client must not delete the server socket")
            (await! done)
            (finally (ws/abort! socket))))))))

(deftest ping-during-close-test
  (let [closed (promise) pings (atom 0)]
    (with-server
      (fn [channel]
        (upgrade! channel)
        (let [close (read-frame! channel)]
          (is (= 8 (:opcode close)))
          (write-frame! channel 9 true "closing")
          (write-frame! channel 8 true (:payload close))))
      (fn [path done]
        (let [socket (connection path {:on-ping (fn [& _] (swap! pings inc))
                                       :on-close (fn [_ code _] (deliver closed code))
                                       :on-error (fn [_ error] (deliver closed error))})]
          (try
            (await! (ws/close! socket))
            (is (= 1000 (await! closed)))
            (is (= 1 @pings))
            (await! done)
            (finally (ws/abort! socket))))))))

(deftest httpkit-interoperability-test
  (let [received (promise)
        server (httpkit/run-server
                (fn [request]
                  (httpkit/as-channel request
                                      {:on-receive (fn [channel data] (httpkit/send! channel data))}))
                {:port 0 :legacy-return-value? false})
        port (httpkit/server-port server)]
    (try
      (with-server
        (fn [unix]
          (with-open [tcp (SocketChannel/open (InetSocketAddress. "127.0.0.1" (int port)))]
            (let [copy (fn [^SocketChannel from ^SocketChannel to]
                         (let [b (ByteBuffer/allocate 8192)]
                           (loop []
                             (.clear b)
                             (when-not (neg? (.read from b))
                               (.flip b)
                               (while (.hasRemaining b) (.write to b))
                               (recur)))))
                  upstream (doto (Thread. ^Runnable #(try (copy unix tcp) (catch Exception _)))
                             (.setDaemon true) (.start))]
              (try (copy tcp unix)
                   (finally (.close tcp) (.close ^SocketChannel unix) (.join upstream 2000))))))
        (fn [path _]
          (let [socket (connection path {:on-message (fn [_ data _] (deliver received data))})]
            (try
              (await! (ws/send! socket "http-kit echo"))
              (is (= "http-kit echo" (await! received)))
              (await! (ws/close! socket))
              (finally (ws/abort! socket))))))
      (finally (httpkit/server-stop! server)))))
