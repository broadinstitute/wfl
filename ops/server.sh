#!/bin/bash -ex

ZERO=${0%/*}

trap 'kill 0' EXIT

# Spin up the NPM server for UI
npm run serve --prefix=ui -- --port 8080 &

# Spin up the API server
"${ZERO:-.}/../zero" server gotc-dev 3000 &

wait
