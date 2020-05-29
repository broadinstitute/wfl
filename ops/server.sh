#!/bin/bash -ex

declare -r WFL=${0%/*}

trap 'kill 0' EXIT

export ZERO_DEPLOY_ENVIRONMENT=debug

npm run serve --prefix=ui -- --port 8080 &

"${WFL:-.}/../wfl" server 3000 &

wait
