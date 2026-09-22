(ns codex.transport-test
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [codex.app-server :as server]
            [codex.impl.util :as u])
  (:import [java.net ServerSocket] [java.io ByteArrayOutputStream]))

(deftest stdio-framing-and-stderr
  (let [bb? (System/getProperty "babashka.version")
        empty-config (doto (java.io.File/createTempFile "codex-test" ".edn") (.deleteOnExit))
        _ (spit empty-config "{}")
        command (if bb?
                  [(or (System/getenv "CODEX_TEST_BB") "bb") "--config" (str empty-config)
                   "--classpath" "src:resources:apis:test" "-m" "codex.fixture-server"]
                  [(str (System/getProperty "java.home") "/bin/java") "-cp" (System/getProperty "java.class.path")
                   "clojure.main" "-m" "codex.fixture-server"])
        stderr (promise)
        conn (server/connect! {:transport {:type :stdio :command command} :on-stderr #(deliver stderr %)})]
    (try
      (is (= "fixture" (get-in (server/info conn) [:server "userAgent"])))
      (is (string? (deref stderr 5000 nil)))
      (let [value {"text" "line one\nλ 🌱 line two" "nil" nil}]
        (is (= value (server/await! (server/request! conn "echo" value) 5000 ::timeout))))
      (finally (server/close! conn)))
    (is (= :closed (server/status conn)))))

(defn read-n [in n]
  (let [bytes (byte-array n)]
    (loop [offset 0]
      (when (< offset n)
        (let [read (.read ^java.io.InputStream in bytes offset (- n offset))]
          (when (neg? read) (throw (ex-info "EOF" {})))
          (recur (+ offset read)))))
    bytes))

(defn read-frame [in]
  (let [first-byte (.read ^java.io.InputStream in)
        second-byte (.read ^java.io.InputStream in)]
    (when (neg? first-byte) (throw (ex-info "EOF" {})))
    (let [short-size (bit-and second-byte 127)
          n (case short-size 126 (+ (bit-shift-left (.read ^java.io.InputStream in) 8) (.read ^java.io.InputStream in))
                  127 (throw (ex-info "Fixture frame too large" {})) short-size)
          mask (when (pos? (bit-and second-byte 128)) (read-n in 4))
          payload (read-n in n)]
      (when mask
        (dotimes [i n] (aset-byte payload i (unchecked-byte (bit-xor (aget payload i) (aget mask (mod i 4)))))))
      [(bit-and first-byte 15) payload])))

(defn write-frame! [out opcode last? bytes]
  (.write ^java.io.OutputStream out (int (bit-or opcode (if last? 128 0))))
  (let [n (alength bytes)]
    (if (< n 126) (.write ^java.io.OutputStream out (int n))
        (do (.write ^java.io.OutputStream out (int 126))
            (.write ^java.io.OutputStream out (int (bit-shift-right n 8)))
            (.write ^java.io.OutputStream out (int (bit-and n 255))))))
  (.write ^java.io.OutputStream out bytes)
  (.flush ^java.io.OutputStream out))

(defn websocket-peer []
  (let [listener (ServerSocket. 0) headers (promise) done (promise) socket (atom nil)]
    (u/worker!
     "codex-websocket-fixture"
     (fn []
       (try
         (with-open [client (.accept listener)]
           (reset! socket client)
           (let [in (.getInputStream client) out (.getOutputStream client)
                 header (loop [s ""]
                          (if (str/ends-with? s "\r\n\r\n") s
                              (let [n (.read in)]
                                (when (neg? n) (throw (ex-info "EOF" {})))
                                (recur (str s (char n))))))
                 _ (deliver headers header)
                 key (second (re-find #"(?im)^Sec-WebSocket-Key: ([^\r]+)" header))
                 sha (.digest (java.security.MessageDigest/getInstance "SHA-1")
                              (.getBytes (str key "258EAFA5-E914-47DA-95CA-C5AB0DC85B11") "UTF-8"))
                 accept (.encodeToString (java.util.Base64/getEncoder) sha)]
             (.write out (.getBytes (str "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " accept "\r\n\r\n") "UTF-8"))
             (.flush out)
             (loop []
               (let [[opcode payload] (read-frame in)]
                 (when (= opcode 1)
                   (let [message (json/parse-string (String. payload "UTF-8"))]
                     (when (contains? message "id")
                       (let [result (if (= "initialize" (get message "method")) {"userAgent" "ws-fixture"} (get message "params"))
                             response (.getBytes (json/generate-string {"id" (get message "id") "result" result}) "UTF-8")
                             split (quot (alength response) 2)]
                         ;; One message delivered as two protocol frames.
                         (write-frame! out 1 false (java.util.Arrays/copyOfRange response 0 split))
                         (write-frame! out 0 true (java.util.Arrays/copyOfRange response split (alength response)))))
                     (recur)))))))
         (catch Exception _ nil)
         (finally (deliver done true)))))
    {:url (str "ws://127.0.0.1:" (.getLocalPort listener)) :headers headers :done done
     :close! #(do (when @socket (.close ^java.net.Socket @socket)) (.close listener))}))

(deftest websocket-framing-and-authentication
  (let [peer (websocket-peer)]
    (try
      (let [conn (server/connect! {:transport {:type :websocket :url (:url peer) :token-fn (constantly "fixture-token")}})]
        (try
          (is (str/includes? (str/lower-case @(:headers peer)) "authorization: bearer fixture-token"))
          (is (= {"text" "Unicode λ and 🌱"}
                 (server/await! (server/request! conn "echo" {"text" "Unicode λ and 🌱"}) 5000 ::timeout)))
          (finally (server/close! conn))))
      (finally ((:close! peer))))
    (is (= true (deref (:done peer) 5000 ::timeout)))))
