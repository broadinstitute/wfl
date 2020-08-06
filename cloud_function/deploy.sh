# Deploy the submit_aou_workload cloud function
# usage: bash deploy.sh GCLOUD_PROJECT TRIGGER_BUCKET

GCLOUD_PROJECT=${1}
TRIGGER_BUCKET=${2}
TRIGGER_EVENT=${3:-"google.storage.object.finalize"}
REGION=${4:-"us-central1"}

if [ "${GCLOUD_PROJECT}" == "broad-gotc-dev" ]; then
  # This service account must have access to the WFL API
  SA_EMAIL="aou-cloud-fn-non-prod@broad-gotc-dev.iam.gserviceaccount.com"
  _WFL_URL="https://workflow-launcher.gotc-dev.broadinstitute.org"
elif [ "${GCLOUD_PROJECT}" == "broad-aou-storage" ]; then
  SA_EMAIL="aou-cloud-fn@broad-aou-storage.iam.gserviceaccount.com"
  _WFL_URL="https://aou-wfl.gotc-prod.broadinstitute.org"
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
    --set-env-vars WFL_URL=${_WFL_URL} \
    --runtime python37 \
    --memory 128MB
