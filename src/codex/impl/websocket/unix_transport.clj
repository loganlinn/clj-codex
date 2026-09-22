(ns codex.impl.websocket.unix-transport
  "Direct WebSockets over filesystem Unix sockets. Loaded only on demand."
  {:no-doc true}
  (:require [codex.impl.websocket.transport :as transport]
            [clojure.string :as str])
  (:import [java.io ByteArrayOutputStream EOFException IOException]
           [java.net URI StandardProtocolFamily UnixDomainSocketAddress]
           [java.net.http HttpTimeoutException]
           [java.nio ByteBuffer CharBuffer]
           [java.nio.channels SocketChannel]
           [java.nio.charset CharsetDecoder StandardCharsets]
           [java.security MessageDigest SecureRandom]
           [java.util Base64]
           [java.util.concurrent CompletableFuture ExecutorService Executors
            ScheduledExecutorService ThreadFactory TimeUnit]
           [java.util.function BiConsumer]))

(set! *warn-on-reflection* true)

(defn- ->uri [uri]
  (cond (string? uri) (java.net.URI/create uri)
        (map? uri)
        (java.net.URI. ^String (:scheme uri)
                       ^String (:user uri)
                       ^String (:host uri)
                       ^Integer (:port uri -1)
                       ^String (:path uri)
                       ^String (:query uri)
                       ^String (:fragment uri))
        :else uri))

(defn- coerce-key
  "Coerces a key to str"
  [k]
  (if (keyword? k)
    (-> k str (subs 1))
    (str k)))

(def ^:private token-pattern #"[!#$%&'*+.^_`|~0-9A-Za-z-]+")

(def ^:private guid "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")

(defn- invalid! [^String message]
  (throw (IllegalArgumentException. message)))

(defn- protocol-error! [message]
  (throw (ex-info message {:close-code 1002})))

(defn- failed-future ^CompletableFuture [error]
  (doto (CompletableFuture.) (.completeExceptionally error)))

(defn- daemon-factory [name]
  (reify ThreadFactory
    (newThread [_ runnable]
      (doto (Thread. ^Runnable runnable ^String name) (.setDaemon true)))))

(defn- options [{:keys [uri unix-socket client connect-timeout headers subprotocols]
                 :as opts}]
  (when-not (and (string? unix-socket) (not (empty? unix-socket)))
    (invalid! ":unix-socket must be a nonempty filesystem path string"))
  (when (some? client) (invalid! ":client cannot be used with :unix-socket"))
  (when (and (some? connect-timeout) (not (and (integer? connect-timeout)
                                               (pos? connect-timeout)
                                               (<= connect-timeout Long/MAX_VALUE))))
    (invalid! ":connect-timeout must be a positive number of milliseconds"))
  (let [^URI uri (->uri uri)]
    (when-not (and uri (= "ws" (some-> (.getScheme uri) str/lower-case))
                   (.getHost uri) (nil? (.getRawUserInfo uri))
                   (nil? (.getRawFragment uri))
                   (<= -1 (.getPort uri) 65535))
      (invalid! "Unix WebSockets require a ws:// URI with a host and no user info or fragment"))
    (doseq [subprotocol subprotocols]
      (when-not (and (string? subprotocol) (re-matches token-pattern subprotocol))
        (invalid! "Invalid WebSocket subprotocol")))
    (when-not (= (count subprotocols) (count (distinct subprotocols)))
      (invalid! "WebSocket subprotocols must be distinct"))
    (let [headers (mapv (fn [[k v]] [(coerce-key k) v]) headers)]
      (doseq [[k v] headers]
        (when-not (and (re-matches token-pattern k) (string? v)
                       (every? #(or (= 9 (int %)) (<= 32 (int %) 126)
                                    (<= 128 (int %) 255)) v))
          (invalid! "Invalid WebSocket header name or value"))
        (when (or (#{"host" "connection" "upgrade" "content-length" "transfer-encoding"}
                   (str/lower-case k))
                  (str/starts-with? (str/lower-case k) "sec-websocket-"))
          (invalid! (str "WebSocket handshake controls header: " k))))
      (assoc opts :uri (URI/create (.toASCIIString uri)) :headers headers))))

(defn- read-buffer! ^ByteBuffer [^SocketChannel channel n]
  (let [buffer (ByteBuffer/allocate (int n))]
    (while (.hasRemaining buffer)
      (when (neg? (.read channel buffer))
        (throw (EOFException. "WebSocket connection ended without a complete frame"))))
    (.flip buffer)
    buffer))

(defn- write-buffer! [^SocketChannel channel ^ByteBuffer buffer]
  (while (.hasRemaining buffer) (.write channel buffer)))

(defn- accept-key [^String key]
  (.encodeToString (Base64/getEncoder)
                   (.digest (MessageDigest/getInstance "SHA-1")
                            (.getBytes (str key guid) StandardCharsets/ISO_8859_1))))

(defn- header-tokens [headers name]
  (->> (get headers name)
       (mapcat #(str/split % #","))
       (map #(str/lower-case (str/trim %)))
       set))

(defn- handshake! [^SocketChannel channel ^SecureRandom random
                   {:keys [uri headers subprotocols]}]
  (let [^URI uri uri
        nonce (byte-array 16)
        _ (.nextBytes random nonce)
        key (.encodeToString (Base64/getEncoder) nonce)
        path (.getRawPath uri)
        target (str (if (empty? path) "/" path)
                    (when-some [query (.getRawQuery uri)] (str "?" query)))
        request (str "GET " target " HTTP/1.1\r\n"
                     "Host: " (.getRawAuthority uri) "\r\n"
                     "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                     "Sec-WebSocket-Version: 13\r\nSec-WebSocket-Key: " key "\r\n"
                     (when (seq subprotocols)
                       (str "Sec-WebSocket-Protocol: " (str/join ", " subprotocols) "\r\n"))
                     (apply str (map (fn [[k v]] (str k ": " v "\r\n")) headers))
                     "\r\n")]
    (write-buffer! channel (ByteBuffer/wrap (.getBytes request StandardCharsets/ISO_8859_1)))
    ;; Read exactly through CRLFCRLF, leaving any first frame in the channel.
    (let [out (ByteArrayOutputStream.)
          one (ByteBuffer/allocate 1)
          response (loop [tail 0]
                     (when (>= (.size out) 65536)
                       (throw (IOException. "WebSocket handshake exceeds 64 KiB")))
                     (.clear one)
                     (when (neg? (.read channel one))
                       (throw (EOFException. "Incomplete WebSocket handshake")))
                     (let [b (bit-and 255 (.get one 0))
                           tail (bit-and 0xffffffff (bit-or (bit-shift-left tail 8) b))]
                       (.write out (int b))
                       (if (= tail 0x0d0a0d0a)
                         (.toString out "ISO-8859-1")
                         (recur tail))))
          [status & lines] (str/split response #"\r\n")
          headers (reduce (fn [m line]
                            (let [[_ name value] (re-matches #"([^:]+):[ \t]*(.*)" line)]
                              (when-not (and name (re-matches token-pattern name)
                                             (not (re-find #"[\x00-\x08\x0a-\x1f\x7f]" value)))
                                (throw (IOException. "Malformed WebSocket handshake header")))
                              (update m (str/lower-case name) (fnil conj []) (str/trim value))))
                          {} lines)
          selected (get headers "sec-websocket-protocol")]
      (when-not (and (re-matches #"HTTP/1\.1 101(?: .*)?" status)
                     (contains? (header-tokens headers "upgrade") "websocket")
                     (contains? (header-tokens headers "connection") "upgrade")
                     (= [(accept-key key)] (get headers "sec-websocket-accept"))
                     (nil? (get headers "sec-websocket-extensions"))
                     (or (nil? selected)
                         (and (= 1 (count selected))
                              (some #{(first selected)} subprotocols))))
        (throw (IOException. (str "Invalid WebSocket upgrade response: " status)))))))

(declare send-data! send-control! send-close! terminate!)

(defrecord UnixWebSocket [channel writer timer opts state result random]
  transport/Transport
  (-send! [this data last?] (send-data! this data last?))
  (-ping! [this data] (send-control! this 9 data :control))
  (-pong! [this data] (send-control! this 10 data :control))
  (-close! [this status reason]
    (if (#{1002 1003 1006 1007 1009 1010 1012 1013 1015} status)
      (failed-future (IllegalArgumentException. "Invalid application WebSocket close code"))
      (send-close! this status reason)))
  (-abort! [this] (terminate! this {:kind :aborted})))

(defn- stop-timer! [{:keys [timer]}]
  (when timer (.shutdownNow ^ScheduledExecutorService timer)))

(defn- terminate! [{:keys [channel writer state result] :as ws} terminal]
  (let [pending (locking state
                  (when-not (:terminal @state)
                    (let [pending (:pending @state)]
                      (swap! state assoc :terminal terminal :pending #{})
                      pending)))]
    (when pending
      (try (.close ^SocketChannel channel) (catch IOException _))
      (.shutdownNow ^ExecutorService writer)
      (stop-timer! ws)
      (let [error (or (:error terminal) (IOException. "WebSocket connection is closed"))]
        (.completeExceptionally ^CompletableFuture result error)
        (doseq [^CompletableFuture future pending]
          (.completeExceptionally future error)))))
  nil)

(defn- payload-buffer ^ByteBuffer [data]
  (cond
    (instance? ByteBuffer data) data
    (bytes? data) (ByteBuffer/wrap ^bytes data)
    (string? data) (ByteBuffer/wrap (.getBytes ^String data StandardCharsets/UTF_8))
    :else (invalid! "Expected a byte array or ByteBuffer")))

(defn- write-frame! [{:keys [channel random]} opcode fin? ^ByteBuffer payload]
  (let [length (.remaining payload)
        mask (byte-array 4)
        header (ByteBuffer/allocate 14)]
    (.nextBytes ^SecureRandom random mask)
    (.put header (unchecked-byte (bit-or (if fin? 128 0) opcode)))
    (cond
      (< length 126) (.put header (unchecked-byte (bit-or 128 length)))
      (<= length 65535) (do (.put header (unchecked-byte 254))
                            (.putShort header (unchecked-short length)))
      :else (do (.put header (unchecked-byte 255)) (.putLong header (long length))))
    (.put header mask)
    (.flip header)
    (write-buffer! channel header)
    (loop [offset 0]
      (when (.hasRemaining payload)
        (let [chunk (byte-array (min 8192 (.remaining payload)))
              n (alength chunk)]
          (.get payload chunk)
          (dotimes [i n]
            (aset-byte chunk i (unchecked-byte
                                (bit-xor (aget chunk i) (aget mask (bit-and (+ offset i) 3))))))
          (write-buffer! channel (ByteBuffer/wrap chunk))
          (recur (+ offset n)))))))

(defn- enqueue! [{:keys [state writer] :as ws} opcode fin? ^ByteBuffer payload category]
  (let [future (CompletableFuture.)]
    (locking state
      (let [{:keys [terminal output-closed data-pending control-pending fragment close-future]} @state
            data? (= category :data)
            control? (= category :control)]
        (cond
          (and (= opcode 8) close-future) close-future
          ;; A concurrent close may win while the reader is answering a ping.
          (and (= category :auto) (= opcode 10) output-closed (not terminal))
          (CompletableFuture/completedFuture ws)
          (or terminal output-closed)
          (failed-future (IOException. "WebSocket output is closed"))
          (or (and data? data-pending) (and control? control-pending)
              (and data? fragment (not= fragment opcode)))
          (failed-future (IllegalStateException. "An incompatible WebSocket send is pending"))
          :else
          (let [wire-opcode (if (and data? fragment) 0 opcode)
                source (.duplicate payload)
                limit (.limit payload)]
            (swap! state (fn [s]
                           (cond-> (update s :pending conj future)
                             data? (assoc :data-pending true :fragment (when-not fin? opcode))
                             control? (assoc :control-pending true)
                             (= opcode 8) (assoc :output-closed true :close-future future))))
            (try
              (.execute ^ExecutorService writer
                        ^Runnable
                        (reify Runnable
                          (run [_]
                            (try
                              (write-frame! ws wire-opcode fin? source)
                              (.position payload limit)
                              (locking state
                                (swap! state (fn [s]
                                               (cond-> (update s :pending disj future)
                                                 data? (assoc :data-pending false)
                                                 control? (assoc :control-pending false)))))
                              (.complete future ws)
                              (catch Throwable error
                                (terminate! ws {:kind :error :error error}))))))
              (catch Throwable error
                (terminate! ws {:kind :error :error error})))
            future))))))

(defn- send-data! [ws data last?]
  (try
    (if (instance? CharSequence data)
      (enqueue! ws 1 last? (.encode (.newEncoder StandardCharsets/UTF_8)
                                    (CharBuffer/wrap ^CharSequence data)) :data)
      (enqueue! ws 2 last? (payload-buffer data) :data))
    (catch Exception error (failed-future error))))

(defn- send-control! [ws opcode data category]
  (try
    (let [buffer (payload-buffer data)]
      (when (> (.remaining buffer) 125)
        (invalid! "WebSocket control payloads cannot exceed 125 bytes"))
      (enqueue! ws opcode true buffer category))
    (catch Exception error (failed-future error))))

(defn- valid-close-code? [code]
  (or (#{1000 1001 1002 1003 1007 1008 1009 1010 1011 1012 1013 1014} code)
      (<= 3000 code 4999)))

(defn- send-close! [ws code reason]
  (try
    (when-not (and (integer? code) (valid-close-code? code) (string? reason))
      (invalid! "Invalid WebSocket close code or reason"))
    (let [text (.encode (.newEncoder StandardCharsets/UTF_8) (CharBuffer/wrap ^String reason))
          n (.remaining text)]
      (when (> n 123) (invalid! "WebSocket close reason cannot exceed 123 UTF-8 bytes"))
      (let [buffer (ByteBuffer/allocate (+ n 2))]
        (.putShort buffer (short code))
        (.put buffer text)
        (.flip buffer)
        (enqueue! ws 8 true buffer :auto)))
    (catch Exception error (failed-future error))))

(defn- frame-header! [channel]
  (let [header (read-buffer! channel 2)
        a (bit-and 255 (.get header))
        b (bit-and 255 (.get header))
        fin? (bit-test a 7)
        opcode (bit-and a 15)
        size (bit-and b 127)
        _ (when (or (not (zero? (bit-and a 112))) (bit-test b 7)
                    (not (#{0 1 2 8 9 10} opcode))
                    (and (>= opcode 8) (or (not fin?) (> size 125))))
            (protocol-error! "Invalid WebSocket frame header"))
        length (case size
                 126 (bit-and 65535 (.getShort (read-buffer! channel 2)))
                 127 (.getLong (read-buffer! channel 8))
                 size)]
    (when (or (neg? length)
              (and (= size 126) (< length 126))
              (and (= size 127) (< length 65536)))
      (protocol-error! "Invalid WebSocket frame header"))
    {:opcode opcode :fin? fin? :length length}))

(defn- callback! [{:keys [opts] :as ws} key & args]
  (when-let [f (get opts key)] (apply f ws args)))

(defn- decode-text [^CharsetDecoder decoder ^ByteBuffer carry ^ByteBuffer chunk last?]
  (let [input (ByteBuffer/allocate (+ (.remaining carry) (.remaining chunk)))
        output (CharBuffer/allocate (+ 2 (.capacity input)))]
    (.put input carry)
    (.put input chunk)
    (.flip input)
    (let [result (.decode decoder input output last?)]
      (when (.isError result)
        (throw (ex-info "Invalid UTF-8 in WebSocket text" {:close-code 1007}))))
    (when last? (.flush decoder output))
    (.flip output)
    [(str output) (.slice input)]))

(defn- receive-data! [{:keys [channel] :as ws} {:keys [fin? length]} type decoder carry]
  (loop [remaining length carry carry]
    (let [n (min remaining 8192)
          chunk (read-buffer! channel n)
          last? (and fin? (= remaining n))
          [data carry] (if (= type 1)
                         (decode-text decoder carry chunk last?)
                         [chunk carry])]
      (callback! ws :on-message data last?)
      (if (= remaining n)
        carry
        (recur (- remaining n) carry)))))

(defn- receive-close! [ws ^ByteBuffer payload]
  (let [n (.remaining payload)]
    (when (= n 1) (protocol-error! "Invalid WebSocket close payload"))
    (let [code (if (zero? n) 1005 (bit-and 65535 (.getShort payload)))
          _ (when (and (pos? n) (not (valid-close-code? code)))
              (protocol-error! "Invalid WebSocket close status"))
          reason (try (str (.decode (.newDecoder StandardCharsets/UTF_8) payload))
                      (catch java.nio.charset.CharacterCodingException _
                        (throw (ex-info "Invalid UTF-8 in close reason" {:close-code 1007}))))
          reply (if (zero? n)
                  (enqueue! ws 8 true (ByteBuffer/allocate 0) :auto)
                  (send-close! ws code ""))]
      ;; A peer that stops reading cannot keep the reader and writer alive forever.
      (.get ^CompletableFuture reply 1 TimeUnit/SECONDS)
      (terminate! ws {:kind :closed :code code :reason reason}))))

(defn- receive! [{:keys [channel state] :as ws}]
  (loop [fragment nil decoder nil carry (ByteBuffer/allocate 0)]
    (when-not (:terminal @state)
      (let [{:keys [opcode fin? length] :as header} (frame-header! channel)]
        (if (>= opcode 8)
          (let [payload (read-buffer! channel length)]
            (case (int opcode)
              8 (receive-close! ws payload)
              9 (do
                    ;; Apply backpressure instead of accumulating automatic pongs.
                  (.get ^CompletableFuture (send-control! ws 10 (.duplicate payload) :auto))
                  (callback! ws :on-ping payload))
              10 (callback! ws :on-pong payload))
            (recur fragment decoder carry))
          (do
            (when (or (and (= opcode 0) (nil? fragment))
                      (and (not= opcode 0) fragment))
              (protocol-error! "Invalid WebSocket fragmentation sequence"))
            (let [type (if (zero? opcode) fragment opcode)
                  decoder (if (= opcode 1) (.newDecoder StandardCharsets/UTF_8) decoder)
                  carry (receive-data! ws header type decoder carry)]
              (recur (when-not fin? type) (when-not fin? decoder) carry))))))))

(defn- run-connection! [{:keys [channel opts random state result] :as ws}]
  (try
    (.connect ^SocketChannel channel (UnixDomainSocketAddress/of ^String (:unix-socket opts)))
    (handshake! channel random opts)
    (let [opened? (locking state
                    (when-not (:terminal @state)
                      (swap! state assoc :opened true)
                      true))]
      (when opened?
        (stop-timer! ws)
        (callback! ws :on-open)
        (.complete ^CompletableFuture result ws)
        (receive! ws)))
    (catch Throwable error
      (when (and (:opened @state) (not (:terminal @state)))
        (when-let [code (:close-code (ex-data error))]
          (try (.get ^CompletableFuture (send-close! ws code "") 100 TimeUnit/MILLISECONDS)
               (catch Exception _))))
      (terminate! ws {:kind :error :error error}))
    (finally
      (let [{:keys [opened terminal]} @state]
        (when opened
          (try
            (case (:kind terminal)
              :closed (callback! ws :on-close (:code terminal) (:reason terminal))
              :error (callback! ws :on-error (:error terminal))
              nil)
            (catch Throwable _)))))))

(defn websocket [opts]
  (let [{:keys [connect-timeout async] :as opts} (options opts)
        channel (SocketChannel/open StandardProtocolFamily/UNIX)
        writer (Executors/newSingleThreadExecutor (daemon-factory "codex-unix-websocket-writer"))
        timer (when connect-timeout
                (Executors/newSingleThreadScheduledExecutor
                 (daemon-factory "codex-unix-websocket-timeout")))
        result (CompletableFuture.)
        ws (->UnixWebSocket channel writer timer opts (atom {:pending #{}}) result (SecureRandom.))]
    (try
      (.whenComplete result
                     (reify BiConsumer
                       (accept [_ _ _]
                         (when (.isCancelled result)
                           (terminate! ws {:kind :aborted})))))
      (when timer
        (.schedule ^ScheduledExecutorService timer
                   ^Runnable (reify Runnable
                               (run [_]
                                 (locking (:state ws)
                                   (when-not (:opened @(:state ws))
                                     (terminate! ws {:kind :error
                                                     :error (HttpTimeoutException.
                                                             "Unix WebSocket connection timed out")})))))
                   (long connect-timeout) TimeUnit/MILLISECONDS))
      (doto (.newThread ^ThreadFactory (daemon-factory "codex-unix-websocket-reader")
                        (reify Runnable (run [_] (run-connection! ws))))
        (.start))
      (catch Throwable error (terminate! ws {:kind :error :error error})))
    (if async result @result)))
