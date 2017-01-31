(ns puppetlabs.services.jruby-pool-manager.jruby-agents-test
  (:require [clojure.test :refer :all]
            [schema.test :as schema-test]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as tk-bootstrap]
            [puppetlabs.services.jruby-pool-manager.jruby-testutils :as jruby-testutils]
            [puppetlabs.services.jruby-pool-manager.jruby-core :as jruby-core]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.services.jruby-pool-manager.impl.jruby-agents :as jruby-agents]
            [puppetlabs.services.jruby-pool-manager.impl.jruby-pool-manager-core :as jruby-pool-manager-core]
            [puppetlabs.services.protocols.pool-manager :as pool-manager-protocol])
  (:import (puppetlabs.services.jruby_pool_manager.jruby_schemas JRubyInstance)))

(use-fixtures :once schema-test/validate-schemas)

(deftest next-instance-id-test
  (let [pool-context (jruby-pool-manager-core/create-pool-context
                      (jruby-testutils/jruby-config {:max-active-instances 8}))]
    (testing "next instance id should be based on the pool size"
      (is (= 10 (jruby-agents/next-instance-id 2 pool-context)))
      (is (= 100 (jruby-agents/next-instance-id 92 pool-context))))
    (testing "next instance id should wrap after max int"
      (let [id (- Integer/MAX_VALUE 1)]
        (is (= (mod id 8) (jruby-agents/next-instance-id id pool-context)))))))

(deftest custom-termination-test
  (testing "Flushing the pool causes cleanup hook to be called"
    (let [cleanup-atom (atom nil)
          config (assoc-in (jruby-testutils/jruby-config {:max-active-instances 1})
                           [:lifecycle :cleanup]
                           (fn [x] (reset! cleanup-atom "Hello from cleanup")))]
      (tk-bootstrap/with-app-with-config
       app
       jruby-testutils/default-services
       {}
       (let [pool-manager-service (tk-app/get-service app :PoolManagerService)
             pool-context (pool-manager-protocol/create-pool pool-manager-service config)]
         (jruby-core/flush-pool! pool-context)
         ; wait until the flush is complete
         (is (jruby-testutils/timed-await (jruby-agents/get-modify-instance-agent pool-context)))
         (is (= "Hello from cleanup" (deref cleanup-atom))))))))

(deftest collect-all-jrubies-test
  (testing "returns list of all the jruby instances"
    (tk-bootstrap/with-app-with-config
     app
     jruby-testutils/default-services
     {}
     (let [config (jruby-testutils/jruby-config {:max-active-instances 4})
           pool-manager-service (tk-app/get-service app :PoolManagerService)
           pool-context (pool-manager-protocol/create-pool pool-manager-service config)
           pool (jruby-core/get-pool pool-context)
           jruby-list (jruby-agents/borrow-all-jrubies pool-context)]
       (is (= 4 (count jruby-list)))
       (is (every? #(instance? JRubyInstance %) jruby-list))
       (is (= 0 (.size pool)))))))
