(ns puppetlabs.services.jruby.jruby-pool-int-test
  (:require [clojure.test :refer :all]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as tk-testutils]
            [puppetlabs.services.jruby.jruby-testutils :as jruby-testutils]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.trapperkeeper.services :as tk-services]
            [puppetlabs.services.protocols.jruby :as jruby-protocol]
            [puppetlabs.services.jruby.jruby-service :as jruby]
            [clojure.tools.logging :as log]
            [puppetlabs.trapperkeeper.testutils.logging :as logutils]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Utilities

(def default-borrow-timeout 180000)

(defn timed-await
  [agent]
  (await-for 240000 agent))

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
         ;; Where a max-requests-per-instance may have been imposed for the
         ;; pool, we need to avoid having borrow counts increase on each of
         ;; the pool instances while waiting for the new pool to show up.
         ;; Otherwise, an individual instance might be flushed and
         ;; misinterpreted as the new pool having been swapped in when the
         ;; 'old' one is actually still in use.  Artificially decrement the
         ;; borrow count here to compensate for the increase that the jruby
         ;; pool would normally do when returning an instance.
        (swap! (:state instance) update-in [:borrow-count] dec)
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

;; TODO: this test seems redundant to the one in puppetlabs.services.jruby.jruby-agents-test
(deftest ^:integration flush-jruby-pool-test
  (testing "Flushing the pool results in all new JRubyInstances"
    (tk-testutils/with-app-with-config
      app
      [jruby/jruby-pooled-service]
      (jruby-testutils/jruby-tk-config
       (jruby-testutils/jruby-config {:max-active-instances      4
                                             :borrow-timeout default-borrow-timeout}))
      (let [jruby-service (tk-app/get-service app :JRubyService)
            context (tk-services/service-context jruby-service)
            pool-context (:pool-context context)]
        ;; set a ruby constant in each instance so that we can recognize them
        (is (true? (set-constants-and-verify pool-context 4)))
        (jruby-protocol/flush-jruby-pool! jruby-service)
        (is (true? (timed-await (:pool-agent pool-context)))
            (str "timed out waiting for the flush to complete, stack:\n"
                 (get-all-stack-traces-as-str)))
        ;; now the pool is flushed, so the constants should be cleared
        (is (true? (verify-no-constants pool-context 4)))))))

(deftest ^:integration hold-instance-while-pool-flush-in-progress-test
  (testing "instance borrowed from old pool before pool flush begins and returned *after* new pool is available"
    (tk-testutils/with-app-with-config
      app
      [jruby/jruby-pooled-service]
      (jruby-testutils/jruby-tk-config
       (jruby-testutils/jruby-config {:max-active-instances      4
                                             :borrow-timeout default-borrow-timeout}))
      (let [jruby-service (tk-app/get-service app :JRubyService)
            context (tk-services/service-context jruby-service)
            pool-context (:pool-context context)]
        ;; set a ruby constant in each instance so that we can recognize them
        (is (true? (set-constants-and-verify pool-context 4)))
        ;; borrow an instance and hold the reference to it.
        (let [instance (jruby-protocol/borrow-instance
                        jruby-service
                        :hold-instance-while-pool-flush-in-progress-test)]
          ;; trigger a flush
          (jruby-protocol/flush-jruby-pool! jruby-service)
          ;; wait for the new pool to become available
          (is (true? (wait-for-new-pool jruby-service)))
          ;; return the instance
          (jruby-protocol/return-instance jruby-service instance :hold-instance-while-pool-flush-in-progress-test)
          ;; wait until the flush is complete
          (is (true? (timed-await (:pool-agent pool-context)))
              (str "timed out waiting for the flush to complete, stack:\n"
                   (get-all-stack-traces-as-str))))
        ;; now the pool is flushed, and the constants should be cleared
        (is (true? (verify-no-constants pool-context 4)))))))

(deftest ^:integration hold-file-handle-on-instance-while-pool-flush-in-progress-test
  (testing "file handle opened from old pool instance is held open across pool flush"
    (tk-testutils/with-app-with-config
      app
      [jruby/jruby-pooled-service]
      (jruby-testutils/jruby-tk-config
       (jruby-testutils/jruby-config {:max-active-instances      4
                                             :borrow-timeout default-borrow-timeout}))
      (let [jruby-service (tk-app/get-service app :JRubyService)
            context (tk-services/service-context jruby-service)
            pool-context (:pool-context context)]
        ;; set a ruby constant in each instance so that we can recognize them
        (is (true? (set-constants-and-verify pool-context 2)))
        ;; borrow an instance and hold the reference to it.
        (let [instance (jruby-protocol/borrow-instance jruby-service
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
          (is (true? (timed-await (:pool-agent pool-context)))
              (str "timed out waiting for the flush to complete, stack:\n"
                   (get-all-stack-traces-as-str))))
        ;; now the pool is flushed, and the constants should be cleared
        (is (true? (verify-no-constants pool-context 2)))))))

(deftest ^:integration max-requests-flush-while-pool-flush-in-progress-test
  (testing "instance from new pool hits max-requests while flush in progress"
    (tk-testutils/with-app-with-config
     app
     [jruby/jruby-pooled-service]
     (jruby-testutils/jruby-tk-config
      (jruby-testutils/jruby-config {:max-active-instances 4
                                     :max-requests-per-instance 10
                                     :borrow-timeout
                                     default-borrow-timeout}))

     (let [jruby-service (tk-app/get-service app :JRubyService)
           context (tk-services/service-context jruby-service)
           pool-context (:pool-context context)
           pool-agent (:pool-agent pool-context)]
       ;; set a ruby constant in each instance so that we can recognize them.
       ;; this counts as one request for each instance.
       (is (true? (set-constants-and-verify pool-context 4)))

       ;; borrow one instance and hold the reference to it, to prevent
       ;; the flush operation from completing
       (let [instance1 (jruby-protocol/borrow-instance jruby-service
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

         ;; The flush should still not have completed at this point because
         ;; the last instance from the old pool is still being borrowed.
         ;; Wait for some arbitrary (but short) period of time to see that
         ;; that the agent is still busy - presumably handling the flush.
         (is (false? (await-for 50 pool-agent)))

         ;; and finally, we return the last instance from the old pool
         (jruby-protocol/return-instance jruby-service instance1 :max-requests-flush-while-pool-flush-in-progress-test)

         ;; wait until the flush is complete
         (is (true? (timed-await pool-agent))
             (str "timed out waiting for the flush to complete, stack:\n"
                  (get-all-stack-traces-as-str))))

       ;; we should have three instances with the constant and one without.
       (is (true? (check-jrubies-for-constant-counts pool-context 3 1)))

       ;; The jruby return instance calls done within the previous
       ;; check jrubies call may cause an instance to be in the process of
       ;; being flushed when the server is shut down.  This ensures that
       ;; the flushing is all done before the server is shut down - since
       ;; that could otherwise cause an annoying error message about the
       ;; pool not being full at shut down to be displayed.
       (timed-await (:flush-instance-agent pool-context))))))

(deftest initialization-and-cleanup-hooks-test
  (testing "custom initialization and cleanup callbacks get called appropriately"
    (let [lifecycle-fns {:initialize-pool-instance (fn [instance] (assoc instance :foo "FOO"))
                         :cleanup (fn [instance] (log/error "Terminating" (:foo instance)))}
          config (assoc-in (jruby-testutils/jruby-tk-config
                            (jruby-testutils/jruby-config
                             {:max-active-instances 1
                              :max-requests-per-instance 10
                              :borrow-timeout default-borrow-timeout}))
                           [:jruby :lifecycle] lifecycle-fns)]
      (logutils/with-test-logging
       (tk-testutils/with-app-with-config
        app
        [jruby/jruby-pooled-service]
        config
        (let [jruby-service (tk-app/get-service app :JRubyService)
              context (tk-services/service-context jruby-service)
              pool-context (:pool-context context)]
          ;; set a ruby constant in each instance so that we can recognize them
          (is (true? (set-constants-and-verify pool-context 1)))
          (let [instance (jruby-protocol/borrow-instance jruby-service
                                                         :initialization-and-cleanup-hooks-test)]
            (is (= "FOO" (:foo instance)))
            (jruby-protocol/return-instance jruby-service instance :initialization-and-cleanup-hooks-test))

          (jruby-protocol/flush-jruby-pool! jruby-service)
          ; wait until the flush is complete
          (await (get-in context [:pool-context :pool-agent]))
          (is (logged? #"Terminating FOO"))))))))

(deftest initialize-scripting-container-hook-test
  (testing "can set custom environment variables via :initialize-scripting-container hook"
    (let [lifecycle-fns {:initialize-scripting-container (fn [scripting-container gem-home]
                                                           (.setEnvironment scripting-container
                                                                            {"CUSTOMENV" "foobar"})
                                                           scripting-container)}
          config (assoc-in (jruby-testutils/jruby-tk-config
                            (jruby-testutils/jruby-config
                             {:max-active-instances 1
                              :max-requests-per-instance 10
                              :borrow-timeout default-borrow-timeout}))
                           [:jruby :lifecycle] lifecycle-fns)]
      (tk-testutils/with-app-with-config
       app
       [jruby/jruby-pooled-service]
       config
       (let [jruby-service (tk-app/get-service app :JRubyService)
             context (tk-services/service-context jruby-service)
             pool-context (:pool-context context)]
         ;; set a ruby constant in each instance so that we can recognize them
         (is (true? (set-constants-and-verify pool-context 1)))

         (let [instance (jruby-protocol/borrow-instance jruby-service
                                                        :initialize-environment-variables-test)
               scripting-container (:scripting-container instance)
               jruby-env (.runScriptlet scripting-container "ENV")]
           (is (= {"CUSTOMENV" "foobar"} jruby-env))
           (jruby-protocol/return-instance jruby-service instance
                                           :initialize-environment-variables-test)))))))
