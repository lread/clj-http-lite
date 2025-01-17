(ns clj-http.lite.client
  "Batteries-included HTTP client."
  (:require [clj-http.lite.core :as core]
            [clj-http.lite.links :refer [wrap-links]]
            [clj-http.lite.util :as util]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.net UnknownHostException))
  (:refer-clojure :exclude (get update)))

(set! *warn-on-reflection* true)

(defn update [m k f & args]
  (assoc m k (apply f (m k) args)))

(defn when-pos [v]
  (when (and v (pos? v)) v))

(defn parse-url [url]
  (let [url-parsed (io/as-url url)]
    {:scheme (keyword (.getProtocol url-parsed))
     :server-name (.getHost url-parsed)
     :server-port (when-pos (.getPort url-parsed))
     :uri (.getPath url-parsed)
     :user-info (.getUserInfo url-parsed)
     :query-string (.getQuery url-parsed)}))

(def unexceptional-status?
  #{200 201 202 203 204 205 206 207 300 301 302 303 307})

(defn wrap-exceptions [client]
  (fn [req]
    (let [{:keys [status] :as resp} (client req)]
      (if (or (not (clojure.core/get req :throw-exceptions true))
              (unexceptional-status? status))
        resp
        (throw (ex-info (str "clj-http: status " (:status resp)) resp))))))

(declare wrap-redirects)

(defn follow-redirect [client req resp]
  (let [url (get-in resp [:headers "location"])]
    ((wrap-redirects client) (assoc req :url url))))

(defn wrap-redirects [client]
  (fn [{:keys [request-method follow-redirects] :as req}]
    (let [{:keys [status] :as resp} (client req)]
      (cond
       (= false follow-redirects)
       resp
       (and (#{301 302 307} status) (#{:get :head} request-method))
       (follow-redirect client req resp)
       (and (= 303 status) (= :head request-method))
       (follow-redirect client (assoc req :request-method :get) resp)
       :else
       resp))))

(defn wrap-decompression [client]
  (fn [req]
    (if (get-in req [:headers "Accept-Encoding"])
      (client req)
      (let [req-c (update req :headers assoc "Accept-Encoding" "gzip, deflate")
            resp-c (client req-c)]
        (case (or (get-in resp-c [:headers "Content-Encoding"])
                  (get-in resp-c [:headers "content-encoding"]))
          "gzip" (update resp-c :body util/gunzip)
          "deflate" (update resp-c :body util/inflate)
          resp-c)))))

(defn wrap-output-coercion [client]
  (fn [{:keys [as] :as req}]
    (let [{:keys [body] :as resp} (client req)]
      (if body
        (cond
         (keyword? as)
         (condp = as
           ;; Don't do anything for streams
           :stream resp
           ;; Don't do anything when it's a byte-array
           :byte-array resp
           ;; Automatically determine response type
           :auto
           (assoc resp
             :body
             (let [typestring (get-in resp [:headers "content-type"])]
               (cond
                (.startsWith (str typestring) "text/")
                (if-let [charset (second (re-find #"charset=(.*)"
                                                  (str typestring)))]
                  (String. #^"[B" body ^String charset)
                  (String. #^"[B" body "UTF-8"))

                :else
                (String. #^"[B" body "UTF-8"))))
           ;; No :as matches found
           (assoc resp :body (String. #^"[B" body "UTF-8")))
         ;; Try the charset given if a string is specified
         (string? as)
         (assoc resp :body (String. #^"[B" body ^String as))
         ;; Return a regular UTF-8 string body
         :else
         (assoc resp :body (String. #^"[B" body "UTF-8")))
        resp))))

(defn wrap-input-coercion [client]
  (fn [{:keys [body body-encoding _length] :as req}]
    (if body
      (cond
       (string? body)
       (client (-> req (assoc :body (.getBytes ^String body)
                              :character-encoding (or body-encoding
                                                      "UTF-8"))))
       :else
       (client req))
      (client req))))

(defn content-type-value [type]
  (if (keyword? type)
    (str "application/" (name type))
    type))

(defn wrap-content-type [client]
  (fn [{:keys [content-type] :as req}]
    (if content-type
      (client (-> req (assoc :content-type
                        (content-type-value content-type))))
      (client req))))

(defn wrap-accept [client]
  (fn [{:keys [accept] :as req}]
    (if accept
      (client (-> req (dissoc :accept)
                  (assoc-in [:headers "Accept"]
                            (content-type-value accept))))
      (client req))))

(defn accept-encoding-value [accept-encoding]
  (str/join ", " (map name accept-encoding)))

(defn wrap-accept-encoding [client]
  (fn [{:keys [accept-encoding] :as req}]
    (if accept-encoding
      (client (-> req (dissoc :accept-encoding)
                  (assoc-in [:headers "Accept-Encoding"]
                            (accept-encoding-value accept-encoding))))
      (client req))))

(defn generate-query-string [params]
  (str/join "&"
            (mapcat (fn [[k v]]
                      (if (sequential? v)
                        (map #(str (util/url-encode (name %1))
                                   "="
                                   (util/url-encode (str %2)))
                             (repeat k) v)
                        [(str (util/url-encode (name k))
                              "="
                              (util/url-encode (str v)))]))
                    params)))

(defn wrap-query-params [client]
  (fn [{:keys [query-params] :as req}]
    (if query-params
      (client (-> req (dissoc :query-params)
                  (assoc :query-string
                    (generate-query-string query-params))))
      (client req))))

(defn basic-auth-value [basic-auth]
  (let [basic-auth (if (string? basic-auth)
                     basic-auth
                     (str (first basic-auth) ":" (second basic-auth)))]
    (str "Basic " (util/base64-encode (util/utf8-bytes basic-auth)))))

(defn wrap-basic-auth [client]
  (fn [req]
    (if-let [basic-auth (:basic-auth req)]
      (client (-> req (dissoc :basic-auth)
                  (assoc-in [:headers "Authorization"]
                            (basic-auth-value basic-auth))))
      (client req))))

(defn parse-user-info [user-info]
  (when user-info
    (str/split user-info #":")))

(defn wrap-user-info [client]
  (fn [req]
    (if-let [[user password] (parse-user-info (:user-info req))]
      (client (assoc req :basic-auth [user password]))
      (client req))))

(defn wrap-method [client]
  (fn [req]
    (if-let [m (:method req)]
      (client (-> req (dissoc :method)
                  (assoc :request-method m)))
      (client req))))

(defn wrap-form-params [client]
  (fn [{:keys [form-params request-method] :as req}]
    (if (and form-params (= :post request-method))
      (client (-> req
                  (dissoc :form-params)
                  (assoc :content-type (content-type-value
                                        :x-www-form-urlencoded)
                         :body (generate-query-string form-params))))
      (client req))))

(defn wrap-url [client]
  (fn [req]
    (if-let [url (:url req)]
      (client (-> req (dissoc :url) (merge (parse-url url))))
      (client req))))

(defn wrap-unknown-host [client]
  (fn [{:keys [ignore-unknown-host?] :as req}]
    (try
      (client req)
      (catch UnknownHostException e
        (if ignore-unknown-host?
          nil
          (throw e))))))

(defn wrap-request
  "Returns a battaries-included HTTP request function coresponding to the given
   core client. See client/client."
  [request]
  (-> request
      wrap-query-params
      wrap-user-info
      wrap-url
      wrap-redirects
      wrap-decompression
      wrap-input-coercion
      wrap-output-coercion
      wrap-exceptions
      wrap-basic-auth
      wrap-accept
      wrap-accept-encoding
      wrap-content-type
      wrap-form-params
      wrap-method
      wrap-links
      wrap-unknown-host))

(def #^{:doc
        "Executes the HTTP request corresponding to the given map and returns
   the response map for corresponding to the resulting HTTP response.

   In addition to the standard Ring request keys, the following keys are also
   recognized:
   * :url
   * :method
   * :query-params
   * :basic-auth
   * :content-type
   * :accept
   * :accept-encoding
   * :as

  The following additional behaviors over also automatically enabled:
   * Exceptions are thrown for status codes other than 200-207, 300-303, or 307
   * Gzip and deflate responses are accepted and decompressed
   * Input and output bodies are coerced as required and indicated by the :as
     option."}
  request
  (wrap-request #'core/request))

(defn get
  "Like #'request, but sets the :method and :url as appropriate."
  [url & [req]]
  (request (merge req {:method :get :url url})))

(defn head
  "Like #'request, but sets the :method and :url as appropriate."
  [url & [req]]
  (request (merge req {:method :head :url url})))

(defn post
  "Like #'request, but sets the :method and :url as appropriate."
  [url & [req]]
  (request (merge req {:method :post :url url})))

(defn put
  "Like #'request, but sets the :method and :url as appropriate."
  [url & [req]]
  (request (merge req {:method :put :url url})))

(defn delete
  "Like #'request, but sets the :method and :url as appropriate."
  [url & [req]]
  (request (merge req {:method :delete :url url})))

(defmacro with-connection-pool
  "This macro is a no-op, but left in to support backward-compatibility
  with clj-http."
  [_opts & body]
  `(do
     ~@body))
