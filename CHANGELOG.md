### 1.1.0 - 2018-02-27

* (SERVER-2130) Default to jit compile-mode for jruby 9k
* (SERVER-2081) Set KCode, source encoding and external encoding to ensure
  source files are parsed as UTF-8 by default in jruby 1.7

### 1.0.0 - 2018-01-09

* (SERVER-2060) Splay JRuby instance flushing

### 0.11.2 - 2017-12-14

* (SERVER-1780) Insert hashCode into profiler file name

### 0.11.1 - 2017-12-12

* (SERVER-1874) Update to jruby 9.1.15.0

### 0.11.0 - 2017-12-11

This is a feature release

* (SERVER-1821) Bump JRuby 9k dep to 9.1.9.0-2 for ffi memory leak fix
* (SERVER-1840) Bump to JRuby 9.1.11.0-1
* (SERVER-1780) Enable jruby profiling for ScriptingContainers

### 0.10.0 - 2017-05-26

This is a breaking maintenance / minor feature release.

Maintenance:

* Bump JRuby 1.7 dependencies to 1.7.27.

Breaking changes:

* Support for configuring the Ruby language `compat-version`, including the
  related functions, has been removed in this release.
  
  The `compat-version` was only previously functional for JRuby 1.7.x
  releases since each JRuby 9k release only supports a single language
  version.  JRuby 1.7.27 effectively breaks the ability to use Ruby language
  version 2.0, however, due to a regression - see:
  https://github.com/jruby/jruby/issues/4613 - which is unlikely to be fixed
  in the future since the JRuby 1.7.x series has been declared EOL.  For
  Ruby language 2+ support, users will now have to use jruby-utils with a 
  jruby-deps 9.x.x.x version dependency.

Features:

* Added a `jruby-version-info` function for getting the version string that
  JRuby reports at the command line. 

### 0.9.1 - 2017-05-19

This is a minor release.

* Remove warning for jar deletion failure. Changes in upstream JRuby will
  remove some of the temporary jars that are copied at runtime, so the logging
  for our cleanup of these jars is not necessary.
* jruby-deps version bumps - JRuby 9k to 9.1.9.0-1 and JRuby 1.7 to 1.7.26-2.

### 0.9.0 - 2017-05-03

This is a feature release.

* [SERVER-1630](https://tickets.puppetlabs.com/browse/SERVER-1630) Added
  support for exercising JRuby 9k.  jruby-utils continues to depend upon
  JRuby 1.7.26 but jruby-utils consumers can now successfully override
  the 1.7.26 dependency with JRuby 9k-based dependencies.  JRuby dependencies
  are now derived from single upstream Maven artifact, puppetlabs/jruby-deps.
  A jruby9k profile was added for executing the jruby-utils tests under
  JRuby 9k.

### 0.8.0 - 2017-03-01

This is a minor feature and bugfix release.

* Introduce `:flush-timeout` config setting to specify how long a pool flush
  attempt will wait for jruby instances to be returned to the pool before
  aborting the attempt

* Add `lockWithTimeout` method to the `JrubyPool` class to facilitate the new
  `:flush-timeout` setting

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

