(ns build
  (:require [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.tools.build.api :as b]
            [schema-bundle :as bundle]))

(def lib 'com.github.loganlinn/clj-codex)
(def repository "https://github.com/loganlinn/clj-codex")
(def class-dir "target/classes")

(defn- env [key]
  (not-empty (System/getenv key)))

(defn- release-options [{:keys [tag version]}]
  (when version
    (throw (ex-info "Tagged releases require :tag, not :version" {})))
  (let [tag (or tag (env "RELEASE_TAG"))]
    (when-not (and (string? tag)
                   (re-matches #"v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-(alpha|beta|rc)\.(0|[1-9][0-9]*))?" tag))
      (throw (ex-info "Expected vMAJOR.MINOR.PATCH, optionally with -alpha.N, -beta.N, or -rc.N" {:tag tag})))
    {:tag tag
     :version (subs tag 1)
     :jar-file (str "target/clj-codex-" (subs tag 1) ".jar")}))

(defn- snapshot-options [{:keys [version tag]}]
  (when tag
    (throw (ex-info "Snapshots require :version, not :tag" {})))
  (let [version (or version (env "SNAPSHOT_VERSION"))]
    (when-not (and (string? version)
                   (re-matches #"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)-SNAPSHOT" version))
      (throw (ex-info "Expected MAJOR.MINOR.PATCH-SNAPSHOT, for example 0.1.0-SNAPSHOT" {:version version})))
    {:version version :jar-file (str "target/clj-codex-" version ".jar")}))

(defn- git [& args]
  (let [{:keys [exit out err]} (apply sh/sh "git" args)]
    (when-not (zero? exit)
      (throw (ex-info "Git publication check failed" {:args args :error err})))
    (str/trim out)))

(defn- check-source []
  (let [commit (git "rev-parse" "HEAD")]
    (when-not (str/blank? (git "status" "--porcelain" "--untracked-files=normal"))
      (throw (ex-info "Publication requires a clean checkout, including untracked files" {})))
    (when-let [expected (env "GITHUB_SHA")]
      (when-not (= expected commit)
        (throw (ex-info "HEAD does not match the GitHub event" {:expected expected :head commit}))))
    commit))

(defn check-release
  "Require an existing release tag at HEAD and a clean checkout."
  [opts]
  (let [{:keys [tag] :as release} (release-options opts)
        commit (check-source)]
    (when-not (= commit (git "rev-parse" "--verify" (str "refs/tags/" tag "^{commit}")))
      (throw (ex-info "Release tag does not point to HEAD" {:tag tag :head commit})))
    (assoc release :commit commit)))

(defn check-snapshot
  "Require a snapshot version and a clean checkout, without requiring a tag."
  [opts]
  (let [snapshot (snapshot-options opts)
        commit (check-source)]
    (when (and (env "GITHUB_ACTIONS") (not= "refs/heads/main" (env "GITHUB_REF")))
      (throw (ex-info "GitHub snapshot publication must run from main" {})))
    (assoc snapshot :commit commit)))

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar
  "Build a release tag or snapshot version. A tag need not exist for a local preview."
  [opts]
  (when (and (:tag opts) (:version opts))
    (throw (ex-info "Specify a release tag or snapshot version, not both" {})))
  (let [{:keys [tag version jar-file] :as release}
        (cond
          (:tag opts) (release-options opts)
          (:version opts) (snapshot-options opts)
          (and (env "RELEASE_TAG") (env "SNAPSHOT_VERSION"))
          (throw (ex-info "Set RELEASE_TAG or SNAPSHOT_VERSION, not both" {}))
          (env "SNAPSHOT_VERSION") (snapshot-options opts)
          :else (release-options opts))
        basis (b/create-basis {:project "deps.edn"})]
    (bundle/check! ".")
    (clean nil)
    (b/write-pom {:class-dir class-dir
                  :lib lib
                  :version version
                  :basis basis
                  :src-pom :none
                  :src-dirs ["src"]
                  :resource-dirs ["resources" "apis"]
                  :scm {:url repository
                        :connection (str "scm:git:" repository ".git")
                        :developerConnection "scm:git:ssh://git@github.com/loganlinn/clj-codex.git"
                        :tag (or tag (git "rev-parse" "HEAD"))}
                  :pom-data [[:description "A data-oriented Codex app-server SDK for Clojure and Babashka"]
                             [:url repository]
                             [:licenses
                              [:license
                               [:name "MIT License"]
                               [:url "https://opensource.org/license/mit/"]
                               [:distribution "repo"]]]]})
    (b/copy-dir {:src-dirs ["src" "resources" "apis"] :target-dir class-dir})
    (b/copy-file {:src "LICENSE" :target (str class-dir "/META-INF/LICENSE")})
    (b/jar {:class-dir class-dir :jar-file jar-file})
    (println "Built" jar-file)
    (assoc release :basis basis :pom-file (b/pom-path {:lib lib :class-dir class-dir}))))

(defn install [opts]
  (let [{:keys [basis version jar-file]} (jar opts)]
    (b/install {:basis basis :lib lib :version version
                :jar-file jar-file :class-dir class-dir})))

(defn- deploy! [opts]
  (doseq [credential ["CLOJARS_USERNAME" "CLOJARS_PASSWORD"]]
    (when (str/blank? (env credential))
      (throw (ex-info (str "Missing " credential) {}))))
  (let [{:keys [jar-file pom-file]} (jar opts)]
    ((requiring-resolve 'deps-deploy.deps-deploy/deploy)
     {:installer :remote
      :artifact jar-file
      :pom-file pom-file
      :sign-releases? false})))

(defn publish
  "Check the release, build once, and upload to Clojars."
  [opts]
  (let [{:keys [tag]} (check-release opts)]
    (deploy! {:tag tag})))

(defn publish-snapshot
  "Check the snapshot, build once, and upload to Clojars without a Git tag."
  [opts]
  (let [{:keys [version]} (check-snapshot opts)]
    (deploy! {:version version})))

(defn test-build [_]
  (load-file "script/build_test.clj")
  (let [{:keys [fail error]} ((requiring-resolve 'clojure.test/run-tests) 'build-test)]
    (when (pos? (+ fail error))
      (throw (ex-info "Build tests failed" {:fail fail :error error})))))
