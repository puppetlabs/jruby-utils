#!/bin/bash

set -e

echo "Running tests with default JRuby (1.7-based)"
lein2 test

echo "Running tests with JRuby 9k"
lein2 with-profile +jruby9k test
