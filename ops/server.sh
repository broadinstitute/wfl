#!/bin/bash -ex

declare -r WFL=${0%/*}

trap 'kill 0' EXIT

test "$1" && export WFL_DEPLOY_ENVIRONMENT="$1"

npm run serve --prefix=derived/ui -- --port 8080 &

pushd api
export _JAVA_OPTIONS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005"
"./wfl" server 3000 &
popd

declare OPEN=open
test CharlesDarwin = Charles$(uname) || OPEN=xdg-open

$OPEN http://localhost:8080/

wait
