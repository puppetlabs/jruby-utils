(ns puppetlabs.services.jruby.jruby-pool-int-test
  (:require [clojure.test :refer :all]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as tk-bootstrap]
            [puppetlabs.services.jruby.jruby-testutils :as jruby-testutils]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.services.jruby.jruby-pool-manager-service :as pool-manager]
            [puppetlabs.services.protocols.pool-manager :as pool-manager-protocol]
            [puppetlabs.services.jruby.jruby-core :as jruby-core]
            [puppetlabs.services.jruby.jruby-schemas :as jruby-schemas]
            [puppetlabs.services.jruby.jruby-agents :as jruby-agents]
            [puppetlabs.services.jruby.jruby-internal :as jruby-internal]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Utilities

(def test-borrow-timeout 180000)

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
  [pool-context]
  (let [max-new-pool-wait-count 100000]
    ;; borrow until we get an instance that doesn't have a constant,
    ;; so we'll know that the new pool is online
    (loop [instance (jruby-core/borrow-from-pool-with-timeout pool-context :wait-for-new-pool [])
           loop-count 0]
      (let [has-constant? (constant-defined? instance)]
         ;; Where a max-borrows-per-instance may have been imposed for the
         ;; pool, we need to avoid having borrow counts increase on each of
         ;; the pool instances while waiting for the new pool to show up.
         ;; Otherwise, an individual instance might be flushed and
         ;; misinterpreted as the new pool having been swapped in when the
         ;; 'old' one is actually still in use.  Artificially decrement the
         ;; borrow count here to compensate for the increase that the jruby
         ;; pool would normally do when returning an instance.
        (swap! (jruby-internal/get-instance-state-container instance) update-in [:borrow-count] dec)
        (jruby-core/return-to-pool instance :wait-for-new-pool [])
        (cond
          (not has-constant?) true
          (= loop-count max-new-pool-wait-count) false
          :else (recur (jruby-core/borrow-from-pool-with-timeout
                        pool-context
                        :wait-for-new-pool
                        [])
                       (inc loop-count)))))))

(defn borrow-until-desired-borrow-count
  [pool-context desired-borrow-count]
  (let [max-borrow-wait-count 100000]
    (loop [instance (jruby-core/borrow-from-pool-with-timeout
                     pool-context
                     :borrow-until-desired-borrow-count
                     [])
           loop-count 0]
      (let [borrow-count (:borrow-count (jruby-core/instance-state instance))]
        (jruby-core/return-to-pool instance :borrow-until-desired-borrow-count [])
        (cond
          (= (inc borrow-count) desired-borrow-count) true
          (= loop-count max-borrow-wait-count) false
          :else (recur (jruby-core/borrow-from-pool-with-timeout
                        pool-context
                        :borrow-until-desired-borrow-count
                        [])
                       (inc loop-count)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Tests

(deftest ^:integration flush-pool-test
  (testing "Flushing the pool results in all new JRubyInstances"
    (tk-bootstrap/with-app-with-config
     app
     [pool-manager/jruby-pool-manager-service]
     {}
     (let [config (jruby-testutils/jruby-config {:max-active-instances 4
                                                 :borrow-timeout test-borrow-timeout})
           pool-manager-service (tk-app/get-service app :PoolManagerService)
           pool-context (pool-manager-protocol/create-pool pool-manager-service config)]
       ;; set a ruby constant in each instance so that we can recognize them
       (is (true? (set-constants-and-verify pool-context 4)))
       (jruby-core/flush-pool! pool-context)
       (is (true? (timed-await (jruby-agents/get-pool-agent pool-context)))
           (str "timed out waiting for the flush to complete, stack:\n"
                (get-all-stack-traces-as-str)))
       ;; now the pool is flushed, so the constants should be cleared
       (is (true? (verify-no-constants pool-context 4)))))))

(deftest ^:integration flush-pool-for-shutdown-test
  (testing "Flushing the pool for shutdown results in no JRubyInstances left"
    (tk-bootstrap/with-app-with-config
     app
     [pool-manager/jruby-pool-manager-service]
     {}
     (let [config (jruby-testutils/jruby-config {:max-active-instances 4
                                                 :borrow-timeout test-borrow-timeout})
           pool-manager-service (tk-app/get-service app :PoolManagerService)
           pool-context (pool-manager-protocol/create-pool pool-manager-service config)]
       ;; wait for all jrubies to be added to the pool
       (jruby-testutils/wait-for-jrubies-from-pool-context pool-context)
       (is (= 4 (.size (jruby-core/get-pool pool-context))))
       (jruby-core/flush-pool-for-shutdown! pool-context)
       (let [flushed-pool (jruby-core/get-pool pool-context)]
         ;; flushing the pool removes all JRubyInstances but causes a ShutdownPoisonPill
         ;; to be added
         (is (= 1 (.size flushed-pool)))
         (is (jruby-schemas/shutdown-poison-pill? (.borrowItem flushed-pool))))))))

(deftest ^:integration hold-instance-while-pool-flush-in-progress-test
  (testing "instance borrowed from old pool before pool flush begins and returned *after* new pool is available"
    (tk-bootstrap/with-app-with-config
     app
     jruby-testutils/default-services
     {}
     (let [config (jruby-testutils/jruby-config {:max-active-instances 4
                                                 :borrow-timeout test-borrow-timeout})
           pool-manager-service (tk-app/get-service app :PoolManagerService)
           pool-context (pool-manager-protocol/create-pool pool-manager-service config)]
       ;; set a ruby constant in each instance so that we can recognize them
       (is (true? (set-constants-and-verify pool-context 4)))
       ;; borrow an instance and hold the reference to it.
       (let [instance (jruby-core/borrow-from-pool-with-timeout
                       pool-context
                       :hold-instance-while-pool-flush-in-progress-test
                       [])]
         ;; trigger a flush
         (jruby-core/flush-pool! pool-context)
         ;; wait for the new pool to become available
         (is (true? (wait-for-new-pool pool-context)))
         ;; return the instance
         (jruby-core/return-to-pool instance :hold-instance-while-pool-flush-in-progress-test [])
         ;; wait until the flush is complete
         (is (true? (timed-await (jruby-agents/get-pool-agent pool-context)))
             (str "timed out waiting for the flush to complete, stack:\n"
                  (get-all-stack-traces-as-str))))
       ;; now the pool is flushed, and the constants should be cleared
       (is (true? (verify-no-constants pool-context 4)))))))

(deftest ^:integration hold-file-handle-on-instance-while-pool-flush-in-progress-test
  (testing "file handle opened from old pool instance is held open across pool flush"
    (tk-bootstrap/with-app-with-config
     app
     jruby-testutils/default-services
     {}
     (let [config (jruby-testutils/jruby-config {:max-active-instances 4
                                                 :borrow-timeout test-borrow-timeout})
           pool-manager-service (tk-app/get-service app :PoolManagerService)
           pool-context (pool-manager-protocol/create-pool pool-manager-service config)]
       ;; set a ruby constant in each instance so that we can recognize them
       (is (true? (set-constants-and-verify pool-context 2)))
       ;; borrow an instance and hold the reference to it.
       (let [instance (jruby-core/borrow-from-pool-with-timeout
                       pool-context
                       :hold-instance-while-pool-flush-in-progress-test
                       [])
             sc (:scripting-container instance)]
         (.runScriptlet sc
                        (str "require 'tempfile'\n\n"
                             "$unique_file = "
                             "Tempfile.new"
                             "('hold-instance-test-', './target')"))
         (try
           ;; trigger a flush
           (jruby-core/flush-pool! pool-context)
           ;; wait for the new pool to become available
           (is (true? (wait-for-new-pool pool-context)))

           (is (nil? (.runScriptlet sc "$unique_file.close"))
               "Unexpected response on attempt to close unique file")
           (finally
             (.runScriptlet sc "$unique_file.unlink")))
         ;; return the instance
         (jruby-core/return-to-pool instance :hold-instance-while-pool-flush-in-progress-test [])
         ;; wait until the flush is complete
         (is (true? (timed-await (jruby-agents/get-pool-agent pool-context)))
             (str "timed out waiting for the flush to complete, stack:\n"
                  (get-all-stack-traces-as-str))))
       ;; now the pool is flushed, and the constants should be cleared
       (is (true? (verify-no-constants pool-context 2)))))))

(deftest ^:integration max-borrows-flush-while-pool-flush-in-progress-test
  (testing "instance from new pool hits max-borrows while flush in progress"
    (tk-bootstrap/with-app-with-config
     app
     jruby-testutils/default-services
     {}
     (let [config (jruby-testutils/jruby-config {:max-active-instances 4
                                                 :max-borrows-per-instance 10
                                                 :borrow-timeout
                                                 test-borrow-timeout})
           pool-manager-service (tk-app/get-service app :PoolManagerService)
           pool-context (pool-manager-protocol/create-pool pool-manager-service config)
           pool-agent (jruby-agents/get-pool-agent pool-context)]
       ;; set a ruby constant in each instance so that we can recognize them.
       ;; this counts as one request for each instance.
       (is (true? (set-constants-and-verify pool-context 4)))

       ;; borrow one instance and hold the reference to it, to prevent
       ;; the flush operation from completing
       (let [instance1 (jruby-core/borrow-from-pool-with-timeout
                        pool-context
                        :max-borrows-flush-while-pool-flush-in-progress-test
                        [])]
         ;; we are going to borrow and return a second instance until we get its
         ;; request count up to max-borrows - 1, so that we can use it to test
         ;; flushing behavior the next time we return it.
         (is (true? (borrow-until-desired-borrow-count pool-context 9)))
         ;; now we grab a reference to that instance and hold onto it for later.
         (let [instance2 (jruby-core/borrow-from-pool-with-timeout
                          pool-context
                          :max-borrows-flush-while-pool-flush-in-progress-test
                          [])]
           (is (= 9 (:borrow-count (jruby-core/instance-state instance2))))

           ;; trigger a flush
           (jruby-core/flush-pool! pool-context)
           ;; wait for the new pool to become available
           (is (true? (wait-for-new-pool pool-context)))
           ;; there will only be two instances in the new pool, because we are holding
           ;; references to two from the old pool.
           (is (true? (set-constants-and-verify pool-context 2)))
           ;; borrow and return instance from the new pool until an instance flush is triggered
           (is (true? (borrow-until-desired-borrow-count
                       pool-context
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
           (jruby-core/return-to-pool instance2
                                      :max-borrows-flush-while-pool-flush-in-progress-test
                                      [])
           (is (true? (check-jrubies-for-constant-counts pool-context 2 1))))

         ;; now we'll set the ruby constant on the 3 instances in the new pool
         (is (true? (set-constants-and-verify pool-context 3)))

         ;; The flush should still not have completed at this point because
         ;; the last instance from the old pool is still being borrowed.
         ;; Wait for some arbitrary (but short) period of time to see that
         ;; that the agent is still busy - presumably handling the flush.
         (is (false? (await-for 50 pool-agent)))

         ;; and finally, we return the last instance from the old pool
         (jruby-core/return-to-pool instance1
                                    :max-borrows-flush-while-pool-flush-in-progress-test
                                    [])

         ;; wait until the flush is complete
         (is (true? (timed-await (jruby-agents/get-pool-agent pool-context)))
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
       (timed-await (jruby-agents/get-flush-instance-agent pool-context))))))

(deftest initialization-and-cleanup-hooks-test
  (testing "custom initialization and cleanup callbacks get called appropriately"
    (let [foo-atom (atom "FOO")
          lifecycle-fns {:initialize-pool-instance (fn [instance] (assoc instance :foo foo-atom))
                         :cleanup (fn [instance] (reset! (:foo instance) "Terminating FOO"))}
          config (jruby-testutils/jruby-config
                  {:max-active-instances 1
                   :max-borrows-per-instance 10
                   :borrow-timeout test-borrow-timeout
                   :lifecycle lifecycle-fns})]
      (tk-bootstrap/with-app-with-config
       app
       jruby-testutils/default-services
       {}
       (let [pool-manager-service (tk-app/get-service app :PoolManagerService)
             pool-context (pool-manager-protocol/create-pool pool-manager-service config)
             instance (jruby-core/borrow-from-pool-with-timeout
                       pool-context
                       :initialization-and-cleanup-hooks-test
                       [])]
         (is (= "FOO" (deref (:foo instance))))
         (jruby-core/return-to-pool instance :initialization-and-cleanup-hooks-test [])

         (jruby-core/flush-pool! pool-context)
         ; wait until the flush is complete
         (await (jruby-agents/get-pool-agent pool-context))
         (is (= "Terminating FOO" (deref foo-atom))))))))

(deftest initialize-scripting-container-hook-test
  (testing "can set custom environment variables via :initialize-scripting-container hook"
    (let [lifecycle-fns {:initialize-scripting-container (fn [scripting-container {}]
                                                           (.setEnvironment scripting-container
                                                                            {"CUSTOMENV" "foobar"})
                                                           scripting-container)}
          config (jruby-testutils/jruby-config
                  {:max-active-instances 1
                   :max-borrows-per-instance 10
                   :borrow-timeout test-borrow-timeout
                   :lifecycle lifecycle-fns})]
      (tk-bootstrap/with-app-with-config
       app
       jruby-testutils/default-services
       {}
       (let [pool-manager-service (tk-app/get-service app :PoolManagerService)
             pool-context (pool-manager-protocol/create-pool pool-manager-service config)]

         (let [instance (jruby-core/borrow-from-pool-with-timeout
                         pool-context
                         :initialize-environment-variables-test
                         [])
               scripting-container (:scripting-container instance)
               jruby-env (.runScriptlet scripting-container "ENV")]
           (is (= {"CUSTOMENV" "foobar"} jruby-env))
           (jruby-core/return-to-pool
            instance
            :initialize-environment-variables-test
            [])))))))
