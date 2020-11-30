#!/usr/bin/env bash

# https://github.com/DataBiosphere/jade-data-repo/blob/develop/docs/register-sa-with-sam.md#registering

set -e

declare -r IAM=.iam.gserviceaccount.com

usage () {
    local -r av0=$1 status=$2 ; shift 2
    local -r sa=wfl-non-prod@broad-gotc-dev.iam.gserviceaccount.com
    >&2 echo $av0: Register a service account with SAM.
    >&2 echo "Usage: $av0 dev <sa>"
    >&2 echo "Where: 'dev' is the deployment environment."
    >&2 echo "       <sa> is a service account."
    >&2 echo
    >&2 echo "Example:" $av0 dev $sa
    >&2 echo
    >&2 echo "Note:  <sa> should end in '$IAM'."
    >&2 echo
    >&2 echo 'BTW:   You ran:' $av0 "$@"
    exit $status
}

getToken () {
    local -r sa=$1
    local -r iam=https://iamcredentials.googleapis.com/v1/projects/
    local -r url=$iam-/serviceAccounts/$sa:generateAccessToken
    local -r token=$(gcloud auth print-access-token)
    local -r data='{
      "scope": [
          "https://www.googleapis.com/auth/userinfo.email",
          "https://www.googleapis.com/auth/userinfo.profile"
      ]
    }'
    curl -sH "Authorization: Bearer $token" \
         -H 'Content-Type: application/json' \
         -d "$data" "$url" |
        jq -r .accessToken
}

main () {
    local -r av0=${0##*/} env=$1 sa=$2
    local -r sam=https://sam.dsde-$env.broadinstitute.org/register/user/v1
    if test Xdev != X$env
    then
        >&2 echo $av0: Works only in the dev environment now.
        usage "$av0" 1 "$@"
    fi
    if test "${sa%$IAM}" = "$sa"
    then
        >&2 echo $av0: A service account ends in $IAM
        usage "$av0" 2 "$@"
    fi
    local -r auth="Authorization: Bearer $(getToken $sa)"
    local -r result=$(curl -X POST -H "$auth" "$sam")
    local -r status=$?
    echo "$result" | jq . || echo "$result"
    exit $status
}

main "$@"
