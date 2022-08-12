(ns tasks
  (:require [babashka.tasks :refer [shell clojure]]
            [build-shared :as shared]
            [clojure.edn :as edn]
            [clojure.string :as str]))

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

(defn replace-version [file version cc]
  (spit file
        (str/replace (slurp file)
                     (re-pattern (format "(%s)\\.(\\d+)" version))
                     (fn [[_ version _]]
                       (str version "." cc)))))

(defn publish []
  (let [;; commit count + 1 for README update
        cc (inc (shared/git-count-revs))
        tag (str "Release-" (shared/version cc))]
    (replace-version "README.md" shared/base-version cc)
    (replace-version "project.clj" shared/base-version cc)
    (shell "git add README.md project.clj")
    (shell "git commit -m 'Bump version in README'")
    (shell "git push")
    (shell "git tag" tag)
    (shell "git push origin" tag)))
