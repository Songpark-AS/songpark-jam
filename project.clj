(defproject songpark-jam "0.1.0-SNAPSHOT"

  :dependencies [[org.clojure/clojure "1.10.3"]
                 ;; structure
                 [com.stuartsierra/component "1.0.0" :scope "provided"]
                 ;; logging
                 [com.taoensso/timbre "5.1.2" :scope "provided"]

                 ;; MQTT library
                 [songpark/mqtt "1.0.0-alpha4"]

                 ;; core async. used for TPX IPC/Jam bindings
                 [org.clojure/core.async "1.5.648"]]

  :repl-options {:init-ns songpark.jam.tpx}

  :plugins [[lein-auto "0.1.3"]]

  :profiles {:dev {:dependencies [[clj-commons/spyscope "0.1.48"]]
                   :injections [(require 'spyscope.core)]}})
