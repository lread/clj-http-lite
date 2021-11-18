(ns build
  (:require [build-shared :as shared]
            [clojure.string :as str]
            [clojure.tools.build.api :as b]))

(def lib 'org.clj-commons/clj-http-lite)
(def version (shared/version (shared/git-count-revs)))
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
(def jar-file (format "target/%s-%s.jar" (name lib) version))
(def github-repo "https://github.com/clj-commons/clj-http-lite")

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [opts]
  (let [opts (merge {:class-dir class-dir
                     :lib lib
                     :version version
                     :basis basis
                     :src-dirs ["src"]}
                    opts)
        opts (assoc opts :scm
                    (cond-> {:url "https://github.com/clj-commons/clj-http-lite"}
                      (:tag opts) (assoc :tag (:tag opts))))]
    (b/write-pom opts))
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file jar-file}))

(defn deploy [opts]
  (jar opts)
  ((requiring-resolve 'deps-deploy.deps-deploy/deploy)
   (merge {:installer :remote
           :artifact jar-file
           :pom-file (b/pom-path {:lib lib :class-dir class-dir})}
          opts))
  opts)

(def release-marker "Release-")

(defn extract-version [tag]
  (str/replace-first tag release-marker ""))

(defn ci-tag []
  (when (= "tag" (System/getenv "GITHUB_REF_TYPE"))
    (System/getenv "GITHUB_REF_NAME")))

(defn maybe-deploy [opts]
  (if-let [tag (ci-tag)]
    (do
      (println "Found tag " tag)
      (if (re-find (re-pattern release-marker) tag)
        (do
          (println "Releasing to clojars...")
          (-> opts
              (assoc :lib lib
                     :version (extract-version tag)
                     :tag tag)
              (deploy)))
        (do
          (println "Tag is not a release tag, skipping deploy")
          opts)))
    (do
      (println "No tag found, skipping deploy")
      opts)))
