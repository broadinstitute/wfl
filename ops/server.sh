#!/usr/bin/env bash

set -ex

export GOOGLE_APPLICATION_CREDENTIALS=/Users/tbl/Broad/wfl/api/wfl-prod-sa.json
export WFL_CLIO_URL=https://clio.gotc-dev.broadinstitute.org
export WFL_CROMWELL_URL=https://cromwell-gotc-auth.gotc-prod.broadinstitute.org

declare -r WFL=${0%/*}

trap 'kill 0' EXIT

npm run serve --prefix=derived/ui -- --port 8080 &

pushd api
clojure -M:liquibase
export _JAVA_OPTIONS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005"
clojure -M:wfl server 3000 &
popd

wait
