#!/usr/bin/env bb
(require '[babashka.fs :as fs])
(load-file (str (fs/path (fs/parent *file*) "support.clj")))
(ns examples.review-agent
  "Run the built-in Codex reviewer and print its report.

  bb examples/review_agent.clj
  bb examples/review_agent.clj '/path/to/my repo' --format text
  bb examples/review_agent.clj /path/to/repo --prompt 'Review changes against main.'
  bb /path/to/clj-codex/examples/review_agent.clj . --timeout-ms 600000

  The repository defaults to the caller's current directory. git rev-parse
  resolves the working tree root, including linked worktrees and paths inside
  a repository. The script starts its server there and creates a read-only
  thread with approval policy never.

  Without --prompt, the target includes staged, unstaged, and untracked changes.
  With --prompt, custom instructions replace that target. Include the desired
  comparison in the prompt. --model MODEL_ID overrides the configured model.
  The script uses the existing Codex provider or account and can consume paid
  usage. It starts no login flow and needs no separate review-agent skill.

  An event listener is installed before review/start! submits the review.
  The script waits for the terminal turn event and reads the final report
  from the exitedReviewMode item. Progress goes to stderr.

  review! returns the map from codex.review/parse-report. The CLI prints EDN
  by default. --format text prints the original report. To parse an existing
  report without a server, call (codex.review/parse-report report).

  The result contains :findings and :report-text. The parser recognizes complete
  Review comment: and Full review comments: blocks, preserves Markdown bodies,
  and reads priority from [P0] through [P3] title prefixes. The rendered report
  omits confidence scores and the overall correctness verdict.
  Prose-only reports and malformed blocks yield an empty findings vector.
  Empty findings means none were extracted, not that the review passed.
  :report-text preserves the original report. Parsing depends on Codex's text
  format and does not validate findings against the repository.

  A completed review exits zero, even with findings. A failed turn, missing
  report, or timeout exits nonzero. On timeout, the script requests interruption
  before it closes the owned server.

  App-server reference: https://learn.chatgpt.com/docs/app-server"
  (:use examples.support)
  (:require [babashka.process :as process]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as str]
            [codex.app-server :as server]
            [codex.event :as event]
            [codex.item :as item]
            [codex.review :as review]
            [codex.thread :as thread]
            [codex.turn :as turn]))

(defn repo-root [path]
  (let [{:keys [exit out err]}
        (process/shell {:out :string :err :string :continue true :shutdown nil}
                       "git" "-C" path "rev-parse" "--show-toplevel")]
    (when-not (zero? exit)
      (throw (ex-info "Expected a path inside a Git working tree" {:path path :git-error (str/trim err)})))
    (str/trim out)))

(defn review! [{:keys [repo prompt timeout-ms model]}]
  (when (and prompt (str/blank? prompt))
    (throw (ex-info "--prompt must not be blank" {})))
  (let [cwd (repo-root repo)]
    (server/with-connection [c (connect! {:cwd cwd})]
      (let [context (thread/start! c
                                   (cond-> {:cwd cwd :sandbox :read-only :approval-policy :never}
                                     model (assoc :model model)))
            id (get-in context [::thread/thread ::thread/id])
            state (atom {})
            done (promise)
            ;; Subscribe before review/start: completion can precede its reply.
            subscription
            (event/listen! c {:thread-id id :on-error #(deliver done {:error %})}
                           (fn [e]
                             (swap! state event/apply-event e)
                             (when (event/terminal? e)
                               (deliver done {:turn (event/turn-snapshot @state id (::turn/id e))}))))]
        (try
          (let [target (if prompt (review/custom prompt) (review/uncommitted))
                started (review/start! c id target {:delivery :inline})
                turn-id (get-in started [:turn ::turn/id])
                review-id (:review-thread-id started)]
            (binding [*out* *err*]
              (println "Review thread:" review-id "Repository:" cwd))
            (let [result (deref done timeout-ms ::timeout)]
              (when (= ::timeout result)
                (turn/interrupt! c (turn/ref review-id turn-id))
                (throw (ex-info "Review timed out; interruption requested" {:thread-id review-id})))
              (when-let [error (:error result)] (throw error))
              (let [completed (successful! (:turn result))
                    _ (when-not (= turn-id (::turn/id completed))
                        (throw (ex-info "Unexpected review turn ID" {:expected turn-id :turn completed})))
                    report (some #(when (= :exited-review-mode (::item/type %)) (::item/review %))
                                 (::turn/items completed))]
                (when-not report
                  (throw (ex-info "Review completed without a review report" {:turn completed})))
                (review/parse-report report))))
          (finally (event/unlisten! subscription)))))))

(when (= *file* (System/getProperty "babashka.file"))
  (main! "bb examples/review_agent.clj [REPO] [--prompt 'Review changes against main.']"
         (merge timeout-spec
                {:repo {:coerce :string :default "." :desc "Git working tree path (default: current directory)"}
                 :prompt {:alias :p :coerce :string :desc "Custom review instructions; replaces the default target"}
                 :model {:coerce :string :desc "Optional model ID; otherwise use the configured model"}
                 :format {:coerce :string
                          :default "edn"
                          :validate #{"text" "edn"}
                          :desc "Output format"}})
         [:repo] (fn [opts]
                   (let [result (review! opts)]
                     (if (= "edn" (:format opts))
                       (pprint result)
                       (println (:report-text result)))))))
