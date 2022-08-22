(ns clj-http.test.core-test
  (:require [clj-http.lite.core :as core]
            [clj-http.lite.util :as util]
            [clojure.test :refer [deftest is use-fixtures]]
            [clojure.string :as str]
            [ring.adapter.jetty :as ring])
  (:import (org.eclipse.jetty.server Server ServerConnector)
           (java.util Base64)))

(set! *warn-on-reflection* true)

(defn b64-decode [^String s]
  (when s
    (-> (Base64/getDecoder)
        (.decode s)
        util/utf8-string)))

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
    [:get "/redirect"] {:status 302 :headers {"Location" "/get"}}
    [:get "/error"]
    {:status 500 :body "o noes"}
    [:get "/timeout"]
    (do
      (Thread/sleep 10)
      {:status 200 :body "timeout"})
    [:delete "/delete-with-body"]
    {:status 200 :body "delete-with-body"}
    ;; minimal to support testing
    [:get "/basic-auth"]
    (let [cred (some->> (get (:headers req) "authorization")
                        (re-find #"^Basic (.*)$")
                        last
                        b64-decode)
      [user pass] (and cred (str/split cred #":"))]
      (if (and (= "username" user) (= "password" pass))
        {:status 200 :body "welcome"}
        {:status 401 :body "denied"}))))

(defn make-server ^Server []
  (ring/run-jetty handler {:port         0 ;; Use a free port
                           :join?        false
                           :ssl-port     0 ;; Use a free port
                           :ssl?         true
                           :keystore     "test-resources/keystore"
                           :key-password "keykey"}))

(def ^:dynamic *server* nil)

(defn- port-for-protocol [p]
  (let [^Server s *server*]
    (some (fn [^ServerConnector c]
            (when (str/starts-with? (str/lower-case (.getDefaultProtocol c)) p)
              (.getLocalPort c)))
          (.getConnectors s))))

(defn current-port []
  (port-for-protocol "http/"))

(defn current-https-port []
  (port-for-protocol "ssl"))

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
   :server-name "localhost"
   :server-port (current-port)})

(defn request [req]
  (core/request (merge (base-req) req)))

(defn slurp-body [req]
  (slurp (:body req)))

(deftest ^{:integration true} makes-get-request
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

(deftest ^{:integration true} delete-with-body
  (let [resp (request {:request-method :delete :uri "/delete-with-body"
                       :body (.getBytes "foo bar")})]
    (is (= 200 (:status resp)))
    (is (= "delete-with-body" (slurp-body resp)))))

(deftest ^{:integration true} self-signed-ssl-get
  (let [client-opts {:request-method :get
                     :uri "/get"
                     :scheme         :https
                     :server-name "localhost"
                     :server-port (current-https-port)}]
    (is (thrown? javax.net.ssl.SSLException
                 (request client-opts)))
    (let [resp (request (assoc client-opts :insecure? true))]
      (is (= 200 (:status resp)))
      (is (= "get" (slurp-body resp))))
    (is (thrown? javax.net.ssl.SSLException
                 (request client-opts))
        "subsequent bad cert fetch throws")))

(deftest ^{:integration true} t-save-request-obj
  (let [resp (request {:request-method :post :uri "/post"
                       :body           (.getBytes "foo bar" "UTF-8")
                       :save-request?  true})]
    (is (= 200 (:status resp)))
    (is (= {:scheme         :http
            :http-url       (str "http://localhost:" (current-port) "/post")
            :request-method :post
            :uri            "/post"
            :server-name    "localhost"
            :server-port    (current-port)}
           (-> resp
               :request
               (dissoc :body))))))

(deftest ^{:integration true} t-streaming-response
  (let [stream (:body (request {:request-method :get :uri "/get" :as :stream}))
        body (slurp stream)]
    (is (= "get" body))))
