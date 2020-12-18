# Deploy the submit_sg_workload cloud function
# usage: bash deploy.sh TRIGGER_BUCKET_NAME
# ex: bash deploy.sh broad-gotc-prod-storage

TRIGGER_BUCKET_NAME=${1}
TRIGGER_EVENT=${2:-"google.storage.object.finalize"}
REGION=${3:-"us-central1"}

if [ "${TRIGGER_BUCKET_NAME}" == "broad-gotc-prod-storage" ]; then
    GCLOUD_PROJECT="broad-gotc-prod-storage"
    SA_EMAIL="sg-submission-fn-non-prod@broad-gotc-prod-storage.iam.gserviceaccount.com"
    _PATH_PATTERN="^gs://broad-gotc-prod-storage/pipeline/[^/]+/[^/]+/unmapped/"
    _WFL_URL="https://gotc-prod-wfl.gotc-prod.broadinstitute.org"
    _CROMWELL_URL="https://cromwell-gotc-auth.gotc-prod.broadinstitute.org/"
    _WORKLOAD_PROJECT="sg-prod"
    _OUTPUT_BUCKET="gs://???"
elif [ "${TRIGGER_BUCKET_NAME}" == "jwarren-wfl-inputs" ]; then
    GCLOUD_PROJECT="broad-gotc-dev-storage"
    SA_EMAIL="sg-submission-fn-non-prod@broad-gotc-dev-storage.iam.gserviceaccount.com"
    _WFL_URL="https://dev-wfl.gotc-dev.broadinstitute.org"
    _CROMWELL_URL="https://cromwell-gotc-auth.gotc-dev.broadinstitute.org/"
    _WORKLOAD_PROJECT="sg-dev"
    _OUTPUT_BUCKET="gs://jwarren-wfl-outputs"
else
    printf "Unrecognized google project\n"
    exit 1
fi

gcloud config set project ${GCLOUD_PROJECT}
gcloud functions deploy submit_sg_workload \
    --region ${REGION} \
    --trigger-resource ${TRIGGER_BUCKET_NAME} \
    --trigger-event ${TRIGGER_EVENT} \
    --service-account ${SA_EMAIL} \
    --set-env-vars WFL_URL=${_WFL_URL},CROMWELL_URL=${_CROMWELL_URL},WORKLOAD_PROJECT=${_WORKLOAD_PROJECT},OUTPUT_BUCKET=${_OUTPUT_BUCKET} \
    --runtime python37 \
    --memory 128MB \
    --retry

if [ -n "${_PATH_PATTERN}" ]; then
    gcloud functions deploy submit_sg_workload --update-env-vars PATH_PATTERN=${_PATH_PATTERN}
fi
