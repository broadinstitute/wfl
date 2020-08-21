# Deploy the submit_aou_workload cloud function
# usage: bash deploy.sh GCLOUD_PROJECT TRIGGER_BUCKET

GCLOUD_PROJECT=${1}
TRIGGER_BUCKET=${2}
TRIGGER_EVENT=${3:-"google.storage.object.finalize"}
REGION=${4:-"us-central1"}

if [ "${GCLOUD_PROJECT}" == "broad-gotc-dev-storage" ]; then
  # This service account must have access to the WFL API
  SA_EMAIL="aou-cloud-fn-non-prod@broad-gotc-dev-storage.iam.gserviceaccount.com"
  _WFL_URL="https://dev-wfl.gotc-dev.broadinstitute.org"
  _CROMWELL_URL="https://cromwell-gotc-auth.gotc-dev.broadinstitute.org/"
  _WFL_ENVIRONMENT="aou-dev"
elif [ "${GCLOUD_PROJECT}" == "broad-aou-storage" ]; then
  SA_EMAIL="aou-cloud-fn@broad-aou-storage.iam.gserviceaccount.com"
  _WFL_URL="https://aou-wfl.gotc-prod.broadinstitute.org"
  _CROMWELL_URL="https://cromwell-aou.gotc-prod.broadinstitute.org/"
  _WFL_ENVIRONMENT="aou-prod"
else
  printf "Unrecognized google project\n"
  exit 1
fi

gcloud config set project ${GCLOUD_PROJECT}
gcloud functions deploy submit_aou_workload \
    --region ${REGION} \
    --trigger-resource ${TRIGGER_BUCKET} \
    --trigger-event ${TRIGGER_EVENT} \
    --service-account ${SA_EMAIL} \
    --set-env-vars WFL_URL=${_WFL_URL},CROMWELL_URL=${_CROMWELL_URL},WFL_ENVIRONMENT=${_WFL_ENVIRONMENT} \
    --runtime python37 \
    --memory 128MB
