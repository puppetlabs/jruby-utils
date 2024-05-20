(ns puppetlabs.services.jruby-pool-manager.jruby-agents-test
  (:require [clojure.test :refer :all]
            [schema.test :as schema-test]
            [puppetlabs.services.jruby-pool-manager.jruby-testutils :as jruby-testutils]
            [puppetlabs.services.jruby-pool-manager.jruby-core :as jruby-core]
            [puppetlabs.services.jruby-pool-manager.impl.jruby-agents :as jruby-agents]
            [puppetlabs.services.jruby-pool-manager.impl.jruby-internal :as jruby-internal]
            [puppetlabs.services.jruby-pool-manager.impl.jruby-pool-manager-core :as jruby-pool-manager-core])
  (:import (puppetlabs.services.jruby_pool_manager.jruby_schemas JRubyInstance)))

(use-fixtures :once schema-test/validate-schemas)

(deftest execute-tasks!-test
  (let [pool-context (jruby-pool-manager-core/create-pool-context
                      (jruby-testutils/jruby-config {:instance-creation-concurrency 3}))
        creation-service (jruby-internal/get-creation-service pool-context)]
    (testing "creation-service is a FixedThreadPool of configured number of threads"
      (is (= 3 (.getMaximumPoolSize creation-service))))
    ;; this isn't a requirement and should be able to change in the future without issue,
    ;; but none of the current callers require the result, so explictly test the assumption.
    (testing "does not return results of task execution"
      (let [tasks [(fn [] :foo) (fn [] :bar)]
            results (jruby-agents/execute-tasks! tasks creation-service)]
        (is (nil? results))))
    (testing "throws original execptions"
      (let [tasks [(fn [] (throw (IllegalStateException. "BOOM")))]]
        (is (thrown? IllegalStateException (jruby-agents/execute-tasks! tasks creation-service)))))))

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
      (jruby-testutils/with-pool-context
       pool-context
       jruby-testutils/default-services
       config
       (jruby-core/flush-pool! pool-context)
       ; wait until the flush is complete
       (is (jruby-testutils/timed-await (jruby-agents/get-modify-instance-agent pool-context)))
       (is (= "Hello from cleanup" (deref cleanup-atom)))))))

(deftest collect-all-jrubies-test
  (testing "returns list of all the jruby instances"
    (jruby-testutils/with-pool-context
     pool-context
     jruby-testutils/default-services
     (jruby-testutils/jruby-config {:max-active-instances 4})
     (let [pool (jruby-core/get-pool pool-context)
           jruby-list (jruby-agents/borrow-all-jrubies pool-context)]
       (try
         (is (= 4 (count jruby-list)))
         (is (every? #(instance? JRubyInstance %) jruby-list))
         (is (= 0 (.currentSize pool)))
         (finally
           (jruby-testutils/fill-drained-pool pool-context jruby-list)))))))
