#!/bin/bash

set -x
set -e

lein version

echo "Running tests"
lein -U test
