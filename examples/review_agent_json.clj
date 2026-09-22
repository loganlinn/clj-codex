#!/usr/bin/env bb
(require '[babashka.fs :as fs])
(load-file (str (fs/path (fs/parent *file*) "support.clj")))
(ns examples.review-agent-json
  "Run the built-in reviewer and print its structured JSON result.

  bb examples/review_agent_json.clj
  bb examples/review_agent_json.clj '/path/to/my repo' > review.json
  bb examples/review_agent_json.clj . --prompt 'Review changes against main.'

  The repository defaults to the current directory. The script resolves its
  Git working tree root, starts an owned server there, and creates a read-only
  thread with approval policy never. --model overrides the configured model.
  Without --prompt, the review covers staged, unstaged, and untracked changes.
  Custom instructions replace that target. The existing Codex account or
  provider supplies the model and can consume paid usage.

  Both initialize.capabilities.experimentalApi and thread/start's
  experimentalRawEvents are enabled. This raw stream is internal/experimental.
  Its behavior and the review rubric were inspected at Codex revision
  78245b47af. Future server versions can change this contract.

  A raw listener is installed before review/start! with inline delivery.
  It reads assistant output_text parts from rawResponseItem/completed and
  correlates both IDs from the start response, including events received early.
  It excludes a trailing Codex memory-citation block before JSON validation.
  It decodes JSON and validates the full review shape before accepting it.
  It retains the last valid result until the matching turn/completed arrives.
  It does not parse the rendered exitedReviewMode.review prose.

  review! returns a string-keyed map with the original review fields, including
  confidence scores and the overall verdict. Optional priority fields remain
  unchanged. The CLI prints this map as JSON to stdout. Progress goes to stderr.
  A valid completed review exits zero, including reviews with findings.
  Missing or invalid JSON, a failed turn, observation loss, and timeouts fail.
  On timeout, the script requests interruption before closing the server.

  App-server reference: https://learn.chatgpt.com/docs/app-server#review"
  (:use examples.support)
  (:require [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]
            [codex.app-server :as server]
            [codex.review :as review]
            [codex.thread :as thread]
            [codex.turn :as turn]))

(defn repo-root [path]
  (let [{:keys [exit out err]}
        (process/shell {:out :string :err :string :continue true :shutdown nil}
                       "git" "-C" path "rev-parse" "--show-toplevel")]
    (when-not (zero? exit)
      (throw (ex-info "Expected a path inside a Git working tree"
                      {:path path :git-error (str/trim err)})))
    (str/trim out)))

(defn confidence? [x]
  (and (number? x) (<= 0 x 1)))

(defn finding? [finding]
  (let [location (get finding "code_location")
        lines (get location "line_range")
        start (get lines "start")
        end (get lines "end")
        priority (get finding "priority")]
    (and (map? finding)
         (string? (get finding "title"))
         (string? (get finding "body"))
         (confidence? (get finding "confidence_score"))
         ;; The review rubric permits omitted or null priority. Preserve it.
         (or (nil? priority) (and (integer? priority) (<= 0 priority 3)))
         (map? location)
         (string? (get location "absolute_file_path"))
         (not (str/blank? (get location "absolute_file_path")))
         (map? lines)
         (integer? start) (integer? end)
         (<= 1 start end 4294967295))))

(defn review-output? [value]
  ;; The complete shape from codex-rs/prompts/templates/review/rubric.md,
  ;; revision 78245b47af. Leave all original keys and values intact.
  (and (map? value)
       (vector? (get value "findings"))
       (every? finding? (get value "findings"))
       (contains? #{"patch is correct" "patch is incorrect"}
                  (get value "overall_correctness"))
       (string? (get value "overall_explanation"))
       (confidence? (get value "overall_confidence_score"))))

(defn parse-review-json [text]
  (try
    ;; Codex can append memory citations after the JSON. Match only a final
    ;; block on its own line, so marker text inside JSON strings stays intact.
    (let [text (str/replace text #"(?ms)^[ \t]*<oai-mem-citation>.*?</oai-mem-citation>\s*\z" "")]
      (with-open [reader (java.io.StringReader. text)]
        ;; parse-string accepts trailing input. Require exactly one JSON value.
        (let [values (doall (take 2 (json/parsed-seq reader)))]
          (when (and (= 1 (count values)) (review-output? (first values)))
            (first values)))))
    ;; Progress and the rendered summary are also assistant text.
    (catch Exception _ nil)))

(defn raw-review [item]
  (when (and (= "message" (get item "type"))
             (= "assistant" (get item "role")))
    (let [parts (filter #(= "output_text" (get % "type")) (get item "content"))
          texts (map #(get % "text") parts)]
      (when (every? string? texts)
        (parse-review-json (apply str texts))))))

(defn observe-review! [identity result done message]
  (let [method (get message "method")
        params (get message "params")]
    (when (and (#{"rawResponseItem/completed" "turn/completed"} method)
               (not (realized? done)))
      ;; Only this listener's worker waits. The reader can receive the start
      ;; reply while the bounded observer queue retains early events in order.
      (when-let [{:keys [thread-id turn-id] :as ids} @identity]
        (when (and (= thread-id (get params "threadId"))
                   (= turn-id (if (= "turn/completed" method)
                                (get-in params ["turn" "id"])
                                (get params "turnId"))))
          (case method
            "rawResponseItem/completed"
            (when-let [candidate (raw-review (get params "item"))]
              (reset! result candidate))

            "turn/completed"
            (let [status (get-in params ["turn" "status"])]
              (when-not (= "completed" status)
                (throw (ex-info "Review turn did not complete successfully"
                                (assoc ids :status status :error (get-in params ["turn" "error"])))))
              (when-not @result
                (throw (ex-info "Review completed without valid structured JSON" ids)))
              (deliver done {:value @result}))))))))

(defn review! [{:keys [repo prompt timeout-ms model]
                :or {repo "." timeout-ms 300000}}]
  (when (and prompt (str/blank? prompt))
    (throw (ex-info "--prompt must not be blank" {})))
  (let [cwd (repo-root repo)]
    (server/with-connection [c (connect! {:cwd cwd :experimental true})]
      (let [context (thread/start! c
                                   (cond-> {:cwd cwd :sandbox :read-only :approval-policy :never
                                            :experimental-raw-events true}
                                     model (assoc :model model)))
            id (get-in context [::thread/thread ::thread/id])
            identity (promise)
            result (atom nil)
            done (promise)
            subscription (server/listen! c {:on-error #(deliver done {:error %})}
                                         #(observe-review! identity result done %))]
        (try
          (let [target (if prompt (review/custom prompt) (review/uncommitted))
                started (review/start! c id target {:delivery :inline})
                ids {:thread-id (:review-thread-id started)
                     :turn-id (get-in started [:turn ::turn/id])}]
            (when-not (every? #(and (string? %) (not (str/blank? %))) (vals ids))
              (throw (ex-info "Review start response omitted review IDs" ids)))
            (deliver identity ids)
            (binding [*out* *err*]
              (println "Review thread:" (:thread-id ids) "Repository:" cwd))
            (let [completed (deref done timeout-ms ::timeout)]
              (when (= ::timeout completed)
                (turn/interrupt! c (turn/ref (:thread-id ids) (:turn-id ids)))
                (throw (ex-info "Review timed out; interruption requested" ids)))
              (when-let [error (:error completed)] (throw error))
              (:value completed)))
          (finally
            ;; Release the worker even if review/start! fails before IDs arrive.
            (deliver identity nil)
            (server/unlisten! subscription)))))))

(when (= *file* (System/getProperty "babashka.file"))
  (main! "bb examples/review_agent_json.clj [REPO] [--prompt 'Review changes against main.']"
         (merge timeout-spec
                {:repo {:coerce :string :default "." :desc "Git working tree path (default: current directory)"}
                 :prompt {:alias :p :coerce :string :desc "Custom review instructions; replaces the default target"}
                 :model {:coerce :string :desc "Optional model ID; otherwise use the configured model"}})
         [:repo] #(println (json/generate-string (review! %) {:pretty true}))))
