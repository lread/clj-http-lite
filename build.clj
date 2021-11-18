(ns build
  (:require [clojure.string :as str]
            [clojure.tools.build.api :as b]))

(def lib 'org.clj-commons/clj-http-lite)
(def version (format "0.4.%s" (b/git-count-revs nil)))
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
(def uber-file (format "target/%s-%s-standalone.jar" (name lib) version))
(def jar-file (format "target/%s-%s.jar" (name lib) version))

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [_]
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis basis
                :src-dirs ["src"]})
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file jar-file}))

(def release-marker "Release-")

(defn extract-version [tag]
  (str/replace-first tag release-marker ""))

(defn deploy [opts]
  (jar opts)
  ((requiring-resolve 'deps-deploy.deps-deploy/deploy)
   (merge {:installer :remote
           :artifact jar-file
           :pom-file (b/pom-path {:lib lib :class-dir class-dir})}
          opts))
  opts)

(defn maybe-deploy [opts]
  (if-let [tag (System/getenv "CIRCLE_TAG")]
    (do
      (println "Found tag " tag)
      (if (re-find (re-pattern release-marker) tag)
        (do
          (println "Releasing to clojars...")
          (-> opts
              (assoc :lib lib :version (extract-version tag))
              (deploy)))
        (do
          (println "Tag is not a release tag, skipping deploy")
          opts)))
    (do
      (println "No tag found, skipping deploy")
      opts)))
