(ns codex.review
  "Review targets, submission, and parsing of rendered reports."
  (:require [clojure.string :as str]
            [codex.api :as api] [codex.thread :as thread]))
(defn uncommitted "Review staged, unstaged, and untracked changes." [] {:type :uncommitted-changes})
(defn branch "Review against a base branch." [name] {:type :base-branch :branch name})
(defn commit "Review a commit." ([sha] {:type :commit :sha sha}) ([sha title] {:type :commit :sha sha :title title}))
(defn custom "Review using custom instructions." [instructions] {:type :custom :instructions instructions})
(defn start! "Start a review. Preserve both review-thread-id and the initial turn."
  ([c t target] (start! c t target {}))
  ([c t target opts]
   (api/invoke! c {:op :review/start :args (assoc opts :thread-id (::thread/id (thread/ref t)) :target target)})))

(defn- finding-header [line]
  ;; Anchor locations at the end; paths can contain spaces, colons, and backslashes.
  (when-let [[_ title path start end]
             (re-matches #"- (.+) — ((?:/|[A-Za-z]:[\\/]|\\\\).+):([0-9]+)-([0-9]+)" line)]
    (let [start (parse-long start) end (parse-long end)
          priority (some-> (re-find #"^\[P([0-3])\](?:\s|$)" title) second parse-long)]
      (when (and start end (<= 0 start 4294967295) (<= 0 end 4294967295))
        (cond-> {:title title
                 :code-location {:absolute-file-path path :line-range {:start start :end end}}}
          priority (assoc :priority priority))))))

(defn- parse-findings [lines]
  (loop [remaining (seq lines) findings []]
    (if-let [line (first remaining)]
      (cond
        (= "" line) (recur (next remaining) findings)
        :else
        (when-let [finding (finding-header line)]
          (let [[body tail] (split-with #(str/starts-with? % "  ") (next remaining))]
            (recur (seq tail)
                   (conj findings (assoc finding :body (str/join "\n" (map #(subs % 2) body))))))))
      findings)))

(defn parse-report
  "Parse the built-in reviewer's rendered report into plain-keyed data.

  Returns :findings and the original :report-text. Findings are extracted only
  from a complete, recognized findings block. An empty findings vector means
  none were extracted, not that the review passed. Prose-only reports and
  malformed blocks both return empty findings. Priority comes from a [P0]-[P3]
  title prefix. Confidence scores and the overall verdict cannot be recovered."
  [report]
  (when-not (string? report)
    (throw (ex-info "Review report must be a string" {:report report})))
  ;; Matches codex-rs/protocol/src/review_format.rs at Codex 78245b47af.
  ;; Do not parse arbitrary prose bullets or return a partially parsed block.
  (let [lines (str/split-lines report)
        headings #{"Review comment:" "Full review comments:"}
        index (first (keep-indexed #(when (headings %2) %1) lines))
        findings (when index (parse-findings (subvec lines (inc index))))
        parsed? (and (seq findings)
                     (if (= "Review comment:" (get lines index))
                       (= 1 (count findings))
                       (< 1 (count findings))))]
    {:findings (if parsed? findings [])
     :report-text report}))
