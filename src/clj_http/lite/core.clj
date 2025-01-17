(ns clj-http.lite.core
  "Core HTTP request/response implementation."
  (:require [clojure.java.io :as io])
  (:import (java.io ByteArrayOutputStream InputStream)
           (java.net URL HttpURLConnection)
           (java.security SecureRandom)))

(set! *warn-on-reflection* true)

(defn parse-headers
  "Takes a URLConnection and returns a map of names to values.

   If a name appears more than once (like `set-cookie`) then the value
   will be a vector containing the values in the order they appeared
   in the headers."
  [conn]
  (loop [i 1 headers {}]
    (let [k (.getHeaderFieldKey ^HttpURLConnection conn i)
          v (.getHeaderField ^HttpURLConnection conn i)]
      (if k
        (recur (inc i) (update-in headers [k] conj v))
        (zipmap (for [k (keys headers)]
                  (.toLowerCase ^String k))
                (for [v (vals headers)]
                  (if (= 1 (count v))
                    (first v)
                    (vec v))))))))

(defn- coerce-body-entity
  "Coerce the http-entity from an HttpResponse to either a byte-array, or a
  stream that closes itself and the connection manager when closed."
  [{:keys [as]} conn]
  (let [ins (try
              (.getInputStream ^HttpURLConnection conn)
              (catch Exception _e
                (.getErrorStream ^HttpURLConnection conn)))]
    (if (or (= :stream as) (nil? ins))
      ins
      (with-open [ins ^InputStream ins
                  baos (ByteArrayOutputStream.)]
        (io/copy ins baos)
        (.flush baos)
        (.toByteArray baos)))))

(def ^:private insecure-mode
  (delay (throw (ex-info "insecure? option not supported in this environment"
                         {}))))

(defmacro ^:private def-insecure []
  (when (try (import '[javax.net.ssl
                       HttpsURLConnection SSLContext TrustManager X509TrustManager HostnameVerifier SSLSession])
             (catch Exception _))
    '(do
       (defn- my-host-verifier []
         (proxy [HostnameVerifier] []
           (verify [^String hostname ^javax.net.ssl.SSLSession session] true)))

       (defn trust-invalid-manager
         "This allows the ssl socket to connect with invalid/self-signed SSL certs."
         []
         (reify javax.net.ssl.X509TrustManager
           (getAcceptedIssuers [this] nil)
           (checkClientTrusted [this certs authType])
           (checkServerTrusted [this certs authType])))

       (def ^:private insecure-mode
         (delay
           (HttpsURLConnection/setDefaultSSLSocketFactory
            (.getSocketFactory
             (doto (SSLContext/getInstance "SSL")
               (.init nil (into-array TrustManager [(trust-invalid-manager)])
                      (new java.security.SecureRandom)))))
           (HttpsURLConnection/setDefaultHostnameVerifier (my-host-verifier)))))))

(def-insecure)

(defn request
  "Executes the HTTP request corresponding to the given Ring request map and
   returns the Ring response map corresponding to the resulting HTTP response.

   Note that where Ring uses InputStreams for the request and response bodies,
   the clj-http uses ByteArrays for the bodies."
  [{:keys [request-method scheme server-name server-port uri query-string
           headers content-type character-encoding body socket-timeout
           conn-timeout debug insecure? save-request? follow-redirects
           chunk-size] :as req}]
  (let [http-url (str (name scheme) "://" server-name
                      (when server-port (str ":" server-port))
                      uri
                      (when query-string (str "?" query-string)))
        _ (when insecure?
            @insecure-mode)
        ^HttpURLConnection conn (.openConnection (URL. http-url))]
    (when (and content-type character-encoding)
      (.setRequestProperty conn "Content-Type" (str content-type
                                                    "; charset="
                                                    character-encoding)))
    (when (and content-type (not character-encoding))
      (.setRequestProperty conn "Content-Type" content-type))
    (doseq [[h v] headers]
      (.setRequestProperty conn h v))
    (when (false? follow-redirects)
      (.setInstanceFollowRedirects conn false))
    (.setRequestMethod conn (.toUpperCase (name request-method)))
    (when body
      (.setDoOutput conn true))
    (when socket-timeout
      (.setReadTimeout conn socket-timeout))
    (when conn-timeout
      (.setConnectTimeout conn conn-timeout))
    (when chunk-size
      (.setChunkedStreamingMode conn chunk-size))
    (.connect conn)
    (when body
      (with-open [out (.getOutputStream conn)]
        (io/copy body out)))
    (merge {:headers (parse-headers conn)
            :status (.getResponseCode conn)
            :body (when-not (= request-method :head)
                    (coerce-body-entity req conn))}
           (when save-request?
             {:request (assoc (dissoc req :save-request?)
                         :http-url http-url)}))))
