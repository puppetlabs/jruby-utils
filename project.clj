(defproject puppetlabs/jruby-utils "0.8.0"
  :description "A library for working with JRuby"
  :url "https://github.com/puppetlabs/jruby-utils"
  :license {:name "Apache License, Version 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0"}

  :min-lein-version "2.7.1"
  :parent-project {:coords [puppetlabs/clj-parent "0.3.3"]
                   :inherit [:managed-dependencies]}

  :pedantic? :abort

  :source-paths ["src/clj"]
  :java-source-paths ["src/java"]
  :test-paths ["test/unit" "test/integration"]

  :dependencies [[org.clojure/clojure]
                 [org.clojure/tools.logging]

                 [me.raynes/fs]
                 [prismatic/schema]
                 [slingshot]

                 [org.jruby/jruby-core "1.7.26"
                  :exclusions [com.github.jnr/jffi com.github.jnr/jnr-x86asm]]
                 ;; jffi and jnr-x86asm are explicit dependencies because,
                 ;; in JRuby's poms, they are defined using version ranges,
                 ;; and :pedantic? :abort won't tolerate this.
                 [com.github.jnr/jffi "1.2.12"]
                 [com.github.jnr/jffi "1.2.12" :classifier "native"]
                 [com.github.jnr/jnr-x86asm "1.0.2"]
                 ;; NOTE: jruby-stdlib packages some unexpected things inside
                 ;; of its jar; please read the detailed notes above the
                 ;; 'uberjar-exclusions' example toward the end of this file.
                 [org.jruby/jruby-stdlib "1.7.26"]

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
                                   [puppetlabs/trapperkeeper :classifier "test" :scope "test"]]
                   :jvm-opts ["-Djruby.logger.class=com.puppetlabs.jruby_utils.jruby.Slf4jLogger"]}
             :testutils {:source-paths ^:replace ["test/unit" "test/integration"]}}

  :plugins [[lein-parent "0.3.1"]
            [puppetlabs/i18n "0.6.0"]])
