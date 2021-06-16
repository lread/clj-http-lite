(ns setup
  "This namespace will be automaticaly loaded by the test runner"
  (:require
   [clojure.string :as string])
  (:import
   (org.eclipse.jetty.util MultiException)))

(-> (reify Thread$UncaughtExceptionHandler
      (uncaughtException [_ thread e]
        ;; Omit exceptions coming from "Address already in use" because they're meaningless
        ;; (these happen when one picks port 0, and after one such exception a new port will be retried successfully)
        (let [omit? (or (-> ^Throwable e .getMessage #{"Address already in use"})
                        (and (instance? MultiException e)
                             (->> ^MultiException e
                                  .getThrowables
                                  (every? (fn [^Throwable t]
                                            (-> t .getMessage (.contains "Address already in use")))))))]
          (when-not omit?
            (-> ^Throwable e .printStackTrace)
            (when (System/getenv "CI")
              (System/exit 1))))))

    (Thread/setDefaultUncaughtExceptionHandler))
