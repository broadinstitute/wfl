#!/usr/bin/env bash

set -ex

declare -r WFL=${0%/*}

trap 'kill 0' EXIT

test "$1" && export WFL_DEPLOY_ENVIRONMENT="$1"

npm run serve --prefix=derived/ui -- --port 8080 &

pushd api
clojure -M:liquibase
export _JAVA_OPTIONS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005"
clojure -M:wfl server 3000 &
popd

wait
