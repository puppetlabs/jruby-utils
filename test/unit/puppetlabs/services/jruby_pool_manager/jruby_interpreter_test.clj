(ns puppetlabs.services.jruby-pool-manager.jruby-interpreter-test
  (:require [clojure.test :refer :all]
            [puppetlabs.services.jruby-pool-manager.jruby-testutils :as jruby-testutils]
            [puppetlabs.services.jruby-pool-manager.jruby-core :as jruby-core]))

(deftest jruby-env-vars
  (testing "the environment used by the JRuby interpreters"
    (jruby-testutils/with-scripting-container
     jruby-interpreter
     (jruby-testutils/jruby-config)
     (let [jruby-env (.runScriptlet jruby-interpreter "ENV")]
       ;; $HOME and $PATH are left in by `jruby-env`
       ;; Note that other environment variables are allowed through (e.g.
       ;; `HTTP_PROXY` - see jruby-core/env-vars-allowed-list for the full list),
       ;; but are not expected to be set in most environments. However, in
       ;; order to make this more test robust, these variables are always
       ;; filtered out.
       (is (= #{"HOME" "PATH" "GEM_HOME" "JARS_NO_REQUIRE" "JARS_REQUIRE" "RUBY"}
              (set (remove (set jruby-core/proxy-vars-allowed-list) (keys jruby-env)))))))))

(deftest jruby-configured-env-vars
  (testing "the environment used by the JRuby interpreters can be added to via the config"
    (jruby-testutils/with-scripting-container
     jruby-interpreter
     (jruby-testutils/jruby-config {:environment-vars {:FOO "for_jruby"}})
     (let [jruby-env (.runScriptlet jruby-interpreter "ENV")]
       ;; Note that other environment variables are allowed through,
       ;; but are not expected to be set in most environments. However, in
       ;; order to make this test more robust, these variables are always
       ;; filtered out.
       (is (= #{"HOME" "PATH" "GEM_HOME" "JARS_NO_REQUIRE" "JARS_REQUIRE" "FOO" "RUBY"}
              (set (remove (set jruby-core/proxy-vars-allowed-list) (keys jruby-env)))))
       (is (= (.get jruby-env "FOO") "for_jruby"))))))
