(ns codex.review-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [codex.review :as review]))

;; Fixtures follow render_review_output_text and format_review_findings_block
;; in codex-rs/protocol/src/review_format.rs, revision 78245b47af.
(def single-report
  (str "This change drops pending work.\n\nReview comment:\n\n"
       "- [P1] Drain the queue before closing — /tmp/my repo/queue.clj:12-14\n"
       "  Closing here discards queued requests.\n"
       "  Wait until the queue is empty."))

(deftest single-finding
  (is (= {:report-text single-report
          :findings [{:title "[P1] Drain the queue before closing"
                      :priority 1
                      :body "Closing here discards queued requests.\nWait until the queue is empty."
                      :code-location {:absolute-file-path "/tmp/my repo/queue.clj"
                                      :line-range {:start 12 :end 14}}}]}
         (review/parse-report single-report))))

(deftest multiple-findings-and-markdown
  (let [report (str "Full review comments:\n\n"
                    "- [P0] Preserve the input — including its prefix — C:\\my repo\\a:b.clj:1-2\n"
                    "  Keep this Markdown:\n  \n  ```suggestion\n    (save input)\n  ```\n"
                    "  - An indented bullet is body text.\n\n"
                    "- Handle empty input — /tmp/input.clj:20-20\n"
                    "  Return an empty result.")]
    (is (= {:report-text report
            :findings [{:title "[P0] Preserve the input — including its prefix" :priority 0
                        :body (str "Keep this Markdown:\n\n```suggestion\n  (save input)\n```\n"
                                   "- An indented bullet is body text.")
                        :code-location {:absolute-file-path "C:\\my repo\\a:b.clj"
                                        :line-range {:start 1 :end 2}}}
                       {:title "Handle empty input" :body "Return an empty result."
                        :code-location {:absolute-file-path "/tmp/input.clj"
                                        :line-range {:start 20 :end 20}}}]}
           (review/parse-report report)))))

(deftest newline-and-priority-variants
  (let [crlf (str/replace single-report "\n" "\r\n")]
    (is (= (assoc (review/parse-report single-report) :report-text crlf)
           (review/parse-report crlf))))
  (doseq [priority (range 4)]
    (is (= priority
           (get-in (review/parse-report
                    (str/replace single-report "[P1]" (str "[P" priority "]")))
                   [:findings 0 :priority]))))
  (testing "Unsupported priority tags are preserved without inventing a priority"
    (let [result (review/parse-report (str/replace single-report "[P1]" "[P9]"))]
      (is (= 1 (count (:findings result))))
      (is (not (contains? (first (:findings result)) :priority))))))

(deftest empty-findings-are-not-clean-review-verdicts
  (doseq [report ["" "No issues found." "  No issues found.\n\n" "Reviewer failed to output a response."
                  "Review was interrupted. Please re-run /review and wait for it to complete."
                  "An arbitrary bullet:\n- [P1] Title — /tmp/a:1-2\n  Body."
                  "Review comment:\n\n- malformed finding\n  Body."
                  (str single-report "\nUnexpected trailing prose.")
                  (str single-report "\n\n- [P2] Missing location\n  Body.")
                  (str/replace single-report "Review comment:" "Full review comments:")
                  (str/replace single-report ":12-14" ":999999999999999999999-14")]]
    (is (= {:findings [] :report-text report}
           (review/parse-report report)))))

(deftest empty-body-and-unc-path
  (let [report "Review comment:\n\n- Check the share — \\\\host\\share\\file.clj:3-3"]
    (is (= [{:title "Check the share" :body ""
             :code-location {:absolute-file-path "\\\\host\\share\\file.clj"
                             :line-range {:start 3 :end 3}}}]
           (:findings (review/parse-report report))))))

(deftest invalid-input
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be a string"
                        (review/parse-report nil))))
