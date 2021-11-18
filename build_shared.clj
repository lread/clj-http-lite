(ns build-shared
  (:require [clojure.edn :as edn]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]))

(defn git-count-revs []
  (-> (apply sh (str/split "git rev-list HEAD --count" #" "))
      :out
      str/trim
      Integer/parseInt))

(def base-version (edn/read-string (slurp "version.edn")))

(defn version [revs]
  (str base-version "." revs))
