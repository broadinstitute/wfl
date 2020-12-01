#!/usr/bin/env bash

set -ex

# get vault token using role-id and secret-id
VAULT_TOKEN=$(curl \
    --request POST \
    --data "{\"role_id\":\"${ROLE_ID}\",\"secret_id\":\"${SECRET_ID}\"}" \
    ${VAULT_ADDR}/v1/auth/approle/login | jq -r .auth.client_token)
if [ -z "${VAULT_TOKEN}" ] ; then
    echo "Vault authentication failed!"
    exit 1
fi

echo ::add-mask::${VAULT_TOKEN}
echo "VAULT_TOKEN=${VAULT_TOKEN}" >> $GITHUB_ENV
echo ${VAULT_TOKEN} > ~/.vault-token

# use vault token to read secret - service account json
curl --silent -H "X-Vault-Token: ${VAULT_TOKEN}" -X GET \
    ${VAULT_ADDR}/v1/secret/dsde/gotc/dev/wfl/wfl-non-prod-service-account.json \
    | jq .data > sa.json
if [ ! -s sa.json ] ; then
    echo "Retrieval of Gcloud SA credentials failed"
    exit 1
fi

# auth as service account
gcloud auth activate-service-account --key-file=sa.json
if [ $? -ne 0 ] ; then
    echo "Gcloud auth failed!"
    exit 1
fi

# get bearer token and set it to a specific env var that
# subsequent steps expect.  bearer token good for 1 hour
GOOGLE_OAUTH_ACCESS_TOKEN=$(gcloud auth print-access-token)
if [ -z "${GOOGLE_OAUTH_ACCESS_TOKEN}" ] ; then
    echo "Generating Gcloud access token failed"
    exit 1
fi

echo ::add-mask::${GOOGLE_OAUTH_ACCESS_TOKEN}
echo "GOOGLE_OAUTH_ACCESS_TOKEN=${GOOGLE_OAUTH_ACCESS_TOKEN}" >> $GITHUB_ENV
echo "GOOGLE_APPLICATION_CREDENTIALS=$(find `pwd` -maxdepth 1 -name sa.json)" >> $GITHUB_ENV
