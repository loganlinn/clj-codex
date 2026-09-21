(ns release-version)

(defn parse-tag [tag]
  (let [match (when (string? tag)
                (re-matches #"v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-(alpha|beta|rc)\.(0|[1-9][0-9]*))?" tag))]
    (when-not match
      (throw (ex-info "Expected vMAJOR.MINOR.PATCH, optionally with -alpha.N, -beta.N, or -rc.N" {:tag tag})))
    {:tag tag :version (subs tag 1) :prerelease? (some? (nth match 4))}))
