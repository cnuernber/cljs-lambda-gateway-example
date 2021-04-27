(ns gateway-example.proxy-lambda
  "Implement the lambda runtime for gateway proxying applications.
  Converts gateway proxy messages <-> ring compatible http
  datastructures."
  (:require [org.httpkit.client :as client]
            [clojure.tools.logging :as log]
            [jsonista.core :as json]
            [camel-snake-kebab.core :as csk]
            [clojure.stacktrace :as st]
            [clojure.java.io :as io]
            [gateway-example.main :as app-main])
  (:import [java.util Base64]
           [java.io File InputStream ByteArrayOutputStream])
  (:gen-class))


(set! *warn-on-reflection* true)


(defonce queue-location* (delay (System/getenv "AWS_LAMBDA_RUNTIME_API")))


(defn decode-string
  ^String [^String str-data]
  (when str-data
    (-> (.decode (Base64/getDecoder) str-data)
        (String.))))


(defn- encode-file-or-stream
  ^String [input]
  (let [os (ByteArrayOutputStream.)]
    (io/copy input os)
    (String. (.encode (Base64/getEncoder) (.toByteArray os)))))


(defn gateway-proxy-lambda->ring
  "Proxy event body -> ring http request.  Event bodies are passed as base64 encoded
  strings."
  [event]
  (let [event-body (json/read-value (:body event))
        event-body-body (get event-body "body")]
    {:headers (get event-body "headers")
     :request-method (csk/->kebab-case-keyword (get event-body "httpMethod" "get"))
     :scheme :http
     :uri (get event-body "path" "/")
     :body (decode-string event-body-body)}))


(defn ring->gateway-proxy-lambda
  [{:keys [body] :as processed-result}]
  (let [binary-encode? (or (instance? File body)
                           (instance? InputStream body))]
    {:headers {"Content-Type" "application/json"}
     :body
     (json/write-value-as-string
      (merge
       {:statusCode (:status processed-result)
        ;;The only robust way to return headers is multi-value-headers
        :multiValueHeaders
        (->> (:headers processed-result)
             (map (fn [[k v]]
                    [k (if (sequential? v) v [v])]))
             (into {}))}
       (when (or (instance? InputStream body)
                 (and (string? body) (seq body)))
         {:body (if binary-encode?
                  (encode-file-or-stream body)
                  body)
          :isBase64Encoded binary-encode?})))}))

(defn process-next-request!
  [event handler {:keys [log-event? log-request? log-response?]}]
  (try
    (when log-event? (log/info (json/write-value-as-string
                                (assoc event :log-type :lambda-event))))
    (let [request (gateway-proxy-lambda->ring event)
          _ (when log-request?
              (log/info (json/write-value-as-string
                         (assoc request :log-type :ring-request))))
          response (handler request)]
      (when log-response?
        ;;The result may not transfer to json as it may have streams for the body.
        (try
          (log/info (json/write-value-as-string response))
          (catch Throwable e nil)))
      (ring->gateway-proxy-lambda response))
    (catch Throwable e
      (log/error (json/write-value-as-string
                  {:message "Failed to handle request"
                   :log-type :error
                   :exception (.getMessage e)
                   :stack-trace (with-out-str (st/print-stack-trace e))}))
      (ring->gateway-proxy-lambda {:status 500
                                   :body "Server error"}))))


(defn handle-requests
  [handler process-options]
  (while (constantly true)
    (try
      (let [event @(client/get
                    (format "http://%s/2018-06-01/runtime/invocation/next"
                            @queue-location*))
            request-id (get-in event [:headers :lambda-runtime-aws-request-id])
            result (process-next-request! event handler process-options)]
        @(client/post
          (format "http://%s/2018-06-01/runtime/invocation/%s/response"
                  @queue-location* request-id)
          result))
      (catch Throwable e
        (log/error (json/write-value-as-string
                    {:message "Caught error in handle-requests."
                     :exception (.getMessage e)
                     :stack-trace (with-out-str (st/print-stack-trace e))}))
        true))))


(defn -main
  [& args]
  (handle-requests (app-main/handler)
                   {:log-event? false
                    :log-request? true
                    :log-response? false}))
