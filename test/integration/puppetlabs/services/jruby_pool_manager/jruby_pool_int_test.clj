(ns puppetlabs.services.jruby-pool-manager.jruby-pool-int-test
  (:require [clojure.test :refer :all]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as tk-bootstrap]
            [puppetlabs.services.jruby-pool-manager.jruby-testutils :as jruby-testutils]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.services.jruby-pool-manager.jruby-pool-manager-service :as pool-manager]
            [puppetlabs.services.protocols.pool-manager :as pool-manager-protocol]
            [puppetlabs.services.jruby-pool-manager.jruby-core :as jruby-core]
            [puppetlabs.services.jruby-pool-manager.jruby-schemas :as jruby-schemas]
            [puppetlabs.services.jruby-pool-manager.impl.jruby-agents :as jruby-agents]
            [puppetlabs.services.jruby-pool-manager.impl.jruby-internal :as jruby-internal]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Utilities

(def test-borrow-timeout 180000)

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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Tests

(deftest ^:integration flush-pool-test
  (testing "Flushing the pool results in all new JRubyInstances"
    (jruby-testutils/with-pool-context
     pool-context
     [pool-manager/jruby-pool-manager-service]
     (jruby-testutils/jruby-config {:max-active-instances 4
                                    :borrow-timeout test-borrow-timeout})
     ;; set a ruby constant in each instance so that we can recognize them
     (is (true? (set-constants-and-verify pool-context 4)))
     (jruby-core/flush-pool! pool-context)
     (is (jruby-testutils/timed-await (jruby-agents/get-modify-instance-agent
                                       pool-context))
         (str "timed out waiting for the flush to complete, stack:\n"
              (get-all-stack-traces-as-str)))
     ;; now the pool is flushed, so the constants should be cleared
     (is (true? (verify-no-constants pool-context 4))))))

(deftest ^:integration flush-pool-for-shutdown-test
  (testing "Flushing the pool for shutdown results in no JRubyInstances left"
    (tk-bootstrap/with-app-with-config
     app
     [pool-manager/jruby-pool-manager-service]
     {}
     (let [config (jruby-testutils/jruby-config {:max-active-instances 4
                                                 :borrow-timeout test-borrow-timeout})
           pool-manager-service (tk-app/get-service app :PoolManagerService)
           pool-context (pool-manager-protocol/create-pool pool-manager-service config)
           pool-state (jruby-internal/get-pool-state pool-context)
           pool (:pool pool-state)]
       ;; wait for all jrubies to be added to the pool
       (jruby-testutils/wait-for-jrubies-from-pool-context pool-context)
       (is (= 4 (.size (jruby-core/get-pool pool-context))))
       (jruby-core/flush-pool-for-shutdown! pool-context)

       ;; flushing the pool should remove all JRubyInstances
       (is (= 0 (.size pool)))
       ;; any borrows should now return shutdown poison pill
       (is (jruby-schemas/shutdown-poison-pill? (.borrowItem pool)))))))

(deftest ^:integration hold-file-handle-on-instance-while-another-is-flushed-test
  (testing "file handle opened from one pool instance is held after other jrubies are destoyed"
    (jruby-testutils/with-pool-context
     pool-context
     jruby-testutils/default-services
     (jruby-testutils/jruby-config {:max-active-instances 4
                                    :max-borrows-per-instance 10
                                    :borrow-timeout test-borrow-timeout})
     ;; set a ruby constant in each instance so that we can recognize them
     (is (true? (set-constants-and-verify pool-context 4)))
     ;; borrow an instance and hold the reference to it.
     (let [instance (jruby-core/borrow-from-pool-with-timeout
                     pool-context
                     :hold-file-handle-while-another-instance-is-flushed
                     [])
           sc (:scripting-container instance)]
       (.runScriptlet sc
                      (str "require 'tempfile'\n\n"
                           "$unique_file = "
                           "Tempfile.new"
                           "('hold-instance-test-', './target')"))
       (try
         ; After this, the next borrow and return will trigger a flush
         (jruby-testutils/borrow-until-desired-borrow-count pool-context 9)
         (let [instance-to-flush (jruby-core/borrow-from-pool pool-context :instance-to-flush [])]
           (is (= 9 (:borrow-count (jruby-core/get-instance-state instance-to-flush))))
           (jruby-core/return-to-pool instance-to-flush :instance-to-flush []))

         (is (nil? (.runScriptlet sc "$unique_file.close"))
             "Unexpected response on attempt to close unique file")
         (finally
           (.runScriptlet sc "$unique_file.unlink")))
       ;; return the instance
       (jruby-core/return-to-pool instance :hold-file-handle-while-another-instance-is-flushed [])
       ;; Show that the instance-to-flush instance did actually get flushed
       (check-jrubies-for-constant-counts pool-context 3 1)))))

(deftest ^:integration max-borrows-flush-while-pool-flush-in-progress-test
  (testing "hitting max-borrows while flush in progress doesn't interfere with flush"
    (jruby-testutils/with-pool-context
     pool-context
     jruby-testutils/default-services
     (jruby-testutils/jruby-config {:max-active-instances 4
                                    :max-borrows-per-instance 10
                                    :splay-instance-flush false
                                    :borrow-timeout
                                    test-borrow-timeout})
     (let [pool (jruby-core/get-pool pool-context)]
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
         (is (true? (jruby-testutils/borrow-until-desired-borrow-count pool-context 9)))
         ;; now we grab a reference to that instance and hold onto it for later.
         (let [instance2 (jruby-core/borrow-from-pool-with-timeout
                          pool-context
                          :max-borrows-flush-while-pool-flush-in-progress-test
                          [])]
           (is (= 9 (:borrow-count (jruby-core/get-instance-state instance2))))
           (is (= 2 (jruby-core/free-instance-count pool)))

           ; Just to show that the pool is not locked yet
           (is (not (.isLocked pool)))
           ;; trigger a flush asynchronously
           (let [flush-future (future (jruby-core/flush-pool! pool-context))]
             ;; Once the lock is held this means that the flush is waiting
             ;; for all the instances to be returned before continuing
             (is (jruby-testutils/wait-for-pool-to-be-locked pool))

             ;; now we're going to return instance2 to the pool.  This should cause it
             ;; to get flushed. The main pool flush operation is still blocked.
             (jruby-core/return-to-pool instance2
                                        :max-borrows-flush-while-pool-flush-in-progress-test
                                        [])
             ;; Wait until instance2 is returned
             (is (jruby-testutils/wait-for-instances pool 3) "Timed out waiting for instance2 to return to pool")

             ;; and finally, we return the last instance we borrowed to the pool
             (jruby-core/return-to-pool instance1
                                        :max-borrows-flush-while-pool-flush-in-progress-test
                                        [])

             ;; wait until the flush is complete
             (is (deref flush-future 10000 false))
             (is (not (.isLocked pool)))

             (is (jruby-testutils/wait-for-instances pool 4) "Timed out waiting for the flush to finish"))))

       ;; we should have 4 fresh instances without the constant.
       (is (true? (verify-no-constants pool-context 4)))

       ;; The jruby return instance calls done within the previous
       ;; check jrubies call may cause an instance to be in the process of
       ;; being flushed when the server is shut down.  This ensures that
       ;; the flushing is all done before the server is shut down - since
       ;; that could otherwise cause an annoying error message about the
       ;; pool not being full at shut down to be displayed.
       (jruby-testutils/timed-await (jruby-agents/get-modify-instance-agent pool-context))))))

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
      (jruby-testutils/with-pool-context
       pool-context
       jruby-testutils/default-services
       config
       (let [instance (jruby-core/borrow-from-pool-with-timeout
                       pool-context
                       :initialization-and-cleanup-hooks-test
                       [])]
         (is (= "FOO" (deref (:foo instance))))
         (jruby-core/return-to-pool instance :initialization-and-cleanup-hooks-test [])

         (jruby-core/flush-pool! pool-context)
         ; wait until the flush is complete
         (is (jruby-testutils/timed-await (jruby-agents/get-modify-instance-agent pool-context)))
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
      (jruby-testutils/with-pool-context
       pool-context
       jruby-testutils/default-services
       config
       (let [instance (jruby-core/borrow-from-pool-with-timeout
                       pool-context
                       :initialize-environment-variables-test
                       [])
             scripting-container (:scripting-container instance)
             jruby-env (.runScriptlet scripting-container "ENV")]
         (.remove jruby-env "RUBY")
         (is (= {"CUSTOMENV" "foobar"} jruby-env))
         (jruby-core/return-to-pool
          instance
          :initialize-environment-variables-test
          []))))))
