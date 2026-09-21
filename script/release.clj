(ns release
  (:require [babashka.http-client :as http]
            [babashka.process :as process]
            [babashka.tasks :as tasks]
            [cheshire.core :as json]
            [clojure.string :as str]
            [release-version :as version]))

(def repository "loganlinn/clj-codex")
(def github-repository (str "github.com/" repository))

(defn command! [& args]
  (str/trim (:out (apply process/shell {:out :string :err :string} args))))

(defn check-source! []
  (when-not (str/blank? (command! "git" "rev-parse" "--show-prefix"))
    (throw (ex-info "Run release:draft from the repository root" {})))
  (when-not (str/blank? (command! "git" "status" "--porcelain" "--untracked-files=all"))
    (throw (ex-info "Release drafts require a clean checkout, including untracked files" {})))
  (let [commit (command! "git" "rev-parse" "HEAD")]
    (version/check-committed! "." (command! "git" "show" (str commit ":version.edn")))
    commit))

(defn check-origin! []
  ;; Check both destinations, including multiple configured push URLs.
  (doseq [args [["git" "remote" "get-url" "--all" "origin"]
                ["git" "remote" "get-url" "--push" "--all" "origin"]]]
    (when-not (re-matches #"(?i)(?:https://github\.com/|git@github\.com:|ssh://git@github\.com/)loganlinn/clj-codex(?:\.git)?/?"
                          (apply command! args))
      (throw (ex-info "Origin must fetch and push only to github.com/loganlinn/clj-codex" {})))))

(defn github-api! [& args]
  (json/parse-string-strict (apply command! "gh" "api" "--hostname" "github.com" args) true))

(defn check-github! [tag]
  (let [repo (github-api! (str "repos/" repository))]
    (when-not (true? (get-in repo [:permissions :push]))
      (throw (ex-info "GitHub CLI needs write access to loganlinn/clj-codex. Run gh auth login." {}))))
  ;; The list endpoint includes drafts for users with write access.
  (let [pages (github-api! "--paginate" "--slurp" (str "repos/" repository "/releases?per_page=100"))]
    (when-not (and (vector? pages) (every? vector? pages))
      (throw (ex-info "Unexpected GitHub releases response" {})))
    (when (some #(= tag (:tag_name %)) (mapcat identity pages))
      (throw (ex-info "A GitHub release already uses this tag" {:tag tag})))))

(defn check-ci! [commit]
  (let [run (first (json/parse-string-strict
                    (command! "gh" "run" "list" "--repo" github-repository
                              "--workflow" "ci.yml" "--branch" "main" "--event" "push"
                              "--commit" commit "--limit" "1"
                              "--json" "headSha,headBranch,event,status,conclusion,url") true))]
    ;; Do not filter by success: a newer failed or pending run must block release.
    (when-not (and (= commit (:headSha run))
                   (= "main" (:headBranch run))
                   (= "push" (:event run))
                   (= "completed" (:status run))
                   (= "success" (:conclusion run)))
      (throw (ex-info "The latest main push CI run for HEAD must complete successfully"
                      {:commit commit :run run})))))

(defn check-clojars! [version]
  ;; Check both files so that a partial upload also reserves the version.
  (doseq [extension ["pom" "jar"]]
    (let [url (str "https://repo.clojars.org/com/github/loganlinn/clj-codex/"
                   version "/clj-codex-" version "." extension)
          {:keys [status]} (http/head url {:throw false :timeout 10000})]
      (case status
        404 nil
        200 (throw (ex-info "Clojars already contains this version" {:version version :url url}))
        (throw (ex-info "Cannot check Clojars version availability" {:url url :status status}))))))

(defn preflight! []
  (let [{:keys [tag version] :as release} (version/require-release (version/read-version! "."))
        commit (check-source!)
        ref (str "refs/tags/" tag)]
    (check-origin!)
    (when-not (str/blank? (command! "git" "tag" "--list" tag))
      (throw (ex-info "The release tag already exists locally. See RELEASE.md before retrying." {:tag tag})))
    (when-not (str/blank? (command! "git" "ls-remote" "origin" ref (str ref "^{}")))
      (throw (ex-info "The release tag already exists on origin. See RELEASE.md before retrying." {:tag tag})))
    (check-github! tag)
    (check-ci! commit)
    (check-clojars! version)
    (assoc release :commit commit)))

(defn check-release! []
  (tasks/clojure "-T:build" "check-release"))

(defn draft! []
  (let [{:keys [tag version prerelease? commit]} (preflight!)
        ref (str "refs/tags/" tag)]
    (when-not (= commit (check-source!))
      (throw (ex-info "HEAD changed during the release checks" {:commit commit})))
    (when-not (= version (:version (version/read-version! ".")))
      (throw (ex-info "version.edn changed during the release checks" {})))
    (command! "git" "tag" "-a" tag "-m" (str "Release " version) commit)
    (println "Created annotated tag" tag "at" commit)
    (check-release!)
    (command! "git" "-c" "push.followTags=false" "push" "origin" (str ref ":" ref))
    (println "Pushed" ref)
    (let [url (apply command!
                     (cond-> ["gh" "release" "create" tag "--repo" github-repository
                              "--verify-tag" "--draft" "--generate-notes" "--title" tag "--target" commit]
                       prerelease? (conj "--prerelease")))]
      (println "Draft release:" url)
      (println "Review the notes in GitHub, then publish the draft to start the Clojars upload.")
      url)))

(defn -main [& args]
  (when (seq args)
    (throw (ex-info "Usage: bb release:draft (reads version.edn; no arguments)" {})))
  (draft!))
