(ns puppetlabs.services.jruby-pool-manager.jruby-reference-pool-test
  (:require [clojure.test :refer :all]
            [schema.test :as schema-test]
            [puppetlabs.services.jruby-pool-manager.jruby-testutils :as jruby-testutils]
            [puppetlabs.services.jruby-pool-manager.jruby-core :as jruby-core]
            [puppetlabs.services.jruby-pool-manager.impl.jruby-agents :as jruby-agents]
            [puppetlabs.services.jruby-pool-manager.impl.jruby-pool-manager-core :as jruby-pool-manager-core]
            [puppetlabs.kitchensink.core :as ks])
  (:import (puppetlabs.services.jruby_pool_manager.jruby_schemas JRubyInstance)))

(deftest collect-all-jrubies-test
  (testing "returns list of all the jruby instances"
    (jruby-testutils/with-pool-context
      pool-context
      jruby-testutils/default-services
      (jruby-testutils/jruby-config-for-ref-pool {:max-active-instances 4})
      (let [pool (jruby-core/get-pool pool-context)
            jruby-list (jruby-agents/borrow-all-jrubies pool-context)]
        (try
          (is (= 4 (count jruby-list)))
          (is (every? #(instance? JRubyInstance %) jruby-list))
          (is (= (first jruby-list) (last jruby-list)))
          (is (= 0 (.size pool)))
          (finally
            (jruby-testutils/fill-drained-pool jruby-list)))))))

(deftest test-jruby-core-funcs
  (let [pool-size        2
        timeout          250
        config           (jruby-testutils/jruby-config-for-ref-pool {:max-active-instances pool-size
                                                                     :borrow-timeout timeout})
        pool-context (jruby-pool-manager-core/create-pool-context config)
        pool             (jruby-core/get-pool pool-context)]
    (testing "The pool should not yet be full as it is being primed in the
             background."
      (is (= (jruby-core/free-instance-count pool) 0))

      (jruby-agents/prime-pool! pool-context)
      (try
        (testing "Borrowing all instances from a pool while it is being primed and
             returning them."
          (let [all-the-jrubys (jruby-testutils/drain-pool pool-context pool-size)]
            (is (= 0 (jruby-core/free-instance-count pool)))
            (doseq [instance all-the-jrubys]
              (is (not (nil? instance)) "One of JRubyInstances is nil"))
            (jruby-testutils/fill-drained-pool all-the-jrubys)
            (is (= pool-size (jruby-core/free-instance-count pool)))))

        (testing "Borrowing from an empty pool with a timeout returns nil within the
             proper amount of time."
          (let [all-the-jrubys (jruby-testutils/drain-pool pool-context pool-size)
                test-start-in-millis (System/currentTimeMillis)]
            (is (nil? (jruby-core/borrow-from-pool-with-timeout pool-context :test [])))
            (is (>= (- (System/currentTimeMillis) test-start-in-millis) timeout)
                "The timeout value was not honored.")
            (jruby-testutils/fill-drained-pool all-the-jrubys)
            (is (= (jruby-core/free-instance-count pool) pool-size)
                "All JRubyInstances were not returned to the pool.")))

        (testing "Removing an instance decrements the pool size by 1."
          (let [jruby-instance (jruby-core/borrow-from-pool pool-context :test [])]
            (is (= (jruby-core/free-instance-count pool) (dec pool-size)))
            (jruby-core/return-to-pool jruby-instance :test [])))

        (testing "Borrowing an instance increments its request count."
          (let [drain-via (fn [borrow-fn] (doall (repeatedly pool-size borrow-fn)))
                assoc-count (fn [acc jruby]
                              (assoc acc (:id jruby)
                                         (:borrow-count (jruby-core/get-instance-state jruby))))
                get-counts (fn [jrubies] (reduce assoc-count {} jrubies))]
            (doseq [drain-fn [#(jruby-core/borrow-from-pool pool-context :test [])
                              #(jruby-core/borrow-from-pool-with-timeout pool-context :test [])]]
              (let [jrubies (drain-via drain-fn)
                    counts (get-counts jrubies)]
                (jruby-testutils/fill-drained-pool jrubies)
                (let [jrubies (drain-via drain-fn)
                      new-counts (get-counts jrubies)]
                  (jruby-testutils/fill-drained-pool jrubies)
                  (is (= (ks/keyset counts) (ks/keyset new-counts)))
                  (doseq [k (keys counts)]
                    (is (= (inc (counts k)) (new-counts k)))))))))
        (finally
          (jruby-core/flush-pool-for-shutdown! pool-context))))))
