(ns tasks
  (:require [babashka.tasks :refer [shell]]
            [clojure.string :as str]))

(defn replace-version [file version cc]
  (prn version cc)
  (spit file
        (str/replace (slurp file)
                     (re-pattern (format "(%s)\\.(\\d+)" version))
                     (fn [[_ version _]]
                       (str version "." cc)))))

(defn git-count-revs []
  (-> (shell {:out :string} "git rev-list HEAD --count")
      :out
      str/trim))
