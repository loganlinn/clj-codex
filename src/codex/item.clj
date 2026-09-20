(ns codex.item "Pure queries over immutable item values.")
(defn text "Return textual content for an item, or nil." [item] (::text item))
(defn messages "Select user and agent message items, preserving order." [items]
  (filterv #(contains? #{:user-message :agent-message} (::type %)) items))
(defn commands "Select command execution items." [items] (filterv #(= :command-execution (::type %)) items))
(defn changes "Select file-change items." [items] (filterv #(= :file-change (::type %)) items))
(defn completed? "Was an item completed, or does it carry a terminal execution status?" [item]
  (or (true? (::completed? item)) (contains? #{:completed :failed :declined} (::status item))))
