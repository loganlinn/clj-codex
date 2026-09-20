(ns codex.interaction
  "Server-initiated requests with typed replies and explicit local responder ownership."
  (:require [codex.app-server :as server] [codex.impl.schema :as schema] [codex.impl.util :as u]))

(defn- descriptor [method] (get-in @schema/catalog [:server-requests (u/operation method)]))
(defn- domain-request [request]
  (let [d (descriptor (:method request))]
    {:id (:id request) :kind (u/operation (:method request)) :token (:token request)
     :wire/method (:method request)
     :params (if-let [s (:args-schema d)] (schema/decode-type s (:params request)) (:params request))
     :reply-schema (:result-schema d)}))
(defn pending "Return typed pending requests, including reply tokens." [conn]
  (mapv domain-request (server/pending-requests conn)))
(defn resolved? "Has this request been answered, expired, or cleared?" [conn request]
  (not-any? #(= (:token request) (:token %)) (server/pending-requests conn)))
(defn- encode-reply [request value]
  (if-let [id (:reply-schema request)]
    (let [[root s] (schema/lookup id)] (schema/encode root s value))
    (throw (u/error :schema "No typed reply schema; use app-server/reply! with wire data" {:kind (:kind request)}))))
(defn respond! "Send a typed response to a pending request exactly once locally." [conn request value]
  (server/reply! conn (:token request) {:result (encode-reply request value)}))
(defn reject! "Send a JSON-RPC error for a pending request." [conn request code message]
  (server/reply! conn (:token request) {:error {"code" code "message" message}}))
(defn handle!
  "Install the sole responder for a request kind. f returns response data or ::defer.
   Duplicate handlers are rejected. Returns a registration for unhandle!."
  [conn kind f]
  (let [d (get-in @schema/catalog [:server-requests kind])
        method (:wire/method d)
        handler (fn [raw]
                  (let [request (domain-request raw) response (f request)]
                    (if (= ::defer response) ::server/defer {:result (encode-reply request response)})))]
    (when-not d (throw (u/error :operation "Unknown server request kind" {:kind kind})))
    (locking (:handlers conn)
      (when (contains? @(:handlers conn) method)
        (throw (u/error :handler "A handler already owns this request kind" {:kind kind})))
      (swap! (:handlers conn) assoc method handler))
    {:connection conn :method method :handler handler}))
(defn unhandle! "Remove a handler registration without replacing another owner." [registration]
  (let [{:keys [connection method handler]} registration]
    (locking (:handlers connection)
      (when (identical? handler (get @(:handlers connection) method))
        (swap! (:handlers connection) dissoc method))))
  nil)
