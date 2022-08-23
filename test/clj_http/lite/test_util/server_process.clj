(ns clj-http.lite.test-util.server-process
  (:require [clj-http.lite.test-util.server-state :refer [server-state-file]]
            [clojure.edn :as edn])
  (:import (java.net URL HttpURLConnection)
           (java.lang ProcessBuilder$Redirect)))

(defn- url-reachable? [s]
  (try
    (let [^HttpURLConnection c (.openConnection (URL. s))]
      (.setRequestMethod c "GET")
      (= 200 (.getResponseCode c)))
    (catch Throwable _e)))

(defn kill [{:keys [^Process process http-port]}]
  (when (and process (.isAlive process))
    (println "Stopping http-server")
    (try
      (let [^HttpURLConnection c (.openConnection (URL. (format "http://localhost:%d/stop" http-port)))]
        (.setRequestMethod c "GET")
        (.getResponseCode c))
      (catch Throwable e
        (println "warn: stop command failed\n" (.printStackTrace e))))
    (.waitFor process)))

(defn launch []
  (when (.exists server-state-file)
    (.delete server-state-file))
  (let [max-wait-msecs 120000 ;; Windows GitHub Actions CI can be painfully slow
        status-every-ms 1000
        time-limit (+ (System/currentTimeMillis) max-wait-msecs)
        ;; use bb's clojure launcher for an easy time on Windows
        p (-> (ProcessBuilder. ["bb" "clojure" "-X:http-server"])
              (.redirectOutput ProcessBuilder$Redirect/INHERIT)
              (.redirectError ProcessBuilder$Redirect/INHERIT)
              (.start))]
    (-> (Runtime/getRuntime)
        (.addShutdownHook (Thread. (fn [] (when (.isAlive p)
                                            (println "killing http-server forcibly")
                                            (.destroyForcibly p)
                                            (.waitFor p))))))
    (print "Starting http-server")
    (flush)
    (loop [next-status (System/currentTimeMillis)
           server-state nil]
      (cond
        (not (.isAlive p))
        (throw (ex-info "Http-server process died unexpectedly" {}))
        (> (System/currentTimeMillis) time-limit)
        (do (when server-state
              (kill server-state))
            (throw (ex-info "Timed out waiting for test http-server to start" {})))
        (and server-state (url-reachable? (format "http://localhost:%d/get"
                                                  (:http-port server-state))))
        (do (println)
            server-state)
        (and (not server-state) (.exists server-state-file))
        (recur next-status
               (-> server-state-file slurp edn/read-string (assoc :process p)))
        :else
        (let [next-status (if (> (System/currentTimeMillis) next-status)
                            (do (print ".")
                                (flush)
                                (+ (System/currentTimeMillis) status-every-ms))
                            next-status)]
          (Thread/sleep 50)
          (recur next-status server-state))))))
