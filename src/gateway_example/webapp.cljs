(ns gateway-example.webapp
  (:require [goog.dom :as gdom]
            [reagent.dom]))


(defn home
  []
  [:div.container
   [:h1 "Hello from Graal Native"]
   [:p "And clojurescript/reframe"]])


(defn render
  []
  (reagent.dom/render [home] (gdom/getElement "app")))

(defn ^:dev/after-load clear-cache-and-render!
  []
  (render))

(defn init
  "Entrypoint into the application."
  []
  (render))
