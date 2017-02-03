### 0.7.0 - 2017-01-03

This is a bugfix and internal improvement release.

* Remove the need for a second `JrubyPool` during pool flushes to improve
  system stability
* Fix possibility of race condition where the pool lock can be granted even
  when some jruby instances are still doing work

### 0.6.0 - 2016-12-19

This is a feature release.

* [SERVER-1475](https://tickets.puppetlabs.com/browse/SERVER-1475) Introduce
  an Slf4jLogger class for use in routing JRuby log output to slf4j.

### 0.5.0 - 2016-11-22

This is a minor feature release.

* [SERVER-377](https://tickets.puppetlabs.com/browse/SERVER-377) Allow proxy
  environment variables to pass through to scripting container.

### 0.4.1 - 2016-11-02

This is a minor release.

* Introduce a ScriptingContainer interface as an abstraction in place of a concrete
  ScriptingContainer class in various public APIs.

### 0.4.0 - 2016-10-19

This is a minor feature release.

Features:

* Introduce support for a `compat-version` config option, which allows the MRI
  compatibility version to be configured.  Only supports 1.9 or 2.0, with 1.9
  being the default if not specified.

### 0.3.0 - 2016-10-05

This is a minor feature release and maintenance release.

Features:

* Introduce support for `gem-path` config option, which will result in having the
  GEM_PATH set for the jruby intepreters.

Maintenance:

* Introduce lein-parent for managing dependency versions
* Fixes to race conditions and timeouts in tests

### 0.2.1

This is a maintenance release.

 * Bump dependencies to JRuby 1.7.26.

### 0.2.0

This is a feature release.

 * [SERVER-584](https://tickets.puppetlabs.com/browse/SERVER-584)
   Introduce a setting to allow configured environment variables to be passed
   through to the JRuby scripting container.

### 0.1.0

 * Initial release.

