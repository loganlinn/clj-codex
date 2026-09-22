(ns codex.review-json-example-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [codex.app-server :as server]))

;; This is a Babashka script, including its classpath and CLI helpers.
(load-file "examples/review_agent_json.clj")
(require '[examples.review-agent-json :as example])

(def review-output
  {"findings" [{"title" "[P1] Preserve pending requests"
                "body" "Closing here discards the queued requests.\nKeep **all** entries."
                "confidence_score" 0.95
                "priority" 1
                "code_location" {"absolute_file_path" "/tmp/my repo/queue.clj"
                                 "line_range" {"start" 12 "end" 14}}}]
   "overall_correctness" "patch is incorrect"
   "overall_explanation" "The change loses pending requests."
   "overall_confidence_score" 0.9})

(def memory-citation
  ;; Codex can append this metadata even when the reviewer requests JSON only.
  (str "<oai-mem-citation>\n<citation_entries>\n"
       "MEMORY.md:1-2|note=[review context]\n</citation_entries>\n"
       "<rollout_ids>\n00000000-0000-0000-0000-000000000000\n"
       "</rollout_ids>\n</oai-mem-citation>"))

(def start-result
  {"reviewThreadId" "review-thread"
   "turn" {"id" "review-turn" "status" "inProgress" "items" [] "error" nil}})

(defn raw-message [text]
  {"method" "rawResponseItem/completed"
   "params" {"threadId" "review-thread" "turnId" "review-turn"
             "item" {"type" "message" "role" "assistant"
                     "content" [{"type" "output_text" "text" text}]}}})

(defn completed [status]
  {"method" "turn/completed"
   "params" {"threadId" "review-thread"
             "turn" {"id" "review-turn" "status" status "items" [] "error" nil}}})

(defn reply! [receive! request result]
  (receive! {"id" (get request "id") "result" result}))

(defn before-response [events]
  (fn [request {:keys [receive!]}]
    (doseq [event events] (receive! event))
    (reply! receive! request start-result)))

(defn exercise
  ([respond!] (exercise respond! {}))
  ([respond! opts]
   (let [connect! server/connect!
         listen! server/listen!
         sent (atom [])
         closes (atom 0)
         connection (atom nil)
         observer (atom nil)]
     (with-redefs [example/repo-root (constantly "/tmp/my repo")
                   server/listen! (fn [& args]
                                    (let [s (apply listen! args)] (reset! observer s) s))
                   server/connect!
                   (fn [options]
                     (let [c (connect!
                              (assoc options :transport
                                     {:type :custom
                                      :open
                                      (fn [{:keys [receive!] :as callbacks}]
                                        {:close! #(swap! closes inc)
                                         :send!
                                         (fn [message]
                                           (swap! sent conj message)
                                           (case (get message "method")
                                             "initialize" (reply! receive! message {"userAgent" "test"})
                                             "thread/start" (reply! receive! message {"thread" {"id" "source-thread"}})
                                             "review/start" (respond! message callbacks)
                                             "turn/interrupt" (reply! receive! message {})
                                             nil))})}))]
                       (reset! connection c)
                       c))]
       (let [outcome (try
                       (binding [*err* (java.io.StringWriter.)]
                         {:value (example/review! (merge {:timeout-ms 1000} opts))})
                       (catch Exception e {:error e}))]
         (merge outcome {:sent @sent :closes @closes :connection @connection :observer @observer}))))))

(defn assert-cleanup [{:keys [closes connection observer]}]
  (is (= 1 closes))
  (is (#{:closed :failed} (server/status connection)))
  (is (empty? @(:listeners connection)))
  (when-let [worker @(:worker observer)]
    (.join ^Thread worker 1000)
    (is (not (.isAlive ^Thread worker))))
  (is (empty? @(:pending connection))))

(deftest original-review-fields-round-trip
  (doseq [value [review-output
                 (update review-output "findings" #(mapv (fn [f] (dissoc f "priority")) %))
                 (assoc-in review-output ["findings" 0 "priority"] nil)
                 (assoc review-output "unknown_future_field" {"Mixed_Key" false})
                 (assoc review-output "findings" [] "overall_correctness" "patch is correct")]]
    (is (= value (example/parse-review-json (json/generate-string value)))))
  (let [text (json/generate-string review-output)
        split (quot (count text) 2)
        item (get-in (raw-message text) ["params" "item"])]
    (is (= review-output
           (example/raw-review
            (assoc item "content" [{"type" "output_text" "text" (subs text 0 split)}
                                   {"type" "refusal" "refusal" "ignored"}
                                   {"type" "output_text" "text" (subs text split)}])))))
  (doseq [role ["user" "developer" "system"]]
    (is (nil? (example/raw-review
               (assoc (get-in (raw-message (json/generate-string review-output)) ["params" "item"])
                      "role" role))))))

(deftest reject-incomplete-or-invalid-review-json
  (doseq [key (keys review-output)]
    (is (nil? (example/parse-review-json (json/generate-string (dissoc review-output key))))))
  (doseq [key ["title" "body" "confidence_score" "code_location"]]
    (is (nil? (example/parse-review-json
               (json/generate-string (update-in review-output ["findings" 0] dissoc key))))))
  (doseq [[path value] [[["findings"] nil]
                        [["findings"] {}]
                        [["findings"] ["not a finding"]]
                        [["overall_correctness"] "good"]
                        [["overall_explanation"] nil]
                        [["overall_confidence_score"] 1.1]
                        [["findings" 0 "confidence_score"] -0.1]
                        [["findings" 0 "confidence_score"] "0.9"]
                        [["findings" 0 "priority"] 4]
                        [["findings" 0 "priority"] 1.5]
                        [["findings" 0 "body"] false]
                        [["findings" 0 "code_location" "absolute_file_path"] nil]
                        [["findings" 0 "code_location" "line_range"] {}]
                        [["findings" 0 "code_location" "line_range" "start"] 0]
                        [["findings" 0 "code_location" "line_range" "end"] 11]
                        [["findings" 0 "code_location" "line_range" "end"] 4294967296]]]
    (is (nil? (example/parse-review-json (json/generate-string (assoc-in review-output path value))))))
  (let [text (json/generate-string review-output)]
    (doseq [invalid ["" "I am reviewing the diff." "[]" "null" "false" "{"
                     (str text " trailing prose") (str text " {}")
                     (str "```json\n" text "\n```")]]
      (is (nil? (example/parse-review-json invalid))))))

(deftest review-json-with-memory-citations
  (doseq [value [review-output
                 (assoc review-output "findings" [] "overall_correctness" "patch is correct")
                 (assoc-in review-output ["findings" 0 "body"]
                           (str "Preserve the literal marker in the output:\n" memory-citation))]]
    (let [text (json/generate-string value {:pretty true})]
      (doseq [suffix [(str "\n" memory-citation) (str "\n\n" memory-citation "\n")]]
        (is (= value (example/parse-review-json (str text suffix)))))))
  (let [text (json/generate-string review-output)]
    (doseq [invalid [(str text " trailing prose\n" memory-citation)
                     (str text "\n{}\n" memory-citation)
                     (str text "\n" memory-citation "\ntrailing prose")
                     (str text "\n<oai-mem-citation>unclosed")
                     (str "{\"findings\":[]}\n" memory-citation)]]
      (is (nil? (example/parse-review-json invalid))))))

(deftest early-events-use-both-returned-review-ids
  (let [other (assoc review-output "findings" [] "overall_correctness" "patch is correct")
        raw (raw-message (json/generate-string review-output))
        result (exercise
                (before-response
                 [(raw-message "I am reviewing the changes.")
                  (raw-message (json/generate-string other))
                  raw
                  (assoc-in (raw-message (json/generate-string other)) ["params" "threadId"] "another-thread")
                  (assoc-in (raw-message (json/generate-string other)) ["params" "turnId"] "another-turn")
                  (assoc-in (completed "failed") ["params" "threadId"] "another-thread")
                  (assoc-in (completed "failed") ["params" "turn" "id"] "another-turn")
                  {"method" "item/completed" "params" {"threadId" "review-thread" "turnId" "review-turn"
                                                       "item" {"type" "exitedReviewMode" "review" "Rendered prose"}}}
                  (raw-message "Rendered summary after the structured result.")
                  (completed "completed")])
                {:model "test-model"})
        by-method (into {} (map (juxt #(get % "method") #(get % "params")) (:sent result)))]
    (is (= review-output (:value result)))
    (is (= ["initialize" "initialized" "thread/start" "review/start"]
           (mapv #(get % "method") (:sent result))))
    (is (true? (get-in by-method ["initialize" "capabilities" "experimentalApi"])))
    (is (= {"cwd" "/tmp/my repo" "sandbox" "read-only" "approvalPolicy" "never"
            "experimentalRawEvents" true "model" "test-model"}
           (get by-method "thread/start")))
    (is (= {"threadId" "source-thread" "delivery" "inline" "target" {"type" "uncommittedChanges"}}
           (get by-method "review/start")))
    (assert-cleanup result)))

(deftest wait-for-the-matching-terminal-event
  (let [ready (promise)
        result (future
                 (exercise
                  (fn [request {:keys [receive!] :as callbacks}]
                    (receive! (raw-message (json/generate-string review-output)))
                    (reply! receive! request start-result)
                    (deliver ready callbacks))
                  {:prompt "Review changes against main." :timeout-ms 5000}))
        callbacks (deref ready 2000 nil)]
    (try
      (is (some? callbacks))
      (is (= ::pending (deref result 25 ::pending)))
      ((:receive! callbacks) (assoc-in (completed "completed") ["params" "turn" "id"] "another-turn"))
      (is (= ::pending (deref result 25 ::pending)))
      ((:receive! callbacks) (completed "completed"))
      (let [finished (deref result 2000 nil)]
        (is (= review-output (:value finished)))
        (is (= {"type" "custom" "instructions" "Review changes against main."}
               (get-in (last (:sent finished)) ["params" "target"])))
        (assert-cleanup finished))
      (finally
        (when-let [close! (:closed! callbacks)] (close! nil))
        (future-cancel result)))))

(deftest missing-json-and-unsuccessful-turns-fail
  (doseq [text [nil "Rendered review prose." "{\"findings\":[]}"
                (json/generate-string (dissoc review-output "overall_correctness"))]]
    (let [events (cond-> [] text (conj (raw-message text)))
          result (exercise (before-response (conj events (completed "completed"))))]
      (is (re-find #"without valid structured JSON" (ex-message (:error result))))
      (is (= {:thread-id "review-thread" :turn-id "review-turn"} (ex-data (:error result))))
      (assert-cleanup result)))
  (doseq [status ["failed" "interrupted"]]
    (let [result (exercise (before-response [(raw-message (json/generate-string review-output))
                                             (completed status)]))]
      (is (= status (:status (ex-data (:error result)))))
      (assert-cleanup result))))

(deftest valid-empty-findings-succeed
  (let [value (assoc review-output "findings" [] "overall_correctness" "patch is correct")
        result (exercise (before-response [(raw-message (json/generate-string value))
                                           (completed "completed")]))]
    (is (= value (:value result)))
    (assert-cleanup result)))

(deftest completed-review-with-memory-citations-succeeds
  ;; Regression for a real Codex 0.155.1 review: valid empty findings followed
  ;; by memory citations, then the separately rendered summary and completion.
  (let [value (assoc review-output "findings" [] "overall_correctness" "patch is correct"
                     "overall_confidence_score" 0.88)
        text (str (json/generate-string value {:pretty true}) "\n" memory-citation)
        result (exercise (before-response [(raw-message text)
                                           (raw-message "Rendered review summary.")
                                           (completed "completed")]))]
    (is (= value (:value result)))
    (assert-cleanup result)))

(deftest timeout-interrupts-the-selected-turn
  (let [result (exercise (before-response [(raw-message (json/generate-string review-output))])
                         {:timeout-ms 10})]
    (is (re-find #"timed out" (ex-message (:error result))))
    (is (= {"threadId" "review-thread" "turnId" "review-turn"}
           (get-in (last (:sent result)) ["params"])))
    (is (= "turn/interrupt" (get (last (:sent result)) "method")))
    (assert-cleanup result)))

(deftest start-failure-releases-the-waiting-observer
  (let [result (exercise
                (fn [request {:keys [receive!]}]
                  (receive! (raw-message "An early event before the failed response."))
                  (receive! {"id" (get request "id")
                             "error" {"code" -32602 "message" "Review unavailable"}})))]
    (is (= :rpc (:codex.error/category (ex-data (:error result)))))
    (assert-cleanup result)))

(deftest observation-loss-fails-extraction
  (testing "Transport loss after the start acknowledgement"
    (let [result (exercise
                  (fn [request {:keys [receive! closed!]}]
                    (receive! (raw-message (json/generate-string review-output)))
                    (reply! receive! request start-result)
                    (closed! nil)))]
      (is (= :transport (:codex.error/category (ex-data (:error result)))))
      (assert-cleanup result)))
  (testing "Bounded observer queue overflow before the start response"
    (let [result (exercise (before-response (repeat 1100 (raw-message "Progress."))))]
      (is (= :overflow (:codex.error/category (ex-data (:error result)))))
      (assert-cleanup result))))
