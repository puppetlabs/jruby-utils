#!/bin/bash

set -x
set -e

lein version

echo "Running tests with default JRuby (1.7-based)"
lein -U test

echo "Running tests with JRuby 9k"
lein -U with-profile +jruby9k test
