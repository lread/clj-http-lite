(ns clj-http.lite.client
  "Batteries-included HTTP client.

  NOTE: this ns is based on the Slingshot library.

  If you want a Slingshot-free ns / API instead, please use the newer `clj-http.lite.client`"
  (:require [clj-http-lite.client :as original]
            [clj-http.lite.links :refer [wrap-links]]
            [clj-http-lite.impl :refer [copy]]
            [slingshot.slingshot :refer [throw+]])
  (:refer-clojure :exclude (get update)))

(set! *warn-on-reflection* true)

(copy original/update)

(copy original/when-pos)

(copy original/parse-url)

(copy original/unexceptional-status?)

(copy original/follow-redirect)

(copy original/wrap-redirects)

(copy original/wrap-decompression)

(copy original/wrap-output-coercion)

(copy original/wrap-input-coercion)

(copy original/content-type-value)

(copy original/wrap-content-type)

(copy original/wrap-accept)

(copy original/accept-encoding-value)

(copy original/wrap-accept-encoding)

(copy original/generate-query-string)

(copy original/wrap-query-params)

(copy original/basic-auth-value)

(copy original/wrap-basic-auth)

(copy original/parse-user-info)

(copy original/wrap-user-info)

(copy original/wrap-method)

(copy original/wrap-form-params)

(copy original/wrap-url)

(copy original/wrap-unknown-host)

(copy original/request)

(copy original/get)

(copy original/head)

(copy original/post)

(copy original/put)

(copy original/delete)

(copy original/with-connection-pool)

(defn wrap-exceptions [client]
  (fn [req]
    (let [{:keys [status] :as resp} (client req)]
      (if (or (not (clojure.core/get req :throw-exceptions true))
              (unexceptional-status? status))
        resp
        (throw+ resp "clj-http: status %s" (:status %))))))

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
