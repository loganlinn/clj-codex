(ns codex.api
  "Inspectable operations, schema-aware invocation, and reducible pagination."
  (:require [clojure.edn :as edn] [clojure.java.io :as io]
            [codex.app-server :as server]
            [codex.impl.schema :as schema]
            [codex.impl.util :as u]))

(def ^:private operation-catalog
  (delay (merge-with merge (:operations @schema/catalog)
                     (edn/read-string (slurp (io/resource "codex/operation-semantics.edn"))))))

(defn operations "Return the operation catalog, optionally filtered by a descriptor predicate."
  ([] @operation-catalog)
  ([pred] (into (sorted-map) (filter (comp pred val)) (operations))))
(defn describe "Describe an operation keyword such as :thread/read or :thread.goal/set." [op]
  (or (get (operations) op) (throw (u/error :operation "Unknown operation" {:op op}))))
(defn provenance "Return the Codex generator version and schema digest." [] (:provenance @schema/catalog))

(defn- params [conn descriptor args]
  (let [root (schema/document "codex/app-server/ClientRequest.json")
        s (:wire/params descriptor)
        experimental? (get-in (server/info conn) [:capabilities :experimental-api])
        wire (cond
               (nil? s) ::server/omit
               (= "null" (get s "type")) nil
               :else (schema/encode root s args))]
    (when (and (:experimental? descriptor) (not experimental?))
      (throw (u/error :capability "Operation requires :experimental-api" {:op (:op descriptor)})))
    (when (and (not experimental?) s)
      (let [stable-s (some #(when (= (:wire/method descriptor) (get-in % ["properties" "method" "enum" 0]))
                             (get-in % ["properties" "params"])) (get @schema/stable "oneOf"))
            errors (schema/errors @schema/stable stable-s wire [] true)]
        (when (seq errors)
          (throw (u/error :capability "Arguments require the experimental API or do not match the stable schema"
                          {:op (:op descriptor) :issues errors})))))
    wire))

(defn- normalize-result [op result]
  (cond
    (#{:thread/start :thread/resume :thread/fork} op)
    {:codex.thread/thread (:thread result)
     :codex.thread/config (dissoc result :thread :instruction-sources :codex.api/extensions)
     :codex.thread/instruction-sources (:instruction-sources result)
     :codex.api/extensions (:codex.api/extensions result)}
    (#{:thread/read :thread/unarchive :thread.metadata/update :thread/rollback :thread/revert} op)
    (cond-> (:thread result)
      (:codex.api/extensions result) (assoc :codex.api/response-extensions (:codex.api/extensions result)))
    (= op :turn/start) (:turn result)
    :else result))

(defrecord PendingOperation [request descriptor])
(defmethod print-method PendingOperation [p w]
  (.write ^java.io.Writer w (str "#codex/operation " (pr-str {:op (get-in p [:descriptor :op]) :id (get-in p [:request :id])}))))

(defn submit!
  "Submit {:op keyword :args map} and return a pending operation.
   opts contains local RPC controls such as :timeout-ms; it is not sent as API arguments."
  ([conn operation] (submit! conn operation {}))
  ([conn {:keys [op args] :or {args {}}} opts]
   (let [d (describe op)]
     (->PendingOperation (server/request! conn (:wire/method d) (params conn d args) opts) d))))

(defn- result [pending raw]
  (let [d (:descriptor pending)
        decoded (if-let [id (:result-schema d)] (schema/decode-type id raw) raw)]
    (normalize-result (:op d) decoded)))

(defn await!
  "Await a submitted operation. A timed wait never abandons or interrupts remote work."
  ([pending]
   (try (result pending (server/await! (:request pending)))
        (catch clojure.lang.ExceptionInfo e
          (throw (ex-info (ex-message e) (assoc (ex-data e) :op (get-in pending [:descriptor :op])) e)))))
  ([pending timeout-ms timeout-value]
   (try
     (let [sentinel (Object.) raw (server/await! (:request pending) timeout-ms sentinel)]
       (if (identical? sentinel raw) timeout-value (result pending raw)))
     (catch clojure.lang.ExceptionInfo e
       (throw (ex-info (ex-message e) (assoc (ex-data e) :op (get-in pending [:descriptor :op])) e))))))

(defn invoke! "Run an operation and return its domain result after the RPC response."
  ([conn operation] (await! (submit! conn operation)))
  ([conn operation opts] (await! (submit! conn operation opts))))

(defn pages
  "Return a reducible of pages. Reduction performs I/O and stops at reduced or a nil cursor."
  [conn operation]
  (reify clojure.lang.IReduceInit
    (reduce [_ rf init]
      (loop [acc init args (or (:args operation) {}) seen #{}]
        (let [page (invoke! conn (assoc operation :args args))
              _ (when-not (sequential? (:data page))
                  (throw (u/error :pagination "Operation did not return a page" {:op (:op operation)})))
              next-acc (rf acc page)
              cursor (:next-cursor page)]
          (cond
            (reduced? next-acc) @next-acc
            (nil? cursor) next-acc
            (contains? seen cursor) (throw (u/error :pagination "Repeated pagination cursor" {:op (:op operation)}))
            :else (recur next-acc (assoc args :cursor cursor) (conj seen cursor))))))))

(defn entries "Return a reducible of entries across pages. Does not fetch during construction." [conn operation]
  (reify clojure.lang.IReduceInit
    (reduce [_ rf init]
      (reduce (fn [acc page]
                (loop [acc acc xs (seq (:data page))]
                  (if (seq xs)
                    (let [next-acc (rf acc (first xs))]
                      (if (reduced? next-acc) next-acc (recur next-acc (next xs))))
                    acc)))
              init (pages conn operation)))))
