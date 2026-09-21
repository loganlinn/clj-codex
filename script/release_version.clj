(ns release-version
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def version-file "version.edn")

(defn parse-version [version]
  (let [match (when (string? version)
                (re-matches #"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-(alpha|beta|rc)\.(0|[1-9][0-9]*))?(-SNAPSHOT)?" version))]
    (when-not match
      (throw (ex-info "Expected MAJOR.MINOR.PATCH, optionally with -alpha.N, -beta.N, or -rc.N, and an optional -SNAPSHOT suffix"
                      {:version version})))
    (let [release-version (str/replace version #"-SNAPSHOT$" "")]
      {:version version
       :release-version release-version
       :tag (str "v" release-version)
       :snapshot? (some? (nth match 6))
       :prerelease? (some? (nth match 4))
       :components (mapv bigint (subvec match 1 4))})))

(defn parse-document [text]
  (with-open [reader (java.io.PushbackReader. (java.io.StringReader. text))]
    (let [data (edn/read {:eof ::eof} reader)]
      (when-not (and (map? data) (= #{:version} (set (keys data)))
                     (= ::eof (edn/read {:eof ::eof} reader)))
        (throw (ex-info "version.edn must contain one map with only a :version string" {})))
      (parse-version (:version data)))))

(defn read-version! [root]
  (parse-document (slurp (io/file (str root) version-file))))

(defn require-release [version]
  (when (:snapshot? version)
    (throw (ex-info "version.edn still has a snapshot version. Run bb release:prepare, then commit the change."
                    {:version (:version version)})))
  version)

(defn require-snapshot [version]
  (when-not (:snapshot? version)
    (throw (ex-info "version.edn must contain a snapshot version" {:version (:version version)})))
  version)

(defn check-committed! [root committed-text]
  (when-not (= (read-version! root) (parse-document committed-text))
    (throw (ex-info "version.edn does not match the version committed at HEAD" {}))))

(defn- write-version! [root value]
  (let [version (parse-version value)]
    (spit (io/file (str root) version-file) (str (pr-str {:version value}) "\n"))
    version))

(defn prepare! [root]
  (let [{:keys [release-version]} (require-snapshot (read-version! root))]
    (write-version! root release-version)))

(defn bump! [root part]
  (let [{[major minor patch] :components} (read-version! root)
        components (case part
                     "major" [(inc major) 0 0]
                     "minor" [major (inc minor) 0]
                     "patch" [major minor (inc patch)]
                     (throw (ex-info "Expected patch, minor, or major" {:part part})))]
    (write-version! root (str (str/join "." components) "-SNAPSHOT"))))

(defn -main [action & args]
  (when (or (and (#{"show" "prepare"} action) (seq args))
            (and (= "bump" action) (> (count args) 1)))
    (throw (ex-info "Usage: bb version:show, bb release:prepare, or bb version:bump [patch|minor|major]" {})))
  (case action
    "show" (println (:version (read-version! ".")))
    "prepare" (do (println "Prepared" (:version (prepare! ".")) "in version.edn.")
                  (println "Commit the version change and wait for main CI before running bb release:draft."))
    "bump" (println "Set version.edn to" (:version (bump! "." (or (first args) "patch"))))
    (throw (ex-info "Unknown version task" {:action action}))))
