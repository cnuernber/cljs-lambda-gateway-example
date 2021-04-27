(ns gateway-example.main
  (:require [org.httpkit.server :as server]
            [hiccup.page :as hiccup]
            [ring.util.response :as response]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.middleware.content-type :refer [wrap-content-type]]
            ;;They dynamically load this file which breaks graal native
            [muuntaja.middleware :refer [wrap-format]]
            [ring.middleware.cookies :refer [wrap-cookies]]
            [bidi.ring :as bidi-ring]
            [clojure.tools.logging :as log])
  (:import [java.util Date]
           [java.net URL URLConnection]
           [java.io ByteArrayOutputStream ByteArrayInputStream])
  (:gen-class))


(defn- home-page
  [request]
  (-> (hiccup/html5
       {:lang "en"}
       [:head
        [:meta {:charset "utf-8"}]
        [:title "Graal CLJS"]
        [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
        [:meta {:name "description" :content "Graal example of cljs"}]
        [:link {:href "//fonts.googleapis.com/css?family=Raleway:400,300,600"
                :rel "stylesheet"
                :type "text/css"}]
        [:link {:rel "stylesheet" :href "css/normalize.css"}]
        [:link {:rel "stylesheet" :href "css/skeleton.css"}]
        [:body [:div#app]]
        [:script {:src "js/app.js" :type "text/javascript"}]])
      (response/response)
      (response/header "Content-Type" "text/html")))


(def routes ["/" {:get [[true #'home-page]]}])

(defn handler
  []
  (-> (bidi-ring/make-handler routes)
      ;;Only get latest every 100 ms or so.
      (wrap-format)
      (wrap-cookies)
      (wrap-resource "public")
      (wrap-content-type)))


;; ring/response extension to handle resource:// url types.
(defn- connection-content-length [^URLConnection conn]
  (let [len (.getContentLength conn)]
    (if (<= 0 len) len)))


(defn- connection-last-modified [^URLConnection conn]
  (let [last-mod (.getLastModified conn)]
    (if-not (zero? last-mod)
      (Date. last-mod))))


;;Graal native returns things as :resource urls
(defmethod response/resource-data :resource
  [^URL url]
  (let [conn (.openConnection url)]
    {:content        (.getInputStream conn)
     :content-length (connection-content-length conn)
     :last-modified  (connection-last-modified conn)}))


(defonce ^:private server* (atom nil))


(defn start-server
  ([{:keys [port]
     :or {port 3000}
     :as options}]
   (swap! server*
          (fn [existing-server]
            (if existing-server
              (do
                (log/infof "Restarting server on port %d" port)
                (existing-server))
              (log/infof "Starting server on port %d" port))
            (server/run-server (handler)
                               (merge {:port port}
                                      options)))))
  ([]
   (start-server nil)))


(defn -main
  [& args]
  (start-server)
  (log/infof "Main function exiting-server still running"))
