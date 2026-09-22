(ns codex.input
  "Pure constructors for user input. Values can also be written as maps."
  (:require [codex.impl.util :as u]))

(defn text
  "Text input."
  [s]
  {:type :text :text s})

(defn image
  "Image URL input."
  [url]
  {:type :image :url url})

(defn local-image
  "Image path on the server host."
  [path]
  {:type :local-image :path path})

(defn audio
  "Audio URL input."
  [url]
  {:type :audio :url url})

(defn local-audio
  "Audio path on the server host."
  [path]
  {:type :local-audio :path path})

(defn skill
  "Explicit skill reference. Include its $name in the accompanying text."
  [name path]
  {:type :skill :name name :path path})

(defn mention
  "App or other mention reference."
  [name path]
  {:type :mention :name name :path path})

(defn normalize
  "Normalize a string, input map, or ordered collection to a vector."
  [x]
  (cond (string? x) [(text x)] (map? x) [x]
        (and (sequential? x) (every? map? x)) (vec x)
        :else (throw (u/error :argument "Input must be a string, input map, or sequence of input maps" {}))))
