(ns tasks
  (:require [babashka.tasks :refer [shell]]
            [build-shared :as shared]
            [clojure.string :as str]))

(defn replace-version [file version cc]
  (prn version cc)
  (spit file
        (str/replace (slurp file)
                     (re-pattern (format "(%s)\\.(\\d+)" version))
                     (fn [[_ version _]]
                       (str version "." cc)))))

(defn deploy []
  (let [;; commit count + 1 for README update
        cc (inc (Integer/parseInt (shared/git-count-revs)))
        tag (str "Release-" shared/version)]
    (replace-version "README.md" shared/base-version cc)
    (replace-version "project.clj" shared/base-version cc)
    (shell "git add README.md project.clj")
    (shell "git commit -m 'Bump version in README'")
    (shell "git push")
    (shell "git tag" tag)
    (shell "git push origin" tag)))
