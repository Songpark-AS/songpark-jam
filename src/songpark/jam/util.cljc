(ns songpark.jam.util)

(defn get-id []
  #?(:clj  (java.util.UUID/randomUUID)
     :cljs (random-uuid)))

(defn get-jam-topic
  "Broadcast topic for a jam. Strictly to be used for broadcasting from involved
  Teleporters to Apps"
  [jam-id]
  (str jam-id "/jam"))

(defn get-jam-subscription-topic
  "Broadcast topic for a jam. Strictly to be used for broadcasting from involved
  Teleporters to Apps"
  [jam-id]
  {(str jam-id "/jam") 2})
