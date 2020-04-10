#!/bin/bash -ex

WFL=${0%/*}

trap 'kill 0' EXIT

npm run serve --prefix=ui -- --port 8080 &

"${WFL:-.}/../wfl" server gotc-dev 3000 &

wait
