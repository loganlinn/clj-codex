#!/usr/bin/env bb
(require '[babashka.fs :as fs])
(load-file (str (fs/path (fs/parent *file*) "support.clj")))
(require '[examples.support :as example]
         '[babashka.process :as process]
         '[clojure.pprint :refer [pprint]]
         '[clojure.string :as str]
         '[codex.app-server :as server]
         '[codex.event :as event]
         '[codex.item :as item]
         '[codex.review :as review]
         '[codex.thread :as thread]
         '[codex.turn :as turn])

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
  (let [cwd (repo-root repo)
        c (example/connect! {:cwd cwd})]
    (try
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
              (let [completed (example/successful! (:turn result))
                    _ (when-not (= turn-id (::turn/id completed))
                        (throw (ex-info "Unexpected review turn ID" {:expected turn-id :turn completed})))
                    report (some #(when (= :exited-review-mode (::item/type %)) (::item/review %))
                                 (::turn/items completed))]
                (when-not report
                  (throw (ex-info "Review completed without a review report" {:turn completed})))
                (review/parse-report report))))
          (finally (event/unlisten! subscription))))
      (finally (server/close! c)))))

(when (= *file* (System/getProperty "babashka.file"))
  (example/main! "bb examples/review_agent.clj [REPO] [--prompt 'Review changes against main.']"
                 (merge example/timeout-spec
                        {:repo {:coerce :string :default "." :desc "Git working tree path (default: current directory)"}
                         :prompt {:alias :p :coerce :string :desc "Custom review instructions; replaces the default target"}
                         :model {:coerce :string :desc "Optional model ID; otherwise use the configured model"}
                         :format {:coerce :string :default "text" :validate #{"text" "edn"}
                                  :desc "Output format: text or edn (default: text)"}})
                 [:repo] (fn [opts]
                           (let [result (review! opts)]
                             (if (= "edn" (:format opts))
                               (pprint result)
                               (println (:raw result)))))))
