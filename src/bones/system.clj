(ns bones.system
  (:require [com.stuartsierra.component :as component]
            [bones.jobs :as jobs]
            [aleph.http :refer [start-server]]
            [schema.core :as s]
            [taoensso.timbre :as log]
            [clojure.edn :as edn]
            [onyx.api]
            [onyx.log.zookeeper :refer [zookeeper]]
            [onyx.kafka.embedded-server :as ke]
            [clj-kafka.zk :as zk]
            [clj-kafka.producer :as kp]
            [onyx.plugin.kafka] ;;must be in classpath
            [onyx.plugin.redis] ;;must be in classpath
            [onyx.plugin.core-async] ;;must be in classpath
            [bones.conf :as conf]
            [bones.kafka]))

(defrecord OnyxPeerGroup [conf]
  component/Lifecycle
  (start [cmp]
    ;;validates with onyx.schema/PeerConfig
    (if (:peer-group cmp)
      (do
        (log/info "Onyx Peer Group already started")
        cmp)
      (do
        (log/info "Starting Onyx Peer Group")
        ;; assuming zookeeper is already started
        (let [pconf (assoc conf :zookeeper/server? false)]
          (assoc cmp
                 :peer-group
                 (onyx.api/start-peer-group pconf))))))
  (stop [cmp]
    (if-let [pg (:peer-group cmp)]
      (try
        (log/info "Stopping Onyx Peer Group")
        (onyx.api/shutdown-peer-group pg)
        (dissoc cmp :peer-group)
        (catch InterruptedException e
          (log/warn (str "Peer Group not shutting down:" (.getMessage e)))))
      (do
        (log/info "Onyx Peer Group is not running")
        cmp))))

(defrecord OnyxPeers [n-peers onyx-peer-group conf]
  component/Lifecycle
  ;; requires OnyxPeerGroup
  ;; using dependecy injection of n-peers onyx-peer-group
  (start [cmp]
    (if (:peers cmp)
      (do
        (log/info "Onyx Peers already started")
        cmp)
      (do
        (log/info "Starting Onyx Peers")
        (let [npeers (or (:onyx.peer/n-peers conf) n-peers 4)]
          (assoc cmp
                 :peers
                 (onyx.api/start-peers npeers (:peer-group onyx-peer-group)))))))
  (stop [cmp]
    (if-let [pg (:peers cmp)]
      (do
        (log/info "Stopping Onyx Peers")
        (doseq [v-peer (:peers cmp)]
          (try
            (onyx.api/shutdown-peer v-peer)
            (catch InterruptedException e
              (log/warn "Peer not shutting down: " (.getMessage e)))))
        ;; maybe optionally wait for completion(?)
        (dissoc cmp :peers))
      (do
        (log/info "Onyx peers is not running")
        cmp))))

(s/defschema JobsConf
  {(s/optional-key :zookeeper/address) s/Str
   (s/optional-key :kafka/serializer-fn) s/Keyword
   (s/optional-key :kafka/deserializer-fn) s/Keyword
   (s/optional-key :onyx.task-scheduler) s/Keyword
   s/Any s/Any})

(defrecord Jobs [conf]
  component/Lifecycle
  (start [cmp]
    (s/validate JobsConf conf)
    (if (empty? (:submitted-jobs cmp))
      (do
        (log/info "Starting Jobs")
        (let [command-job-specs (:bones/jobs conf)
              background-job-specs (:bones/background-jobs conf)
              ;; assume zookeeper is already started
              pconf (assoc conf :zookeeper/server? false)
              command-jobs (bones.jobs/build-jobs pconf (keys command-job-specs))
              background-jobs (bones.jobs/build-background-jobs pconf (keys background-job-specs))
              job-specs (conj command-job-specs background-job-specs)
              jobs (concat command-jobs background-jobs)]
          (doseq [[job spec] command-job-specs]
            ;; create topics required by onyx.kafka plugin to exist
            ;; todo: we can check if the topic exists and use clj-kafka.admin functions
            (bones.kafka/produce (bones.jobs/topic-name-input job) "init" {:segment "init"}))
          (doseq [[job spec] background-job-specs]
            ;; this only needs to be done once per namespace, but more than once doesn't hurt
            (bones.kafka/produce (bones.jobs/ns-name-output job) "init" {:segment "init"}))
          (-> cmp
              (assoc :job-specs job-specs)
              (assoc :jobs jobs)
              (assoc :pconf pconf)
              (assoc :submitted-jobs (mapv (partial onyx.api/submit-job pconf) jobs)))))
      (do
        (log/info "Jobs have already been submitted")
        cmp)))
  (stop [cmp]
    (if (not-empty (:submitted-jobs cmp))
      (do
        (log/info "Stopping Jobs")
        (let [job-ids (mapv :job-id (:submitted-jobs cmp))
              ;; no need to start zookeeper here
              pconf (assoc conf :zookeeper/server? false)]
          (doseq [job-id job-ids]
            (onyx.api/kill-job pconf job-id))
          ;; (update cmp :submitted-jobs (partial filter #(contains? job-ids (:job %) ))
          (dissoc cmp :submitted-jobs)))
      (do
        (log/info "No jobs to stop")
        cmp))))

(s/defschema HttpConf
  {:http/handler s/Any
   :http/port s/Int
   s/Any s/Any})

(defrecord HTTP [conf]
  component/Lifecycle
  (start [cmp]
    (s/validate HttpConf conf)
    (if (:server cmp)
      (do
        (println "server is running on port: " (:port cmp))
        cmp)
      (let [{:keys [:http/handler :http/port]} conf
            server (start-server handler {:port port})]
        (-> cmp
         (assoc :server server)
         ;; in case port is nil, get real port
         (assoc :port (aleph.netty/port server))))))
  (stop [cmp] ;; todo add force option
    (if-let [server (:server cmp)]
      (do
        (.close server) ;; this will hang if connections exist
        (dissoc cmp :server))
      cmp)))

(s/defschema ZkConf
  {(s/optional-key :zookeeper/server?) s/Bool
   :zookeeper/address s/Str
   :onyx/id s/Str
   s/Any s/Any} )

(defrecord ZK [conf]
  component/Lifecycle
  (start [cmp]
    (s/validate ZkConf conf)
    (if (:zookeeper cmp)
      (do
        (log/info "ZooKeeper is already running")
        cmp)
      (assoc cmp :zookeeper (.start (zookeeper conf)))))
  (stop [cmp]
    (if (:zookeeper cmp)
      (do
        (.stop (:zookeeper cmp))
        (dissoc cmp :zookeeper))
      (do
        (log/info "ZooKeeper is not running")
        cmp))))

(s/defschema KafkaConf
  {:kafka/hostname s/Str
   :kafka/port (s/cond-pre s/Str s/Int)
   :kafka/broker-id (s/cond-pre s/Str s/Int)
   :zookeeper-addr s/Str
   (s/optional-key :kafka/num-partitions) (s/cond-pre s/Str s/Int)
   (s/optional-key :kafka/log-dir ) s/Str
   s/Any s/Any})

(defrecord Kafka [conf]
  component/Lifecycle
  (start [cmp]
    (s/validate KafkaConf conf)
    (if (:kafka cmp)
      (do
        (log/info "Kafka is already running")
        cmp)
      (let [{:keys [:kafka/hostname :kafka/port :kafka/broker-id :kafka/log-dir :kafka/num-partitions :zookeeper-addr ]} conf
            kconf {:hostname hostname
                   :port port
                   :broker-id broker-id
                   :zookeeper-addr zookeeper-addr
                   :log-dir log-dir
                   :num-partitions num-partitions}]
        (assoc cmp :kafka (.start (ke/map->EmbeddedKafka kconf))))))
  (stop [cmp]
    (if (:kafka cmp)
      (do
        (log/info "Stopping Kafka")
        (.stop (:kafka cmp))
        (dissoc cmp :kafka))
      (do
        (log/info "Kafka is not running")
        cmp))))

(defn system [config]
  (component/system-map
   :conf (conf/map->Conf (assoc config
                                :sticky-keys (keys config)
                                :mappy-keys [[:zookeeper-addr :zookeeper/address]]))
   :http (component/using
          (map->HTTP {})
          [:conf])
   :zookeeper (component/using
               (map->ZK {}) ;; gets config from conf
               [:conf])
   :kafka (component/using
           (map->Kafka {})
           [:zookeeper :conf])
   :onyx-peer-group (component/using
                     (map->OnyxPeerGroup {}) ;; gets conf from conf
                     [:kafka :conf])
   :onyx-peers (component/using
                (map->OnyxPeers {:n-peers 4})
                [:onyx-peer-group :conf])
   :jobs (component/using
          (map->Jobs {})
          [:onyx-peers :conf])
   ))

(defn start-system [system & components]
  (swap! system component/update-system components component/start))

(defn stop-system [system & components]
  (swap! system component/update-system-reverse components component/stop))
