(ns clj-http-lite.impl
  (:require
   [clojure.walk :as walk]))

(defmacro copy
  "`def`ines a value based on a var from another namespace,
  keeping its metadata (especially `:docstring`, `:arglists`)
  while discarding metadata that tends to be unique per ns.

  Similar to Potemkin's `import-vars` but leaner, and more correct when it comes to metadata."
  [x]
  {:pre [(and (symbol? x)
              (namespace x))]}
  (let [ref (-> x resolve (doto assert))
        m (walk/postwalk (fn [x]
                           (if (seq? x)
                             (list 'quote x)
                             x))
                         (-> ref meta (dissoc :file :line :column :ns)))
        s (-> x
              name
              symbol
              (with-meta m))]
    `(def ~s
       (deref ~ref))))
