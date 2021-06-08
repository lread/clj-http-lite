(ns clj-http.test.core
  (:require [clj-http.lite.core :as core]
            [clj-http.lite.util :as util]
            [clojure.pprint :as pp]
            [clojure.java.io :refer [file]]
            [clojure.test :refer [deftest is use-fixtures]]
            [ring.adapter.jetty :as ring])
  (:import (java.io ByteArrayInputStream)
           (org.eclipse.jetty.server Server)
           (org.eclipse.jetty.server.nio SelectChannelConnector)
           (org.eclipse.jetty.server.ssl SslSelectChannelConnector)))

(defn handler [req]
  (condp = [(:request-method req) (:uri req)]
    [:get "/get"]
    {:status 200 :body "get"}
    [:head "/head"]
    {:status 200}
    [:get "/content-type"]
    {:status 200 :body (:content-type req)}
    [:get "/header"]
    {:status 200 :body (get-in req [:headers "x-my-header"])}
    [:post "/post"]
    {:status 200 :body (slurp (:body req))}
    [:get "/redirect"] {:status 302 :headers {"Location" "/get"} }
    [:get "/error"]
    {:status 500 :body "o noes"}
    [:get "/timeout"]
    (do
      (Thread/sleep 10)
      {:status 200 :body "timeout"})
    [:delete "/delete-with-body"]
    {:status 200 :body "delete-with-body"}
    [:post "/multipart"]
    {:status 200 :body (:body req)}))

(defn make-server ^Server []
  (ring/run-jetty handler {:port         0 ;; Use a free port
                           :join?        false
                           :ssl-port     0 ;; Use a free port
                           :ssl?         true
                           :keystore     "test-resources/keystore"
                           :key-password "keykey"}))

(def ^:dynamic *server* nil)

(defn current-port []
  (let [^Server s *server*]
    (->> s
         .getConnectors
         (filter (comp #{SelectChannelConnector} class))
         ^SelectChannelConnector (first)
         .getLocalPort)))

(defn current-https-port []
  (let [^Server s *server*]
    (->> s
         .getConnectors
         (filter (comp #{SslSelectChannelConnector} class))
         ^SslSelectChannelConnector (first)
         .getLocalPort)))

(defn with-server [t]
  (let [s (make-server)]
    (try
      (binding [*server* s]
        (t))
      (finally
        (-> s .stop)))))

(use-fixtures :each with-server)

(defn base-req []
  {:scheme      :http
   :server-name (str "localhost:" (current-port))
   :port        (current-port)})

(defn request [req]
  (core/request (merge (base-req) req)))

(defn slurp-body [req]
  (slurp (:body req)))

(deftest ^{:integration true} makes-get-request
  (current-port)
  (let [resp (request {:request-method :get :uri "/get"})]
    (is (= 200 (:status resp)))
    (is (= "get" (slurp-body resp)))))

(deftest ^{:integration true} makes-head-request
  (let [resp (request {:request-method :head :uri "/head"})]
    (is (= 200 (:status resp)))
    (is (nil? (:body resp)))))

(deftest ^{:integration true} sets-content-type-with-charset
  (let [resp (request {:request-method :get         :uri                "/content-type"
                       :content-type   "text/plain" :character-encoding "UTF-8"})]
    (is (= "text/plain; charset=UTF-8" (slurp-body resp)))))

(deftest ^{:integration true} sets-content-type-without-charset
  (let [resp (request {:request-method :get :uri "/content-type"
                       :content-type   "text/plain"})]
    (is (= "text/plain" (slurp-body resp)))))

(deftest ^{:integration true} sets-arbitrary-headers
  (let [resp (request {:request-method :get :uri "/header"
                       :headers        {"X-My-Header" "header-val"}})]
    (is (= "header-val" (slurp-body resp)))))

(deftest ^{:integration true} sends-and-returns-byte-array-body
  (let [resp (request {:request-method :post :uri "/post"
                       :body           (util/utf8-bytes "contents")})]
    (is (= 200 (:status resp)))
    (is (= "contents" (slurp-body resp)))))

(deftest ^{:integration true} returns-arbitrary-headers
  (let [resp (request {:request-method :get :uri "/get"})]
    (is (string? (get-in resp [:headers "date"])))))

(deftest ^{:integration true} returns-status-on-exceptional-responses
  (let [resp (request {:request-method :get :uri "/error"})]
    (is (= 500 (:status resp)))))

(deftest ^{:integration true} returns-status-on-redirect
  (let [resp (request {:request-method :get :uri "/redirect" :follow-redirects false})]
    (is (= 302 (:status resp)))))

(deftest ^{:integration true} auto-follows-on-redirect
  (let [resp (request {:request-method :get :uri "/redirect"})]
    (is (= 200 (:status resp)))
    (is (= "get" (slurp-body resp)))))

(deftest ^{:integration true} sets-conn-timeout
  ;; indirect way of testing if a connection timeout will fail by passing in an
  ;; invalid argument
  (try
    (request {:request-method :get :uri "/timeout" :conn-timeout -1})
    (throw (Exception. "Shouldn't get here."))
    (catch Exception e
      (is (= IllegalArgumentException (class e))))))

(deftest ^{:integration true} sets-socket-timeout
  (try
    (request {:request-method :get :uri "/timeout" :socket-timeout 1})
    (throw (Exception. "Shouldn't get here."))
    (catch Exception e
      (is (or (= java.net.SocketTimeoutException (class e))
              (= java.net.SocketTimeoutException (class (.getCause e))))))))

;; HUC can't do this
;; (deftest ^{:integration true} delete-with-body
;;   (run-server)
;;   (let [resp (request {:request-method :delete :uri "/delete-with-body"
;;                        :body (.getBytes "foo bar")})]
;;     (is (= 200 (:status resp)))))

(deftest ^{:integration true} self-signed-ssl-get
  (let [client-opts {:request-method :get
                     :uri "/get"
                     :scheme         :https
                     :server-name (str "localhost:" (current-https-port))
                     :port        (current-https-port)}]
    (is (thrown? javax.net.ssl.SSLException
                 (request client-opts)))
    (let [resp (request (assoc client-opts :insecure? true))]
      (is (= 200 (:status resp)))
      (is (= "get" (slurp-body resp))))))

;; (deftest ^{:integration true} multipart-form-uploads
;;   (run-server)
;;   (let [bytes (util/utf8-bytes "byte-test")
;;         stream (ByteArrayInputStream. bytes)
;;         resp (request {:request-method :post :uri "/multipart"
;;                        :multipart [["a" "testFINDMEtest"]
;;                                    ["b" bytes]
;;                                    ["c" stream]
;;                                    ["d" (file "test-resources/keystore")]]})
;;         resp-body (apply str (map #(try (char %) (catch Exception _ ""))
;;                                   (:body resp)))]
;;     (is (= 200 (:status resp)))
;;     (is (re-find #"testFINDMEtest" resp-body))
;;     (is (re-find #"byte-test" resp-body))
;;     (is (re-find #"name=\"c\"" resp-body))
;;     (is (re-find #"name=\"d\"" resp-body))))

(deftest ^{:integration true} t-save-request-obj
  (let [resp (request {:request-method :post :uri "/post"
                       :body           (.getBytes "foo bar")
                       :save-request?  true})]
    (is (= 200 (:status resp)))
    (is (= {:scheme         :http
            :http-url       (str "http://localhost:" (current-port) "/post")
            :request-method :post
            :uri            "/post"
            :server-name    (str "localhost:" (current-port))
            :port           (current-port)}
           (-> resp
               :request
               (dissoc :body))))))

;; (deftest parse-headers
;;   (are [headers expected]
;;        (let [iterator (BasicHeaderIterator.
;;                        (into-array BasicHeader
;;                                    (map (fn [[name value]]
;;                                           (BasicHeader. name value))
;;                                         headers))
;;                        nil)]
;;          (is (= (core/parse-headers iterator)
;;                 expected)))

;;        []
;;        {}

;;        [["Set-Cookie" "one"]]
;;        {"set-cookie" "one"}

;;        [["Set-Cookie" "one"]
;;         ["set-COOKIE" "two"]]
;;        {"set-cookie" ["one" "two"]}

;;        [["Set-Cookie" "one"]
;;         ["serVer"     "some-server"]
;;         ["set-cookie" "two"]]
;;        {"set-cookie" ["one" "two"]
;;         "server"     "some-server"}))

(deftest ^{:integration true} t-streaming-response
  (let [stream (:body (request {:request-method :get :uri "/get" :as :stream}))
        body (slurp stream)]
    (is (= "get" body))))
