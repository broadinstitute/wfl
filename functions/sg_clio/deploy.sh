# Deploy the submit_sg_workload cloud function
# usage: bash deploy.sh TRIGGER_BUCKET_NAME
# ex: bash deploy.sh broad-gotc-prod-storage

TRIGGER_BUCKET_NAME=${1}
TRIGGER_EVENT=${2:-"google.storage.object.finalize"}
REGION=${3:-"us-central1"}

# gotc-prod
if [ "${TRIGGER_BUCKET_NAME}" == "broad-gotc-prod-storage" ]; then
    exit 1
    # This is a bad idea despite the fact that there's no holes in this config, we need to talk about it first
    GCLOUD_PROJECT="broad-gotc-prod-storage"
    SA_EMAIL="picard-prod@broad-gotc-prod.iam.gserviceaccount.com"
    _CLIO_URL="https://clio.gotc-prod.broadinstitute.org"
# gotc-dev
elif [ "${TRIGGER_BUCKET_NAME}" == "???" ]; then
    GCLOUD_PROJECT="broad-gotc-dev-storage"
    SA_EMAIL="picard-dev@broad-gotc-dev.iam.gserviceaccount.com"
    _CLIO_URL="https://clio.gotc-dev.broadinstitute.org"
# gotc-dev -- Jack's sandbox
elif [ "${TRIGGER_BUCKET_NAME}" == "jwarren-wfl-outputs" ]; then
    GCLOUD_PROJECT="broad-gotc-dev-storage"
    SA_EMAIL="sg-clio-fn-non-prod@broad-gotc-dev-storage.iam.gserviceaccount.com"
    # SA can't talk to Clio but whatever, useful for testing
    _CLIO_URL="https://clio.gotc-dev.broadinstitute.org"
else
    printf "Unrecognized google project\n"
    exit 1
fi

gcloud config set project ${GCLOUD_PROJECT}
gcloud functions deploy sg_update_clio \
    --region ${REGION} \
    --trigger-resource ${TRIGGER_BUCKET_NAME} \
    --trigger-event ${TRIGGER_EVENT} \
    --service-account ${SA_EMAIL} \
    --set-env-vars CLIO_URL=${_CLIO_URL} \
    --runtime python37 \
    --memory 128MB \
    --retry
