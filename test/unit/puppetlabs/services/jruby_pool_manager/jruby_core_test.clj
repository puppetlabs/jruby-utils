(ns puppetlabs.services.jruby-pool-manager.jruby-core-test
  (:require [clojure.test :refer :all]
            [schema.test :as schema-test]
            [puppetlabs.services.jruby-pool-manager.jruby-core :as jruby-core]
            [puppetlabs.trapperkeeper.testutils.logging :as logutils]
            [puppetlabs.services.jruby-pool-manager.jruby-schemas :as jruby-schemas])
  (:import (java.io ByteArrayOutputStream PrintStream ByteArrayInputStream)
           (org.jruby.runtime Constants)))

(use-fixtures :once schema-test/validate-schemas)

(def min-config
  (jruby-core/initialize-config
   {:gem-home "./target/jruby-gem-home",
    :ruby-load-path ["./dev-resources/puppetlabs/services/jruby_pool_manager/jruby_core_test"]}))

(defmacro with-stdin-str
  "Evaluates body in a context in which System/in is bound to a fresh
  input stream initialized with the string s.  The return value of evaluating
  body is returned."
  [s & body]
  `(let [system-input# (System/in)
         string-input# (new ByteArrayInputStream (.getBytes ~s))]
     (try
       (System/setIn string-input#)
       ~@body
       (finally (System/setIn system-input#)))))

(defmacro capture-out
  "capture System.out and return it as the value of :out in the return map.
  The return value of body is available as :return in the return map.

  This macro is intended to be used for JRuby interop.  Please see with-out-str
  for an idiomatic clojure equivalent.

  This macro is not thread safe."
  [& body]
  `(let [return-map# (atom {})
         system-output# (System/out)
         captured-output# (new ByteArrayOutputStream)
         capturing-print-stream# (new PrintStream captured-output#)]
     (try
       (System/setOut capturing-print-stream#)
       (swap! return-map# assoc :return (do ~@body))
       (finally
         (.flush capturing-print-stream#)
         (swap! return-map# assoc :out (.toString captured-output#))
         (System/setOut system-output#)))
     @return-map#))

(deftest default-num-cpus-test
  (testing "1 jruby instance for a 1 or 2-core box"
    (is (= 1 (jruby-core/default-pool-size 1)))
    (is (= 1 (jruby-core/default-pool-size 2))))
  (testing "2 jruby instances for a 3-core box"
    (is (= 2 (jruby-core/default-pool-size 3))))
  (testing "3 jruby instances for a 4-core box"
    (is (= 3 (jruby-core/default-pool-size 4))))
  (testing "4 jruby instances for anything above 5 cores"
    (is (= 4 (jruby-core/default-pool-size 5)))
    (is (= 4 (jruby-core/default-pool-size 8)))
    (is (= 4 (jruby-core/default-pool-size 16)))
    (is (= 4 (jruby-core/default-pool-size 32)))
    (is (= 4 (jruby-core/default-pool-size 64)))))

(deftest cli-run!-error-handling-test
  (testing "when command is not found as a resource"
    (logutils/with-test-logging
      (is (nil? (jruby-core/cli-run! min-config "DNE" [])))
      (is (logged? #"DNE could not be found" :error)))))

(deftest ^:integration cli-run!-test
  (testing "jruby cli command output"
    (testing "gem env (SERVER-262)"
      (let [m (capture-out (jruby-core/cli-run! min-config "gem" ["env"]))
            {:keys [return out]} m
            exit-code (.getStatus return)]
        (is (= 0 exit-code))
        ; The choice of SHELL PATH is arbitrary, just need something to scan for
        (is (re-find #"SHELL PATH:" out))))
    (testing "gem list"
      (let [m (capture-out (jruby-core/cli-run! min-config "gem" ["list"]))
            {:keys [return out]} m
            exit-code (.getStatus return)]
        (is (= 0 exit-code))
        ; The choice of json is arbitrary, just need something to scan for
        (is (re-find #"\bjson\b" out))))
    (testing "irb"
      (let [m (capture-out
                (with-stdin-str "puts %{HELLO}"
                  (jruby-core/cli-run! min-config "irb" ["-f"])))
            {:keys [return out]} m
            exit-code (.getStatus return)]
        (is (= 0 exit-code))
        (is (re-find #"\nHELLO\n" out)))
      (let [m (capture-out
                (with-stdin-str "Kernel.exit(42)"
                  (jruby-core/cli-run! min-config "irb" ["-f"])))
            {:keys [return _]} m
            exit-code (.getStatus return)]
        (is (= 42 exit-code))))
    (testing "irb with -r foo"
      (let [m (capture-out
                (with-stdin-str "puts %{#{foo}}"
                  (jruby-core/cli-run! min-config "irb" ["-r" "foo" "-f"])))
            {:keys [return out]} m
            exit-code (.getStatus return)]
        (is (= 0 exit-code))
        (is (re-find #"bar" out))))
    (testing "non existing subcommand returns nil"
      (logutils/with-test-logging
        (is (nil? (jruby-core/cli-run! min-config "doesnotexist" [])))))))

(deftest ^:integration cli-ruby!-test
  (testing "jruby cli command output"
    ;; TODO: consider bringing the CLI clj files back into the repo? (TK-378)
    (testing "ruby -r puppet"
      (let [m (capture-out
                (with-stdin-str "puts %{#{foo}}"
                  (jruby-core/cli-ruby! min-config ["-r" "foo"])))
            {:keys [return out]} m
            exit-code (.getStatus return)]
        (is (= 0 exit-code))
        (is (re-find #"bar" out))))))

(deftest default-jruby-compat-version-test
  (testing "default jruby compat version is correct for current JRuby"
    (is (= (if jruby-schemas/using-jruby-9k?
             Constants/RUBY_VERSION
             "1.9")
           jruby-core/default-jruby-compat-version))))

(deftest get-compat-version-for-jruby-config
  (testing "For get-compat-version-for-jruby-config"
    (testing "deprecation warning logged if compat version not configurable"
      (logutils/with-test-logging
       (is (= "2.3.1" (jruby-core/get-compat-version-for-jruby-config
                       "2.3.1" "2.3.1" #{"2.3.1"})))
       (is (logged? #"Setting compat-version for JRuby 9k is deprecated" :warn))))
    (testing "when unsupported version specified"
      (logutils/with-test-logging
       (testing "supported version returned"
         (is (= "2.3.1" (jruby-core/get-compat-version-for-jruby-config
                         "1.9" "2.3.1" #{"2.3.1"}))))
       (testing "warning is logged"
         (is (logged?
              #"compat-version is set to `1.9`, which is not a supported version. Version `2.3.1` will be used instead"
              :warn)))))
    (testing "supported version is returned"
      (is (= "2.0" (jruby-core/get-compat-version-for-jruby-config
                    "2.0" "1.9" #{"1.9" "2.0"}))))
    (testing "stringified form of supported version is returned"
      (is (= "2.0" (jruby-core/get-compat-version-for-jruby-config
                    2.0 "1.9" #{"1.9" "2.0"}))))
    (testing "default version returned if no version specified"
      (is (= "1.9" (jruby-core/get-compat-version-for-jruby-config
                    nil
                    "1.9"
                    #{"1.9" "2.0"}))))))
