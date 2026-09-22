(ns codex.schema
  "Inspect and check the bundled protocol schemas. Checks accept wire JSON values."
  (:require [codex.impl.schema :as s]))

(defn schemas
  "Return descriptors for exported schemas and nested value definitions."
  []
  (merge (into (sorted-map) (map (fn [[name path]] [name {:resource path :definition name}]) @s/definitions))
         (into {} (map (fn [[name path]] [name {:resource path}]) @s/index))))

(defn describe
  "Return a JSON Schema document (string keys)."
  [id]
  (let [[root schema] (s/lookup id)
        definitions (get root "definitions")]
    (-> root
        (s/resolve-ref schema)
        (assoc "definitions" definitions))))

(defn explain
  "Return structural schema errors, or an empty vector."
  [id value]
  (let [[root schema] (s/lookup id)]
    (s/errors root schema value)))

(defn valid?
  "Does a wire JSON value satisfy the schema checks?"
  [id value]
  (-> id (explain value) empty?))
