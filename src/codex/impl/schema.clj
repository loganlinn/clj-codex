(ns codex.impl.schema
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [codex.impl.util :as u]))

(def index (delay (edn/read-string (slurp (io/resource "codex/schema-index.edn")))))
(def definitions (delay (edn/read-string (slurp (io/resource "codex/definition-index.edn")))))
(def catalog (delay (edn/read-string (slurp (io/resource "codex/catalog.edn")))))
(def document (memoize (fn [path]
                         (if-let [r (io/resource path)]
                           (json/parse-string (slurp r))
                           (throw (u/error :schema "Schema resource not found" {:resource path}))))))
(def stable (delay (document "codex/stable-client-request.json")))

(def entity-ns
  {"Thread" "codex.thread" "Turn" "codex.turn" "ThreadItem" "codex.item"
   "ThreadGoal" "codex.goal" "Model" "codex.model" "AppInfo" "codex.app"
   "SkillMetadata" "codex.skill"})

(defn resolve-ref [root s]
  (if-let [r (get s "$ref")]
    (or (get-in root (map #(-> % (str/replace "~1" "/") (str/replace "~0" "~"))
                          (rest (str/split r #"/"))))
        (throw (u/error :schema "Unresolved schema reference" {:ref r})))
    s))

(defn lookup [id]
  (let [n (if (string? id) id (name id))
        alias {"thread" "Thread" "turn" "Turn" "item" "ThreadItem"
               "input" "UserInput" "goal" "ThreadGoal" "model" "Model"}
        n (get alias n n)]
    (if-let [path (get @index n)]
      (let [s (document path)] [s s])
      (if-let [path (get @definitions n)]
        [(document path) {"$ref" (str "#/definitions/" n)}]
        (throw (u/error :schema "Unknown schema" {:schema id}))))))

(defn type-matches? [t x]
  (case t
    "null" (nil? x) "object" (map? x) "array" (sequential? x)
    "string" (string? x) "integer" (integer? x) "number" (number? x)
    "boolean" (boolean? x) true))

(declare errors)
(defn errors
  "Check the structural JSON Schema vocabulary used by the bundled protocol."
  ([root s x] (errors root s x [] false))
  ([root s x path closed?]
   (let [s (resolve-ref root s)
         branches (or (get s "oneOf") (get s "anyOf"))
         issue (fn [reason] [{:path path :reason reason}])
         t (get s "type")]
     (cond
       (true? s) []
       (false? s) (issue :forbidden)
       branches (let [matches (count (filter empty? (map #(errors root % x path closed?) branches)))]
                  (if (if (get s "oneOf") (= 1 matches) (pos? matches)) [] (issue :union)))
       (get s "allOf") (vec (mapcat #(errors root % x path closed?) (get s "allOf")))
       (and t (not (some #(type-matches? % x) (if (string? t) [t] t)))) (issue :type)
       (and (contains? s "enum") (not (some #(= x %) (get s "enum")))) (issue :enum)
       (and (contains? s "const") (not= x (get s "const"))) (issue :const)
       (map? x)
       (let [props (get s "properties") additional (get s "additionalProperties")]
         (vec
          (concat
           (for [k (get s "required") :when (not (contains? x k))]
             {:path (conj path k) :reason :required})
           (mapcat (fn [[k v]]
                     (cond
                       (contains? props k) (errors root (get props k) v (conj path k) closed?)
                       (map? additional) (errors root additional v (conj path k) closed?)
                       (or (false? additional) (and closed? (seq props)))
                       [{:path (conj path k) :reason :unknown-field}]
                       :else [])) x))))
       (sequential? x)
       (vec (concat (when (and (get s "minItems") (< (count x) (get s "minItems"))) (issue :min-items))
                    (when (and (get s "maxItems") (> (count x) (get s "maxItems"))) (issue :max-items))
                    (mapcat (fn [[i v]] (errors root (get s "items" {}) v (conj path i) closed?))
                            (map-indexed vector x))))
       (number? x)
       (vec (concat (when (and (get s "minimum") (< x (get s "minimum"))) (issue :minimum))
                    (when (and (get s "maximum") (> x (get s "maximum"))) (issue :maximum))))
       (string? x)
       (vec (concat (when (and (get s "minLength") (< (count x) (get s "minLength"))) (issue :min-length))
                    (when (and (get s "maxLength") (> (count x) (get s "maxLength"))) (issue :max-length))
                    (when (and (get s "pattern") (not (re-find (re-pattern (get s "pattern")) x)))
                      (issue :pattern))))
       :else []))))

(defn check! [root s x]
  (when-let [issues (seq (errors root s x))]
    (throw (u/error :schema "Value does not match the protocol schema" {:issues (vec issues)})))
  x)

(declare transform)
(defn- transform-union [direction root branches x owner]
  (let [tagged (when (and (= direction :decode) (map? x))
                 (some (fn [branch]
                         (let [s (resolve-ref root branch)]
                           (when (some (fn [[k prop]]
                                         (let [values (get (resolve-ref root prop) "enum")]
                                           (and (= 1 (count values)) (contains? x k) (= (get x k) (first values)))))
                                       (get s "properties"))
                             branch))) branches))
        attempts (keep (fn [branch]
                         (try
                           (let [v (transform direction root branch x owner)
                                 wire (if (= direction :encode) v x)]
                             (when (empty? (errors root branch wire)) [v]))
                           (catch clojure.lang.ExceptionInfo _ nil))) branches)]
    (if tagged
      (transform direction root tagged x owner)
      (if-let [v (first attempts)]
        (first v)
        (if (= direction :decode)
          (let [compatible (filter (fn [branch]
                                     (let [s (resolve-ref root branch) t (get s "type")]
                                       (and t (type-matches? t x)))) branches)]
            (if (= 1 (count compatible))
              (transform direction root (first compatible) x owner)
              {:type :codex.api/unknown :codex.api/raw x}))
          (throw (u/error :schema "Value does not match any union variant" {})))))))

(defn transform [direction root s x owner]
  (let [ref (some-> (get s "$ref") (str/split #"/") last)
        owner (or (get entity-ns ref) owner)
        s (resolve-ref root s)
        branches (or (get s "oneOf") (get s "anyOf"))]
    (cond
      (and (= direction :encode) (map? x) (= :codex.api/unknown (:type x))) (:codex.api/raw x)
      branches (transform-union direction root branches x owner)
      (get s "allOf") (reduce (fn [v b] (transform direction root b v owner)) x (get s "allOf"))
      (get s "enum")
      (if (= direction :encode)
        (if (keyword? x)
          (or (some #(when (and (string? %) (= (keyword (u/kebab %)) x)) %) (get s "enum")) x)
          x)
        (if (and (string? x) (some #{x} (get s "enum"))) (keyword (u/kebab x)) x))
      (and (map? x) (seq (get s "properties")))
      (let [props (get s "properties")
            key-map (into {} (for [wire (keys props)] [(keyword owner (u/kebab wire)) wire]))]
        (if (= direction :encode)
          (let [extensions (:codex.api/extensions x)]
            (when (some #(or (not (string? %)) (contains? props %)) (keys extensions))
              (throw (u/error :schema "Extension keys must be unknown wire names" {})))
            (reduce-kv
             (fn [m k v]
               (if (= k :codex.api/extensions) m
                   (if-let [wire (get key-map k)]
                     (assoc m wire (transform direction root (get props wire) v nil))
                     (throw (u/error :schema "Unknown argument" {:key k :allowed (set (keys key-map))})))))
             (or extensions {}) x))
          (reduce-kv
           (fn [m k v]
             (if-let [prop (get props k)]
               (let [value (transform direction root prop v nil)]
                 (assoc m (keyword owner (u/kebab k))
                        (if (and (= k "activeFlags") (sequential? value)) (set value) value)))
               (assoc-in m [:codex.api/extensions k] v))) {} x)))
      (and (map? x) (map? (get s "additionalProperties")))
      (into {} (map (fn [[k v]]
                      (when-not (string? k)
                        (throw (u/error :schema "User-owned map keys must be strings" {:key k})))
                      [k (transform direction root (get s "additionalProperties") v nil)]) x))
      (and (or (sequential? x) (set? x)) (get s "items"))
      (mapv #(transform direction root (get s "items") % nil) x)
      :else x)))

(defn encode [root s x]
  (check! root s (transform :encode root s x nil)))
(defn decode [root s x] (transform :decode root s x nil))
(defn decode-type [id x]
  (let [[root s] (lookup id)] (decode root s x)))
