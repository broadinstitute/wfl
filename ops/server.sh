#!/bin/bash -ex

declare -r WFL=${0%/*}

trap 'kill 0' EXIT

test "$1" && export ZERO_DEPLOY_ENVIRONMENT="$1"

npm run serve --prefix=derived/ui -- --port 8080 &

pushd api
"${WFL:-.}/../wfl" server 3000 &

declare OPEN=open
test CharlesDarwin = Charles$(uname) || OPEN=xdg-open

$OPEN http://localhost:8080/

wait
