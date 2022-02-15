(ns songpark.jam.util)

(defn get-jam-topic-subscriptions [{:keys [jam/id] :as _jam-data}]
  {(str id "/jam") 0
   (str id "/teleporters") 0})

(defn get-jam-topic-teleporters [{:keys [jam/id] :as _jam-data}]
  (str id "/teleporters"))

(defn get-jam-topic-jam [{:keys [jam/id] :as _jam-data}]
  (str id "/jam"))
