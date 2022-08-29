(ns tasks
  (:require [babashka.tasks :refer [shell clojure]]
            [clojure.edn :as edn]))

(defn deps []
  (let [aliases (->> "deps.edn"
                     slurp
                     edn/read-string
                     :aliases
                     keys)]
    ;; one at a time because aliases with :replace-deps or override-deps will... well... you know.
    (println "Bring down default deps")
    (clojure "-P")
    (doseq [a aliases]
      (println "Bring down deps for alias" a)
      (clojure "-P" (str "-M" a)))))

