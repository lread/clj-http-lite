(ns tasks
  (:require [clojure.string :as str]))

(defn replace-version [file version cc]
  (spit file
        (str/replace (slurp file)
                     (re-pattern (format "(%s)\\.(\\d+)" version))
                     (fn [[_ version _]]
                       (str version "." cc)))))
