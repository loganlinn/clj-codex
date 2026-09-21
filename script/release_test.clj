(ns release-test
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing run-tests]]
            [release :as release]))

(def tag "v0.1.0")
(def draft-url "https://github.com/loganlinn/clj-codex/releases/untagged-fixture")
(def origin "https://github.com/loganlinn/clj-codex.git")

(defn mutating? [args]
  (or (= ["git" "tag" "-a"] (take 3 args))
      (= ["git" "-c" "push.followTags=false" "push"] (take 4 args))
      (= ["gh" "release" "create"] (take 3 args))))

(defn with-repo [f]
  (let [temp (fs/create-temp-dir {:prefix "codex-release-test-"})
        root (fs/create-dirs (fs/path temp "work"))
        remote (str (fs/path temp "remote.git"))
        calls (atom [])
        run (fn [& args]
              (str/trim (:out (apply process/shell
                                     {:dir (str root) :out :string :err :string
                                      :extra-env {"GIT_CONFIG_GLOBAL" "/dev/null" "GIT_CONFIG_NOSYSTEM" "1"}}
                                     args))))]
    (try
      (run "git" "init" "--bare" remote)
      (run "git" "init" "-b" "main")
      (run "git" "config" "user.name" "Release Test")
      (run "git" "config" "user.email" "release@example.invalid")
      (spit (fs/file root "source.txt") "original\n")
      (run "git" "add" "source.txt")
      (run "git" "commit" "-m" "Initial commit")
      (run "git" "remote" "add" "origin" remote)
      (run "git" "push" "origin" "HEAD:refs/heads/main")
      (let [commit (run "git" "rev-parse" "HEAD")
            state (atom {:origin origin :push-origin origin
                         :repo {:permissions {:push true}} :releases [[]]
                         :ci [{:headSha commit :headBranch "main" :event "push"
                               :status "completed" :conclusion "success" :url "https://example.invalid/ci"}]
                         :pom-status 404 :jar-status 404})]
        (with-redefs [release/command!
                      (fn [& args]
                        (let [args (vec args)]
                          (swap! calls conj args)
                          (when-let [fail? (:fail-command @state)]
                            (when (fail? args)
                              (throw (ex-info "Simulated command failure" {:args args}))))
                          (cond
                            (= ["git" "remote" "get-url" "--all" "origin"] args) (:origin @state)
                            (= ["git" "remote" "get-url" "--push" "--all" "origin"] args) (:push-origin @state)
                            (= "git" (first args)) (apply run args)
                            (= ["gh" "api"] (take 2 args))
                            (json/generate-string (if (str/includes? (last args) "/releases?")
                                                    (:releases @state) (:repo @state)))
                            (= ["gh" "run" "list"] (take 3 args)) (json/generate-string (:ci @state))
                            (= ["gh" "release" "create"] (take 3 args)) draft-url
                            :else (throw (ex-info "Unexpected command" {:args args})))))
                      http/head
                      (fn [url opts]
                        (swap! calls conj [:http/head url opts])
                        (when (:http-error @state)
                          (throw (ex-info "Simulated network failure" {})))
                        (let [pom? (str/ends-with? url ".pom")]
                          (when (and (not pom?) (:after-clojars @state))
                            ((:after-clojars @state)))
                          {:status (get @state (if pom? :pom-status :jar-status))}))
                      release/check-release!
                      (fn [tag]
                        (swap! calls conj [:release/check tag])
                        (when (:check-error @state)
                          (throw (ex-info "Simulated release check failure" {}))))]
          (binding [*out* (java.io.StringWriter.)]
            (f {:root root :run run :commit commit :state state :calls calls}))))
      (finally (fs/delete-tree temp)))))

(deftest invalid-arguments-stop-before-commands
  (with-redefs [release/command! (fn [& args] (throw (ex-info "Unexpected command" {:args args})))]
    (doseq [args [[] [tag "extra"]]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Usage:" (apply release/-main args))))
    (doseq [value [nil "" "0.1.0" "v01.0.0" "v1.0.0-SNAPSHOT" "v1.0.0-rc.01"
                   "v1.0.0-preview.1" "v1.0.0;echo nope"]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Expected vMAJOR" (release/draft! value))))))

(deftest stable-draft-pushes-only-the-selected-annotated-tag
  (with-repo
    (fn [{:keys [run commit calls]}]
      (run "git" "config" "push.followTags" "true")
      (run "git" "tag" "-a" "local-only" "-m" "Keep local")
      (is (= draft-url (release/draft! tag)))
      (is (= "tag" (run "git" "cat-file" "-t" (str "refs/tags/" tag))))
      (is (= commit (run "git" "rev-parse" (str "refs/tags/" tag "^{commit}"))))
      (is (= (str commit "\trefs/tags/" tag "^{}")
             (run "git" "ls-remote" "origin" (str "refs/tags/" tag "^{}"))))
      (is (str/blank? (run "git" "ls-remote" "origin" "refs/tags/local-only")))
      (is (= (str commit "\trefs/heads/main") (run "git" "ls-remote" "origin" "refs/heads/main")))
      (is (= [["git" "tag" "-a" tag "-m" "Release 0.1.0" commit]
              ["git" "-c" "push.followTags=false" "push" "origin" "refs/tags/v0.1.0:refs/tags/v0.1.0"]
              ["gh" "release" "create" tag "--repo" release/github-repository
               "--verify-tag" "--draft" "--generate-notes" "--title" tag "--target" commit]]
             (filterv mutating? @calls)))
      (is (< (.indexOf @calls [:release/check tag])
             (.indexOf @calls ["git" "-c" "push.followTags=false" "push" "origin" "refs/tags/v0.1.0:refs/tags/v0.1.0"])))
      (let [ci-call (first (filter #(= ["gh" "run" "list"] (take 3 %)) @calls))]
        (is (= commit (nth ci-call (inc (.indexOf ci-call "--commit")))))
        (is (not (some #{"--status"} ci-call)))))))

(deftest prereleases-are-inferred-from-the-tag
  (with-repo
    (fn [{:keys [calls state]}]
      (swap! state assoc :origin "git@github.com:loganlinn/clj-codex.git"
             :push-origin "ssh://git@github.com/loganlinn/clj-codex.git")
      (doseq [suffix ["alpha.0" "beta.2" "rc.1"]]
        (is (= draft-url (release/draft! (str tag "-" suffix))))
        (is (= "--prerelease" (last (last @calls))))))))

(deftest preflight-failures-never-create-a-tag
  (with-repo
    (fn [{:keys [state calls run]}]
      (let [initial @state
            ci (first (:ci initial))]
        (doseq [[label changes message]
                [["wrong fetch repository" {:origin "https://github.com/someone/fork.git"} #"Origin must"]
                 ["wrong push repository" {:push-origin "git@github.com:someone/fork.git"} #"Origin must"]
                 ["multiple push destinations" {:push-origin (str origin "\n" origin)} #"Origin must"]
                 ["no write access" {:repo {:permissions {:push false}}} #"write access"]
                 ["existing draft on a later page" {:releases [[] [{:tag_name tag :draft true}]]} #"already uses"]
                 ["existing published release" {:releases [[{:tag_name tag :draft false}]]} #"already uses"]
                 ["invalid releases response" {:releases nil} #"Unexpected GitHub"]
                 ["missing CI" {:ci []} #"CI run"]
                 ["failed CI" {:ci [(assoc ci :conclusion "failure")]} #"CI run"]
                 ["newer pending CI" {:ci [(assoc ci :status "in_progress" :conclusion nil) ci]} #"CI run"]
                 ["different commit" {:ci [(assoc ci :headSha "wrong")]} #"CI run"]
                 ["different branch" {:ci [(assoc ci :headBranch "feature")]} #"CI run"]
                 ["PR CI" {:ci [(assoc ci :event "pull_request")]} #"CI run"]
                 ["published POM" {:pom-status 200} #"Clojars already"]
                 ["partial upload with JAR only" {:jar-status 200} #"Clojars already"]
                 ["Clojars access denied" {:pom-status 403} #"Cannot check Clojars"]
                 ["Clojars rate limit" {:pom-status 429} #"Cannot check Clojars"]
                 ["Clojars outage" {:jar-status 503} #"Cannot check Clojars"]
                 ["Clojars timeout" {:http-error true} #"network failure"]
                 ["GitHub authentication failure" {:fail-command #(= "gh" (first %))} #"command failure"]
                 ["remote tag lookup failure" {:fail-command #(= ["git" "ls-remote"] (take 2 %))} #"command failure"]]]
          (testing label
            (reset! state (merge initial changes))
            (reset! calls [])
            (is (thrown-with-msg? clojure.lang.ExceptionInfo message (release/draft! tag)))
            (is (empty? (filter mutating? @calls)))
            (is (str/blank? (run "git" "tag" "--list" tag)))))))))

(deftest existing-tags-are-never-replaced
  (with-repo
    (fn [{:keys [run calls]}]
      (run "git" "tag" "-a" tag "-m" "Existing")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exists locally" (release/draft! tag)))
      (run "git" "push" "origin" (str "refs/tags/" tag))
      (run "git" "tag" "-d" tag)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exists on origin" (release/draft! tag)))
      (is (empty? (filter mutating? @calls))))))

(deftest dirty-checkouts-and-source-changes-stop-tagging
  (doseq [file ["source.txt" "untracked.txt"]]
    (with-repo
      (fn [{:keys [root calls]}]
        (spit (fs/file root file) "changed\n")
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"clean checkout" (release/draft! tag)))
        (is (empty? (filter mutating? @calls))))))
  (with-repo
    (fn [{:keys [state root run calls]}]
      (swap! state assoc :after-clojars
             #(do (spit (fs/file root "source.txt") "new commit\n")
                  (run "git" "commit" "-am" "Concurrent change")))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"HEAD changed" (release/draft! tag)))
      (is (empty? (filter mutating? @calls))))))

(deftest partial-failures-preserve-tags-and-stop-later-actions
  (doseq [[changes expected-writes remote-tag?]
          [[{:check-error true} 1 false]
           [{:fail-command #(= ["git" "-c" "push.followTags=false" "push"] (take 4 %))} 2 false]
           [{:fail-command #(= ["gh" "release" "create"] (take 3 %))} 3 true]]]
    (with-repo
      (fn [{:keys [state run calls]}]
        (swap! state merge changes)
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Simulated" (release/draft! tag)))
        (is (= expected-writes (count (filter mutating? @calls))))
        (is (= tag (run "git" "tag" "--list" tag)))
        (is (= remote-tag? (not (str/blank? (run "git" "ls-remote" "origin" (str "refs/tags/" tag))))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exists locally" (release/draft! tag)))))))

(defn -main []
  (let [{:keys [fail error]} (run-tests 'release-test)]
    (when (pos? (+ fail error))
      (throw (ex-info "Release task tests failed" {:fail fail :error error})))))
