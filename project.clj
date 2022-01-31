(defproject puppetlabs/jruby-utils "3.2.3-SNAPSHOT"
  :description "A library for working with JRuby"
  :url "https://github.com/puppetlabs/jruby-utils"
  :license {:name "Apache License, Version 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0"}

  :min-lein-version "2.9.1"
  :parent-project {:coords [puppetlabs/clj-parent "4.6.20"]
                   :inherit [:managed-dependencies]}

  :pedantic? :abort

  :source-paths ["src/clj"]
  :java-source-paths ["src/java"]
  :test-paths ["test/unit" "test/integration"]

  :dependencies [[org.clojure/clojure]
                 [org.clojure/java.jmx]
                 [org.clojure/tools.logging]

                 [clj-commons/fs]
                 [prismatic/schema]
                 [slingshot]

                 [puppetlabs/jruby-deps "9.2.17.0-2"]

                 [puppetlabs/i18n]
                 [puppetlabs/kitchensink]
                 [puppetlabs/trapperkeeper]
                 [puppetlabs/ring-middleware]]

  :deploy-repositories [["releases" {:url "https://clojars.org/repo"
                                     :username :env/clojars_jenkins_username
                                     :password :env/clojars_jenkins_password
                                     :sign-releases false}]]

  ;; By declaring a classifier here and a corresponding profile below we'll get an additional jar
  ;; during `lein jar` that has all the code in the test/ directory. Downstream projects can then
  ;; depend on this test jar using a :classifier in their :dependencies to reuse the test utility
  ;; code that we have.
  :classifiers [["test" :testutils]]

  :profiles {:dev {:dependencies  [[puppetlabs/kitchensink :classifier "test" :scope "test"]
                                   [puppetlabs/trapperkeeper :classifier "test" :scope "test"]
                                   [org.bouncycastle/bcpkix-jdk15on]
                                   [org.tcrawley/dynapath]]
                   :jvm-opts ["-Djruby.logger.class=com.puppetlabs.jruby_utils.jruby.Slf4jLogger"
                              "-Xms1G"
                              "-Xmx2G"]}
             :testutils {:source-paths ^:replace ["test/unit" "test/integration"]}}

  :plugins [[lein-parent "0.3.7"]
            [puppetlabs/i18n "0.8.0" :hooks false]])
