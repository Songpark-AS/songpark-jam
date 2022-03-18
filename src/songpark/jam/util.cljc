(ns songpark.jam.util)

(defn get-id []
  #?(:clj  (java.util.UUID/randomUUID)
     :cljs (random-uuid)))

(defn get-jam-topic-subscriptions
  "Get the subscriptions for a jam"
  [environment {jam-id :jam/id} & [teleporter-id]]
  (case environment

    :platform
    {(str jam-id "/jam/platform") 2}

    :teleporter
    (do (assert (some? teleporter-id) "Missing teleporter-id]")
        {(str jam-id "/jam/teleporter/" teleporter-id) 2})
    
    :app
    {(str jam-id "/jam") 2
     (str jam-id "/jam/app") 2}
    
    (throw (ex-info "No supported environment was found. Supported environments are :teleporter and :app" {:environment environment}))))

(defn get-jam-topic
  "Used togeter with what and the jam-data to get the topic to publish to"
  [what {jam-id :jam/id} & [teleporter-id]]
  (case what
    ;; send to any app that is listening in on the jam
    ;; teleporters are the ones using this topic
    :app
    (str jam-id "/jam/app")

    :platform
    (str jam-id "/jam/platform")

    ;; topic directly to a teleporter that is in a jam
    :teleporter
    (do (assert (some? teleporter-id) "Missing teleporter-id")
        (str jam-id "/jam/teleporter/" teleporter-id))

    ;; the general jam info. platform and teleporters send on this
    :jam
    (str jam-id "/jam")
    
    (throw (ex-info "No supported topic found" {:what what
                                                :jam/id jam-id
                                                :teleporter/id teleporter-id}))))
