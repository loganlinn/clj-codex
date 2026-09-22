(ns codex.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [codex.api :as api]
            [codex.app-server :as server]
            [codex.event :as event]
            [codex.impl.schema :as schema]
            [codex.interaction :as interaction]
            [codex.run :as run]
            [codex.command :as command]
            [codex.process :as process]
            [codex.permission :as permission]
            [codex.repl :as repl]
            [codex.thread :as thread]
            [codex.turn :as turn]))

(defn fake
  ([] (fake {}))
  ([opts]
   (let [callbacks (atom nil) sent (atom []) responder (atom nil) transport-closes (atom 0)
         conn (server/connect!
               (merge {:transport {:type :custom
                                   :open (fn [cb]
                                           (reset! callbacks cb)
                                           {:close! #(swap! transport-closes inc)
                                            :send! (fn [message]
                                                     (swap! sent conj message)
                                                     (if (= "initialize" (get message "method"))
                                                       ((:receive! cb) {"id" (get message "id") "result" {"userAgent" "test"}})
                                                       (when @responder (@responder message))))})}}
                      opts))]
     {:conn conn :sent sent :responder responder :transport-closes transport-closes
      :receive! #((:receive! @callbacks) %)
      :close! #((:closed! @callbacks) nil)})))

(defn eventually [f]
  (loop [n 200]
    (if-let [x (f)] x
            (when (pos? n) (Thread/sleep 5) (recur (dec n))))))

(defn encode [id value]
  (let [[root s] (schema/lookup id)] (schema/encode root s value)))

(deftest connection-and-correlation
  (let [{:keys [conn sent receive!]} (fake)]
    (try
      (is (= :ready (server/status conn)))
      (is (= ["initialize" "initialized"] (mapv #(get % "method") @sent)))
      (let [a (server/request! conn "a" {}) b (server/request! conn "b" {})]
        (receive! {"id" (:id b) "result" false})
        (receive! {"id" (:id a) "result" nil})
        (is (false? (server/await! b))) (is (nil? (server/await! a))))
      (finally (server/close! conn)))))

(deftest with-open-closes-jvm-connections
  ;; Babashka cannot implement Closeable on records; with-connection is portable.
  (when-not (System/getProperty "babashka.version")
    (doseq [mode [:return :throw :close-early :binding-failure]]
      (testing (name mode)
        (let [{:keys [conn transport-closes]} (fake)
              error (ex-info "Body or initializer failed" {:mode mode})
              pending (server/request! conn "waiting" {} {:timeout-ms nil})
              observer (server/listen! conn (fn [_]))]
          (try
            (is (instance? java.io.Closeable conn))
            (let [result (try
                           (with-open [c conn
                                       other (if (= :binding-failure mode) (throw error) (java.io.StringReader. ""))]
                             (is (= :ready (server/status c)))
                             (case mode
                               :throw (throw error)
                               :close-early (server/close! c)
                               nil)
                             :returned)
                           (catch clojure.lang.ExceptionInfo e e))]
              (is (= (if (#{:throw :binding-failure} mode) error :returned) result)))
            (is (= :closed (server/status conn)))
            (is (= 1 @transport-closes))
            (is (thrown? clojure.lang.ExceptionInfo (server/await! pending 1000 ::waiting)))
            (is (empty? @(:listeners conn)))
            (is (= :closed (:codex.error/category (ex-data (:error @(:result observer))))))
            (server/close! conn)
            (.close ^java.io.Closeable conn)
            (is (= 1 @transport-closes))
            (finally (server/close! conn))))))))

(deftest with-connection-cleans-up-in-reverse-order
  (doseq [mode [:return :throw :close-early :binding-failure]]
    (testing (name mode)
      (let [opened (atom []) closed (atom [])
            open! (fn [label]
                    (let [{:keys [conn transport-closes] :as fixture} (fake)]
                      (add-watch transport-closes ::close-order (fn [& _] (swap! closed conj label)))
                      (swap! opened conj fixture)
                      conn))
            error (ex-info "Body or initializer failed" {:mode mode})]
        (try
          (let [result (try
                         (server/with-connection [first-conn (open! :first)
                                                  second-conn (do
                                                                (is (= :ready (server/status first-conn)))
                                                                (if (= :binding-failure mode) (throw error) (open! :second)))]
                           (is (= :ready (server/status second-conn)))
                           (case mode
                             :throw (throw error)
                             :close-early (server/close! second-conn)
                             nil)
                           :returned)
                         (catch clojure.lang.ExceptionInfo e e))]
            (is (= (if (#{:throw :binding-failure} mode) error :returned) result)))
          (is (= (if (= :binding-failure mode) [:first] [:second :first]) @closed))
          (is (= (if (= :binding-failure mode) 1 2) (count @opened)))
          (doseq [{:keys [conn transport-closes]} @opened]
            (is (= :closed (server/status conn)))
            (server/close! conn)
            (is (= 1 @transport-closes)))
          (finally
            (doseq [{:keys [conn]} @opened] (server/close! conn))))))))

(deftest schema-conversion
  (is (= {"threadId" "t" "gitInfo" {"branch" nil}}
         (encode "ThreadMetadataUpdateParams" {:thread-id "t" :git-info {:branch nil}})))
  (let [json {"type" "object" "properties" {"answerText" {"type" "string"}}}
        wire (encode "TurnStartParams" {:thread-id "t" :input [{:type :text :text "hi"}]
                                        :output-schema json})]
    (is (= json (get wire "outputSchema")))
    (is (= "text" (get-in wire ["input" 0 "type"]))))
  (is (thrown? clojure.lang.ExceptionInfo (encode "TurnStartParams" {:thread-id "t" :input [] :typo true})))
  (is (= {:codex.thread/id "t" :codex.api/extensions {"futureField" 3}}
         (schema/decode-type "Thread" {"id" "t" "futureField" 3}))))

(deftest deadlines-and-shutdown
  (let [{:keys [conn close!]} (fake)]
    (try
      (let [p (server/request! conn "slow" {} {:timeout-ms 100})]
        (is (= ::waiting (server/await! p 1 ::waiting)))
        (is (thrown? clojure.lang.ExceptionInfo (server/await! p 2000 ::waiting))))
      (let [p (server/request! conn "slow" {} {:timeout-ms nil})]
        (close!)
        (is (thrown? clojure.lang.ExceptionInfo (server/await! p 1000 ::waiting))))
      (finally (server/close! conn)))))

(deftest catalog-and-capability
  (is (= 164 (count (api/operations))))
  (is (= (.trim (slurp (clojure.java.io/resource "codex/generator-version.txt")))
         (:codex-version (api/provenance))))
  (let [{:keys [conn]} (fake)]
    (try
      (is (thrown? clojure.lang.ExceptionInfo
                   (api/submit! conn {:op :process/spawn :args {:command ["true"] :cwd "/tmp" :process-handle "p"}})))
      (finally (server/close! conn)))))

(deftest typed-server-requests
  (let [{:keys [conn receive! sent]} (fake)]
    (try
      (let [registration (interaction/handle! conn :item.command-execution/request-approval (constantly ::interaction/defer))]
        (receive! {"id" 7 "method" "item/commandExecution/requestApproval"
                   "params" {"threadId" "t" "turnId" "u" "itemId" "i"}})
        (let [r (first (interaction/pending conn))]
          (is (not (interaction/resolved? conn r)))
          (interaction/respond! conn r {:decision :accept})
          (is (= {"id" 7 "result" {"decision" "accept"}} (last @sent)))
          (is (thrown? clojure.lang.ExceptionInfo (interaction/respond! conn r {:decision :accept}))))
        (interaction/unhandle! registration))
      (finally (server/close! conn)))))

(deftest completion-before-start-response
  (let [{:keys [conn responder receive!]} (fake)
        item {"id" "i" "type" "agentMessage" "text" "authoritative"}
        notify (fn [method params] (receive! {"method" method "params" params}))]
    (reset! responder
            (fn [m]
              (when (= "turn/start" (get m "method"))
                (notify "item/started" {"threadId" "t" "turnId" "u" "item" (assoc item "text" "")})
                (notify "item/agentMessage/delta" {"threadId" "t" "turnId" "u" "itemId" "i" "delta" "provisional"})
                (notify "item/completed" {"threadId" "t" "turnId" "u" "item" item})
                (notify "turn/completed" {"threadId" "t" "turn" {"id" "u" "status" "completed" "items" [] "error" nil}})
                (receive! {"id" (get m "id") "result" {"turn" {"id" "u" "status" "inProgress" "items" [] "error" nil}}}))))
    (try
      (let [work (run/start! conn "t" {:input "hi"})
            result (run/await! work 2000 ::timeout)]
        (is (= :completed (::turn/status result)))
        (is (= ["authoritative"] (mapv :codex.item/text (::turn/items result))))
        (run/close! work))
      (finally (server/close! conn)))))

(deftest reducible-pagination
  (let [{:keys [conn responder receive! sent]} (fake)]
    (reset! responder #(receive! {"id" (get % "id") "result" {"data" ["a" "b"] "nextCursor" "next"}}))
    (try
      (let [entries (api/entries conn {:op :thread.loaded/list})]
        (is (= 2 (count @sent)))
        (is (= ["a"] (into [] (take 1) entries)))
        (is (= 3 (count @sent))))
      (finally (server/close! conn)))))

(deftest reply-id-direction-and-reentrant-handler
  (let [{:keys [conn responder receive! sent]} (fake)]
    (reset! responder (fn [m] (when (= "nested" (get m "method"))
                                (receive! {"id" (get m "id") "result" "ok"}))))
    (try
      (interaction/handle! conn :item.command-execution/request-approval
                           (fn [_]
                             (is (= "ok" (server/await! (server/request! conn "nested" {}) 1000 ::timeout)))
                             {:decision :decline}))
      (let [outbound (server/request! conn "unanswered" {} {:timeout-ms nil})]
        (receive! {"id" (:id outbound) "method" "item/commandExecution/requestApproval"
                   "params" {"threadId" "t" "turnId" "u" "itemId" "i"}})
        (is (eventually #(some (fn [m] (= "decline" (get-in m ["result" "decision"]))) @sent)))
        (is (= ::waiting (server/await! outbound 5 ::waiting)))
        (server/abandon! outbound))
      (finally (server/close! conn)))))

(deftest remote-resolution-invalidates-replies
  (let [{:keys [conn receive!]} (fake)]
    (try
      (interaction/handle! conn :item.file-change/request-approval (constantly ::interaction/defer))
      (receive! {"id" "r" "method" "item/fileChange/requestApproval"
                 "params" {"threadId" "t" "turnId" "u" "itemId" "i"}})
      (let [request (first (interaction/pending conn))]
        (receive! {"method" "serverRequest/resolved" "params" {"threadId" "t" "requestId" "r"}})
        (is (interaction/resolved? conn request))
        (is (thrown? clojure.lang.ExceptionInfo (interaction/respond! conn request {:decision :accept}))))
      (finally (server/close! conn)))))

(deftest overflow-is-visible-and-does-not-block-rpcs
  (let [{:keys [conn receive!]} (fake) entered (promise) release (promise) error (promise)]
    (try
      (server/listen! conn {:capacity 1 :on-error #(deliver error %)}
                      (fn [_] (deliver entered true) @release))
      (receive! {"method" "warning" "params" {}})
      (is (= true (deref entered 1000 ::timeout)))
      (dotimes [_ 3] (receive! {"method" "warning" "params" {}}))
      (is (= :overflow (:codex.error/category (ex-data (deref error 1000 nil)))))
      (let [p (server/request! conn "still-live" {})]
        (receive! {"id" (:id p) "result" 42})
        (is (= 42 (server/await! p 1000 ::timeout))))
      (finally (deliver release true) (server/close! conn)))))

(deftest defaults-and-deferred-request-capacity
  (let [{:keys [conn receive! sent]} (fake {:interaction-timeout-ms 50 :request-capacity 1})]
    (try
      (interaction/handle! conn :item.file-change/request-approval (constantly ::interaction/defer))
      (doseq [id [1 2]]
        (receive! {"id" id "method" "item/fileChange/requestApproval"
                   "params" {"threadId" "t" "turnId" "u" "itemId" "i"}}))
      (is (some #(= -32001 (get-in % ["error" "code"])) @sent))
      (is (eventually #(some (fn [m] (= "decline" (get-in m ["result" "decision"]))) @sent)))
      (is (empty? (interaction/pending conn)))
      (finally (server/close! conn)))))

(deftest unknown-values-and-arbitrary-json
  (let [future {"id" "i" "type" "futureItem" "PayloadKey" 1}]
    (is (= {:type :codex.api/unknown :codex.api/raw future} (schema/decode-type "ThreadItem" future))))
  (is (= :agent-message (:codex.item/type (schema/decode-type "ThreadItem" {"type" "agentMessage" "id" "i" "text" "old"}))))
  (is (= {"threadId" "t" "items" [{"type" "message" "myField" {"aB" 1}}]}
         (encode "ThreadInjectItemsParams" {:thread-id "t" :items [{"type" "message" "myField" {"aB" 1}}]})))
  (is (= {} (get (encode "ThreadMetadataUpdateParams" {:thread-id "t" :git-info {}}) "gitInfo")))
  (let [{:keys [conn]} (fake)]
    (try
      (is (thrown? clojure.lang.ExceptionInfo
                   (api/submit! conn {:op :thread/start :args {:dynamic-tools []}})))
      (finally (server/close! conn)))))

(deftest streamed-execution-preserves-bytes-and-order
  (doseq [kind [:command :process]
          opt-outs [[] (if (= kind :command)
                         ["process/outputDelta" "process/exited"]
                         ["command/exec/outputDelta"])]]
    (let [{:keys [conn responder receive!]} (fake {:capabilities {:experimental-api true
                                                                  :opt-out-notification-methods opt-outs}})
          output (.getBytes "λ🌱" "UTF-8")
          chunks [(java.util.Arrays/copyOfRange output 0 1)
                  (java.util.Arrays/copyOfRange output 1 (alength output))]
          method (if (= kind :command) "command/exec" "process/spawn")
          id-key (if (= kind :command) "processId" "processHandle")]
      (reset! responder
              (fn [m]
                (when (= method (get m "method"))
                  (let [id (get-in m ["params" id-key])]
                    (doseq [chunk chunks]
                      (receive! {"method" (if (= kind :command) "command/exec/outputDelta" "process/outputDelta")
                                 "params" {id-key id "stream" "stdout" "deltaBase64" (.encodeToString (java.util.Base64/getEncoder) chunk)
                                           "capReached" false}}))
                    (receive! {"id" (get m "id") "result" (if (= kind :command) {"exitCode" 0 "stdout" "" "stderr" ""} {})})
                    (when (= kind :process)
                      (receive! {"method" "process/exited" "params" {id-key id "exitCode" 0 "stdout" "" "stderr" ""
                                                                     "stdoutCapReached" false "stderrCapReached" false}}))))))
      (try
        (let [p ((if (= kind :command) command/start! process/start!) conn {:command ["echo" "hi"] :cwd "/tmp"})
              result ((if (= kind :command) command/await! process/await!) p 2000 ::timeout)]
          (is (= 0 (:exit-code result)))
          (is (= "λ🌱" (String. ^bytes (:stdout result) "UTF-8")))
          (is (false? (:truncated? result))))
        (finally (server/close! conn))))))

(deftest execution-requires-tracking-notifications
  (doseq [[start! method] [[command/start! "command/exec/outputDelta"]
                           [process/start! "process/outputDelta"]
                           [process/start! "process/exited"]]]
    (testing method
      (let [{:keys [conn sent]} (fake {:capabilities {:experimental-api true
                                                      :opt-out-notification-methods [method]}})
            before @sent]
        (try
          (let [error (try (start! conn {:command ["true"] :cwd "/tmp"})
                           nil
                           (catch clojure.lang.ExceptionInfo e e))]
            (is (= :capability (:codex.error/category (ex-data error))))
            (is (= [method] (:methods (ex-data error))))
            (is (= before @sent))
            (is (empty? @(:listeners conn))))
          (finally (server/close! conn)))))))

(deftest watcher-history-releases-discarded-events
  (doseq [limit [1 200]]
    (let [callback (atom nil)]
      (with-redefs [event/listen! (fn [_ _ f] (reset! callback f))]
        (let [watch (repl/watch! nil {:history-limit limit :print? false})]
          (is (= [] (repl/history watch)))
          (dotimes [i 10000] (@callback {:index i}))
          (let [history (repl/history watch)]
            (is (= (mapv #(hash-map :index %) (range (- 10000 limit) 10000)) history))
            ;; A subvector can expose a bounded count while retaining all old events.
            (is (= (type []) (type history)))))))))

(deftest read-only-policy-encodes-for-turns-and-commands
  (doseq [[policy expected] [[(permission/read-only) {"type" "readOnly"}]
                             [(permission/read-only false) {"type" "readOnly" "networkAccess" false}]
                             [(permission/read-only true) {"type" "readOnly" "networkAccess" true}]]
          [schema-id args] [["TurnStartParams" {:thread-id "t" :input []}]
                            ["CommandExecParams" {:command ["true"]}]]]
    (is (= expected (get (encode schema-id (assoc args :sandbox-policy policy)) "sandboxPolicy")))))

(deftest initialization-failure-cleans-up
  (let [closed (atom false)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (server/connect! {:transport {:type :custom :open (fn [{:keys [receive!]}]
                                                                     {:close! #(reset! closed true)
                                                                      :send! #(receive! {"id" (get % "id")
                                                                                         "error" {"code" -1 "message" "no"}})})}})))
    (is @closed)))
