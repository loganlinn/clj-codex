(ns codex.app-server
  "Bidirectional app-server connections. Raw params/results use string-keyed JSON maps."
  (:require [codex.impl.schema :as schema]
            [codex.impl.transport :as transport]
            [codex.impl.util :as u])
  (:import [java.util.concurrent LinkedBlockingQueue TimeUnit]))

(defrecord Connection [id state transport pending requests listeners handlers request-queue options counter write-lock])
(defrecord Pending [id connection result])
(defrecord Subscription [id connection queue result active worker])
(defmethod print-method Connection [c w] (.write ^java.io.Writer w (str "#codex/connection " (pr-str {:id (:id c) :status (:status @(:state c))}))))
(defmethod print-method Pending [p w] (.write ^java.io.Writer w (str "#codex/pending " (pr-str {:id (:id p) :done? (realized? (:result p))}))))
(defmethod print-method Subscription [s w] (.write ^java.io.Writer w (str "#codex/subscription " (pr-str {:id (:id s) :active? @(:active s)}))))

(declare close! reply! notify! request! receive!)

(defn status "Return the connection lifecycle state." [conn] (:status @(:state conn)))
(defn info "Return initialization results, capabilities, and safe connection metadata." [conn]
  (merge {:id (:id conn) :status (status conn)
          :capabilities (:capabilities (:options conn))}
         (select-keys @(:state conn) [:server :error])))

(defn unlisten! "Stop a local raw observer. Does not unsubscribe a remote thread." [subscription]
  (when (compare-and-set! (:active subscription) true false)
    (swap! (:listeners (:connection subscription)) dissoc (:id subscription))
    (.clear ^LinkedBlockingQueue (:queue subscription))
    (.offer ^LinkedBlockingQueue (:queue subscription) ::stop)
    (deliver (:result subscription) {:value :closed}))
  nil)

(defn listen!
  "Observe raw incoming envelopes on an ordered bounded worker queue.
   opts: :capacity (default 1024), :on-error. Returns a subscription."
  ([conn f] (listen! conn {} f))
  ([conn {:keys [capacity on-error] :or {capacity 1024}} f]
   (let [s (assoc (->Subscription (u/uuid) conn (LinkedBlockingQueue. (int capacity)) (promise) (atom true) (atom nil))
                  :on-error on-error)]
     (locking (:listeners conn)
       (when (#{:closed :failed} (status conn))
         (throw (u/error :closed "Connection is closed" {})))
       (swap! (:listeners conn) assoc (:id s) s))
     (reset! (:worker s)
             (u/worker! "codex-observer"
                        #(try
                           (loop []
                             (let [message (.take ^LinkedBlockingQueue (:queue s))]
                               (when (and @(:active s) (not= ::stop message))
                                 (f message) (recur))))
                           (catch Exception e
                             (u/deliver-error! (:result s) e)
                             (unlisten! s)
                             (when on-error (try (on-error e) (catch Exception _ nil)))))))
     s)))

(defn- fail-observer! [s e]
  (u/deliver-error! (:result s) e)
  (unlisten! s)
  (when-let [f (:on-error s)]
    (u/worker! "codex-observer-error" #(try (f e) (catch Exception _ nil)))))

(defn- publish! [conn message]
  (let [message (with-meta message {:sequence (:sequence (swap! (:state conn) update :sequence (fnil inc 0)))})]
    (doseq [[_ s] @(:listeners conn) :when s]
      (when-not (.offer ^LinkedBlockingQueue (:queue s) message)
        (fail-observer! s (u/error :overflow "Observer queue overflow" {:subscription-id (:id s)}))))))

(defn- send! [conn message]
  (locking (:write-lock conn)
    (when (#{:closed :failed} (status conn))
      (throw (u/error :closed "Connection is closed" {})))
    (try
      ((:send! @(:transport conn)) message)
      (catch Exception _
        (let [e (u/error :transport "Transport write failed; remote outcome is unknown" {})]
          (close! conn e) (throw e))))))

(defn pending-requests "Return pending server requests, including their reply tokens." [conn]
  (vec (vals @(:requests conn))))

(defn reply!
  "Reply once to a server request token. reply is {:result wire-value} or {:error wire-error}."
  [conn token reply]
  (when-not (and (map? reply) (= 1 (count (select-keys reply [:result :error]))))
    (throw (u/error :argument "A reply needs exactly one of :result or :error" {})))
  (locking (:requests conn)
    (let [current (get @(:requests conn) (:id token))]
      (when-not (and (= (:connection-id token) (:id conn)) (= token (:token current)))
        (throw (u/error :stale-request "Server request is already resolved or belongs to another connection" {})))
      (swap! (:requests conn) dissoc (:id token))))
  (send! conn (assoc (if (contains? reply :result) {"result" (:result reply)} {"error" (:error reply)}) "id" (:id token)))
  nil)

(defn- default-reply [method]
  (case method
    ("item/commandExecution/requestApproval" "item/fileChange/requestApproval") {:result {"decision" "decline"}}
    "item/permissions/requestApproval" {:result {"permissions" {}}}
    "mcpServer/elicitation/request" {:result {"action" "decline" "content" nil}}
    {:error {"code" -32601 "message" "No client handler for this server request"}}))

(defn- dispatch-request! [conn request]
  (when (= (:token request) (:token (get @(:requests conn) (:id request))))
    (try
      (let [handler (get @(:handlers conn) (:method request))
            response (if handler (handler request) (default-reply (:method request)))]
        (when-not (= ::defer response)
          (reply! conn (:token request) response)))
      (catch Exception _
        (try (reply! conn (:token request) {:error {"code" -32603 "message" "Client handler failed"}})
             (catch Exception _ nil))))))

(defn- finish-pending! [conn id response]
  (let [entry (locking (:pending conn)
                (let [p (get @(:pending conn) id)] (swap! (:pending conn) dissoc id) p))]
    (if entry
      (if (contains? response "error")
        (let [error (get response "error")]
          (u/deliver-error! (:result entry)
                            (u/error :rpc "App-server rejected the request"
                                     {:request-id id :wire/method (:method entry)
                                      :rpc/code (get error "code") :rpc/message (get error "message")
                                      :rpc/data (get error "data")})))
        (u/deliver-value! (:result entry) (get response "result")))
      (publish! conn {"method" "codex/lateResponse" "params" {"id" id}}))))

(defn- receive! [conn message]
  (when-not (#{:closed :failed} (status conn))
    (when-not (map? message) (throw (u/error :protocol "Expected a JSON object" {})))
    (when-not (or (and (string? (get message "method"))
                       (or (not (contains? message "id")) (string? (get message "id")) (integer? (get message "id"))))
                  (and (contains? message "id") (not (contains? message "method"))
                       (not= (contains? message "result") (contains? message "error"))))
      (throw (u/error :protocol "Malformed JSON-RPC message" {})))
    (cond
      (and (contains? message "id") (contains? message "method"))
      (let [id (get message "id")
            token {:id id :connection-id (:id conn) :generation (u/uuid)}
            request {:id id :method (get message "method") :params (get message "params") :token token
                     :deadline (+ (System/currentTimeMillis) (:interaction-timeout-ms (:options conn)))}]
        (let [accepted? (locking (:requests conn)
                          (when (contains? @(:requests conn) id)
                            (throw (u/error :protocol "Duplicate pending server request ID" {:request-id id})))
                          (when (< (count @(:requests conn)) (:request-capacity (:options conn)))
                            (swap! (:requests conn) assoc id request) true))]
          (if accepted?
            (when-not (.offer ^LinkedBlockingQueue (:request-queue conn) request)
              (reply! conn token {:error {"code" -32001 "message" "Client request queue is full"}}))
            (send! conn {"id" id "error" {"code" -32001 "message" "Client pending request capacity exceeded"}}))))
      (contains? message "id") (finish-pending! conn (get message "id") message)
      (= "serverRequest/resolved" (get message "method"))
      (locking (:requests conn)
        (swap! (:requests conn) dissoc (get-in message ["params" "requestId"]))))
    (publish! conn message)))

(defn await!
  "Await a pending RPC. A local timeout returns timeout-value without cancelling the RPC."
  ([pending] (u/await-result (:result pending)))
  ([pending timeout-ms timeout-value] (u/await-result (:result pending) timeout-ms timeout-value)))

(defn abandon! "Release local pending state. Does not cancel remote work." [pending]
  (let [conn (:connection pending)]
    (locking (:pending conn)
      (swap! (:pending conn) dissoc (:id pending)))
    (u/deliver-error! (:result pending) (u/error :abandoned "Local request abandoned" {:request-id (:id pending)})))
  nil)

(defn request!
  "Submit a raw RPC and return a pending handle. :timeout-ms nil disables its deadline."
  ([conn method params] (request! conn method params {}))
  ([conn method params opts]
   (when-not (or (= :ready (status conn)) (and (= :initializing (status conn)) (= method "initialize")))
     (throw (u/error :lifecycle "Connection is not ready" {:status (status conn)})))
   (let [id (str "clj-" (swap! (:counter conn) inc))
         result (promise) pending (->Pending id conn result)
         timeout (get opts :timeout-ms (:request-timeout-ms (:options conn)))
         entry {:result result :method method
                :deadline (when timeout (+ (System/currentTimeMillis) timeout))}]
     (locking (:pending conn)
       (when (#{:closed :failed} (status conn)) (throw (u/error :closed "Connection is closed" {})))
       (when (>= (count @(:pending conn)) (:max-pending (:options conn)))
         (throw (u/error :overloaded "Client pending RPC capacity exceeded" {})))
       (swap! (:pending conn) assoc id entry))
     (try
       (send! conn (cond-> {"id" id "method" method} (not= ::omit params) (assoc "params" params)))
       (catch Exception e (swap! (:pending conn) dissoc id) (u/deliver-error! result e)))
     pending)))

(defn notify! "Send a raw notification. Use ::omit to omit params." [conn method params]
  (send! conn (cond-> {"method" method} (not= ::omit params) (assoc "params" params)))
  nil)

(defn close!
  "Close the connection and owned process. Remote turns are not interrupted. Idempotent."
  ([conn] (close! conn nil))
  ([conn cause]
   (let [[before _] (swap-vals! (:state conn) #(if (#{:closed :failed} (:status %)) %
                                                 (assoc % :status (if cause :failed :closed)
                                                        :error (some-> cause ex-data))))]
     (when-not (#{:closed :failed} (:status before))
       (let [e (or cause (u/error :closed "Connection closed" {}))]
         (locking (:pending conn)
           (doseq [[_ p] @(:pending conn)] (u/deliver-error! (:result p) e))
           (reset! (:pending conn) {}))
         (locking (:requests conn) (reset! (:requests conn) {}))
         (locking (:listeners conn)
           (doseq [[_ s] @(:listeners conn) :when s] (fail-observer! s e)))
         (dotimes [_ (:handler-threads (:options conn))]
           (.offer ^LinkedBlockingQueue (:request-queue conn) ::stop))
         (when-let [close (:close! @(:transport conn))] (try (close) (catch Exception _ nil))))))
   nil))

(defn connect!
  "Connect over :stdio or :websocket and complete initialize/initialized.
   :handlers maps raw method strings to functions returning {:result ...}, {:error ...},
   or ::defer. :interaction-timeout-ms bounds deferred requests. No account login occurs."
  [{:keys [transport client-info capabilities handlers] :as opts}]
  (let [opts (merge {:request-timeout-ms 30000 :interaction-timeout-ms 300000
                     :handler-threads 4 :request-capacity 128 :max-pending 1024} opts)
        _ (doseq [k [:interaction-timeout-ms :handler-threads :request-capacity :max-pending]]
            (when-not (and (integer? (get opts k)) (pos? (get opts k)))
              (throw (u/error :argument "Connection capacity and deadline values must be positive integers" {:key k}))))
        conn (->Connection (u/uuid) (atom {:status :initializing}) (atom nil)
                           (atom {}) (atom {}) (atom {}) (atom (or handlers {}))
                           (LinkedBlockingQueue. (int (:request-capacity opts)))
                           (select-keys opts [:request-timeout-ms :interaction-timeout-ms :handler-threads :capabilities :request-capacity :max-pending])
                           (atom 0) (Object.))]
    (try
      (dotimes [_ (:handler-threads opts)]
        (u/worker! "codex-request-handler"
                   #(loop []
                      (let [request (.poll ^LinkedBlockingQueue (:request-queue conn) 100 TimeUnit/MILLISECONDS)]
                        (when-not (or (= ::stop request) (#{:closed :failed} (status conn)))
                          (when request (dispatch-request! conn request)) (recur))))))
      (let [opened (transport/open! (or transport {:type :stdio})
                                     {:receive! #(try (receive! conn %) (catch Exception e (close! conn e)))
                                      :closed! #(close! conn (or % (u/error :transport "Transport closed" {})))
                                      :stderr! (:on-stderr opts)})]
        (reset! (:transport conn) opened)
        (when (#{:closed :failed} (status conn))
          ((:close! opened)) (throw (u/error :transport "Transport closed during connection" {}))))
      (u/worker! "codex-deadlines"
                 #(loop []
                    (when-not (#{:closed :failed} (status conn))
                      (let [now (System/currentTimeMillis)]
                        (locking (:pending conn)
                          (doseq [[id p] @(:pending conn) :when (and (:deadline p) (<= (:deadline p) now))]
                            (swap! (:pending conn) dissoc id)
                            (u/deliver-error! (:result p)
                                              (u/error :timeout "RPC deadline elapsed; remote outcome is unknown"
                                                       {:request-id id :wire/method (:method p)}))))
                        (doseq [r (pending-requests conn) :when (<= (:deadline r) now)]
                          (try (reply! conn (:token r) (default-reply (:method r))) (catch Exception _ nil))))
                      (Thread/sleep 20) (recur))))
      (let [[root s] (schema/lookup "InitializeParams")
            params (schema/encode root s {:client-info (or client-info {:name "clojure-sdk" :version "0.1.0"})
                                          :capabilities (or capabilities {})})
            response (await! (request! conn "initialize" params))]
        (notify! conn "initialized" {})
        (swap! (:state conn) #(if (= :initializing (:status %)) (assoc % :status :ready :server response) %))
        (when-not (= :ready (status conn)) (throw (u/error :transport "Connection lost during initialization" {}))))
      conn
      (catch Exception e (close! conn e) (throw e)))))
