(ns helper.main)

(defmacro when-invoked-as-script
  "Runs `body` when clj was invoked from command line as a script."
  [& body]
  `(when (= *file* (System/getProperty "babashka.file"))
     ~@body))
