(ns puppetlabs.services.jruby.jruby-pool-int-test
  (:require [clojure.test :refer :all]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as tk-testutils]
            [puppetlabs.services.jruby.jruby-testutils :as jruby-testutils]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.trapperkeeper.services :as tk-services]
            [puppetlabs.services.protocols.jruby-puppet :as jruby-protocol]
            [puppetlabs.services.jruby.jruby-puppet-service :as jruby]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Utilities

(def default-borrow-timeout 180000)

(defn timed-deref
  [ref]
  (deref ref 240000 :timed-out))

(defn get-stack-trace-for-thread-as-str
  [stack-trace-elements]
  (reduce
   (fn [acc stack-trace-element]
     (str acc
          "  "
          (.getClassName stack-trace-element)
          "."
          (.getMethodName stack-trace-element)
          "("
          (.getFileName stack-trace-element)
          ":"
          (.getLineNumber stack-trace-element)
          ")"
          "\n"))
   ""
   stack-trace-elements))

(defn get-all-stack-traces-as-str
  []
  (reduce
   (fn [acc thread-stack-element]
     (let [thread (key thread-stack-element)]
       (str acc
            "\""
            (.getName thread)
            "\" id="
            (.getId thread)
            " state="
            (.getState thread)
            "\n"
            (get-stack-trace-for-thread-as-str
             (val thread-stack-element)))))
   ""
   (Thread/getAllStackTraces)))

(def script-to-check-if-constant-is-defined
  "! $instance_id.nil?")

(defn add-watch-for-flush-complete
  [pool-context]
  (let [flush-complete (promise)]
    (add-watch (:pool-agent pool-context) :flush-callback
               (fn [k a old-state new-state]
                 (when (= k :flush-callback)
                   (remove-watch a :flush-callback)
                   (deliver flush-complete true))))
    flush-complete))

(defn set-constants-and-verify
  [pool-context num-instances]
  ;; here we set a variable called 'instance_id' in each instance
  (jruby-testutils/reduce-over-jrubies!
    pool-context
    num-instances
    #(format "$instance_id = %s" %))
  ;; and validate that we can read that value back from each instance
  (= (set (range num-instances))
     (-> (jruby-testutils/reduce-over-jrubies!
           pool-context
           num-instances
           (constantly "$instance_id"))
         set)))

(defn constant-defined?
  [jruby-instance]
  (let [sc (:scripting-container jruby-instance)]
    (.runScriptlet sc script-to-check-if-constant-is-defined)))

(defn check-all-jrubies-for-constants
  [pool-context num-instances]
  (jruby-testutils/reduce-over-jrubies!
    pool-context
    num-instances
    (constantly script-to-check-if-constant-is-defined)))

(defn check-jrubies-for-constant-counts
  [pool-context expected-num-true expected-num-false]
  (let [constants (check-all-jrubies-for-constants
                    pool-context
                    (+ expected-num-false expected-num-true))]
    (and (= (+ expected-num-false expected-num-true) (count constants))
         (= expected-num-true (count (filter true? constants)))
         (= expected-num-false (count (filter false? constants))))))

(defn verify-no-constants
  [pool-context num-instances]
  ;; verify that the constants are cleared out from the instances by looping
  ;; over them and expecting a 'NameError' when we reference the constant by name.
  (every? false? (check-all-jrubies-for-constants pool-context num-instances)))

(defn wait-for-new-pool
  [jruby-service]
  (let [max-new-pool-wait-count 100000]
    ;; borrow until we get an instance that doesn't have a constant,
    ;; so we'll know that the new pool is online
    (loop [instance (jruby-protocol/borrow-instance jruby-service :wait-for-new-pool)
           loop-count 0]
      (let [has-constant? (constant-defined? instance)]
        (jruby-protocol/return-instance jruby-service instance :wait-for-new-pool)
        (cond
          (not has-constant?) true
          (= loop-count max-new-pool-wait-count) false
          :else (recur (jruby-protocol/borrow-instance
                        jruby-service
                        :wait-for-new-pool)
                       (inc loop-count)))))))

(defn borrow-until-desired-borrow-count
  [jruby-service desired-borrow-count]
  (let [max-borrow-wait-count 100000]
    (loop [instance (jruby-protocol/borrow-instance jruby-service :borrow-until-desired-borrow-count)
           loop-count 0]
      (let [borrow-count (:borrow-count @(:state instance))]
        (jruby-protocol/return-instance jruby-service instance :borrow-until-desired-borrow-count)
        (cond
          (= (inc borrow-count) desired-borrow-count) true
          (= loop-count max-borrow-wait-count) false
          :else (recur (jruby-protocol/borrow-instance
                        jruby-service
                        :borrow-until-desired-borrow-count)
                       (inc loop-count)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Tests

;; TODO: this test seems redundant to the one in jruby-puppet-agents-test
(deftest ^:integration flush-jruby-pool-test
  (testing "Flushing the pool results in all new JRuby instances"
    (tk-testutils/with-app-with-config
      app
      [jruby/jruby-puppet-pooled-service]
      (jruby-testutils/jruby-puppet-tk-config
       (jruby-testutils/jruby-puppet-config {:max-active-instances      4
                                             :borrow-timeout default-borrow-timeout}))
      (let [jruby-service (tk-app/get-service app :JRubyPuppetService)
            context (tk-services/service-context jruby-service)
            pool-context (:pool-context context)]
        ;; set a ruby constant in each instance so that we can recognize them
        (is (true? (set-constants-and-verify pool-context 4)))
        (let [flush-complete (add-watch-for-flush-complete pool-context)]
          (jruby-protocol/flush-jruby-pool! jruby-service)
          (is (true? (timed-deref flush-complete))
              (str "timed out waiting for the flush to complete, stack:\n"
                   (get-all-stack-traces-as-str))))
        ;; now the pool is flushed, so the constants should be cleared
        (is (true? (verify-no-constants pool-context 4)))))))

(deftest ^:integration hold-instance-while-pool-flush-in-progress-test
  (testing "instance borrowed from old pool before pool flush begins and returned *after* new pool is available"
    (tk-testutils/with-app-with-config
      app
      [jruby/jruby-puppet-pooled-service]
      (jruby-testutils/jruby-puppet-tk-config
       (jruby-testutils/jruby-puppet-config {:max-active-instances      4
                                             :borrow-timeout default-borrow-timeout}))
      (let [jruby-service (tk-app/get-service app :JRubyPuppetService)
            context (tk-services/service-context jruby-service)
            pool-context (:pool-context context)]
        ;; set a ruby constant in each instance so that we can recognize them
        (is (true? (set-constants-and-verify pool-context 4)))
        (let [flush-complete (add-watch-for-flush-complete pool-context)
              ;; borrow an instance and hold the reference to it.
              instance (jruby-protocol/borrow-instance jruby-service
                                                       :hold-instance-while-pool-flush-in-progress-test)]
          ;; trigger a flush
          (jruby-protocol/flush-jruby-pool! jruby-service)
          ;; wait for the new pool to become available
          (is (true? (wait-for-new-pool jruby-service)))
          ;; return the instance
          (jruby-protocol/return-instance jruby-service instance :hold-instance-while-pool-flush-in-progress-test)
          ;; wait until the flush is complete
          (is (true? (timed-deref flush-complete))
              (str "timed out waiting for the flush to complete, stack:\n"
                   (get-all-stack-traces-as-str))))
        ;; now the pool is flushed, and the constants should be cleared
        (is (true? (verify-no-constants pool-context 4)))))))

(deftest ^:integration hold-file-handle-on-instance-while-pool-flush-in-progress-test
  (testing "file handle opened from old pool instance is held open across pool flush"
    (tk-testutils/with-app-with-config
      app
      [jruby/jruby-puppet-pooled-service]
      (jruby-testutils/jruby-puppet-tk-config
       (jruby-testutils/jruby-puppet-config {:max-active-instances      4
                                             :borrow-timeout default-borrow-timeout}))
      (let [jruby-service (tk-app/get-service app :JRubyPuppetService)
            context (tk-services/service-context jruby-service)
            pool-context (:pool-context context)]
        ;; set a ruby constant in each instance so that we can recognize them
        (is (true? (set-constants-and-verify pool-context 2)))
        (let [flush-complete (add-watch-for-flush-complete pool-context)
              ;; borrow an instance and hold the reference to it.
              instance (jruby-protocol/borrow-instance jruby-service
                         :hold-instance-while-pool-flush-in-progress-test)
              sc (:scripting-container instance)]
          (.runScriptlet sc
                         (str "require 'tempfile'\n\n"
                              "$unique_file = "
                              "Tempfile.new"
                              "('hold-instance-test-', './target')"))
          (try
            ;; trigger a flush
            (jruby-protocol/flush-jruby-pool! jruby-service)
            ;; wait for the new pool to become available
            (is (true? (wait-for-new-pool jruby-service)))

            (is (nil? (.runScriptlet sc "$unique_file.close"))
                "Unexpected response on attempt to close unique file")
            (finally
              (.runScriptlet sc "$unique_file.unlink")))
          ;; return the instance
          (jruby-protocol/return-instance jruby-service instance :hold-instance-while-pool-flush-in-progress-test)
          ;; wait until the flush is complete
          (is (true? (timed-deref flush-complete))
              (str "timed out waiting for the flush to complete, stack:\n"
                   (get-all-stack-traces-as-str))))
        ;; now the pool is flushed, and the constants should be cleared
        (is (true? (verify-no-constants pool-context 2)))))))

(deftest ^:integration max-requests-flush-while-pool-flush-in-progress-test
  (testing "instance from new pool hits max-requests while flush in progress"
    (jruby-testutils/with-mock-pool-instance-fixture
     (tk-testutils/with-app-with-config
       app
       [jruby/jruby-puppet-pooled-service]
       (jruby-testutils/jruby-puppet-tk-config
        (jruby-testutils/jruby-puppet-config {:max-active-instances 4
                                              :max-requests-per-instance 10
                                              :borrow-timeout
                                              default-borrow-timeout}))

       (let [jruby-service (tk-app/get-service app :JRubyPuppetService)
             context (tk-services/service-context jruby-service)
             pool-context (:pool-context context)]
         ;; set a ruby constant in each instance so that we can recognize them.
         ;; this counts as one request for each instance.
         (is (true? (set-constants-and-verify pool-context 4)))
         (let [flush-complete (add-watch-for-flush-complete pool-context)
               ;; borrow one instance and hold the reference to it, to prevent
               ;; the flush operation from completing
               instance1 (jruby-protocol/borrow-instance jruby-service
                                                         :max-requests-flush-while-pool-flush-in-progress-test)]
           ;; we are going to borrow and return a second instance until we get its
           ;; request count up to max-requests - 1, so that we can use it to test
           ;; flushing behavior the next time we return it.
           (is (true? (borrow-until-desired-borrow-count jruby-service 9)))
           ;; now we grab a reference to that instance and hold onto it for later.
           (let [instance2 (jruby-protocol/borrow-instance jruby-service
                                                           :max-requests-flush-while-pool-flush-in-progress-test)]
             (is (= 9 (:borrow-count @(:state instance2))))

             ;; trigger a flush
             (jruby-protocol/flush-jruby-pool! jruby-service)
             ;; wait for the new pool to become available
             (is (true? (wait-for-new-pool jruby-service)))
             ;; there will only be two instances in the new pool, because we are holding
             ;; references to two from the old pool.
             (is (true? (set-constants-and-verify pool-context 2)))
             ;; borrow and return instance from the new pool until an instance flush is triggered
             (is (true? (borrow-until-desired-borrow-count
                         jruby-service
                         10)))

             ;; at this point, we still have the main flush in progress, waiting for us
             ;; to release the two instances from the old pool.  we should also have
             ;; caused a flush of one of the two instances in the new pool, meaning that
             ;; exactly one of the two in the new pool should have the ruby constant defined.
             (is (true? (check-jrubies-for-constant-counts pool-context 1 1)))

             ;; now we'll set the ruby constants on both instances in the new pool
             (is (true? (set-constants-and-verify pool-context 2)))

             ;; now we're going to return instance2 to the pool.  This should cause it
             ;; to get flushed, but after that, the main pool flush operation should
             ;; pull it out of the old pool and create a new instance in the new pool
             ;; to replace it.  So we should end up with 3 instances in the new pool,
             ;; two of which should have the ruby constants and one of which should not.
             (jruby-protocol/return-instance jruby-service instance2 :max-requests-flush-while-pool-flush-in-progress-test)
             (is (true? (check-jrubies-for-constant-counts pool-context 2 1))))

           ;; now we'll set the ruby constant on the 3 instances in the new pool
           (is (true? (set-constants-and-verify pool-context 3)))

           ;; and finally, we return the last instance from the old pool
           (jruby-protocol/return-instance jruby-service instance1 :max-requests-flush-while-pool-flush-in-progress-test)

           ;; wait until the flush is complete
           (is (true? (timed-deref flush-complete))
               (str "timed out waiting for the flush to complete, stack:\n"
                    (get-all-stack-traces-as-str))))

         ;; we should have three instances with the constant and one without.
         (is (true? (check-jrubies-for-constant-counts pool-context 3 1))))))))