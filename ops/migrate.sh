# Run liquibase migration on a WFL database
# Note: This relies on dependencies installed during the WFL build and only works when run from the wfl directory
# Usage: bash ./ops/migrate.sh "vault/path/to/database/creds"

VAULT_PATH=${1}
DB_URL=$(vault read -field=postgres_url "${VAULT_PATH}")
DB_USERNAME=$(vault read -field=username "${VAULT_PATH}")
DB_PASSWORD=$(vault read -field=password "${VAULT_PATH}")
DB_CHANGELOG="$(pwd)/database/changelog.xml"
WORKING_DIR=$(pwd)
LIQUIBASE=liquibase

if ! liquibase --version &> /dev/null
then
  echo "Liquibase is not installed. Downloading JAR file..."
  mkdir "${WORKING_DIR}/liquibase"
  cd "${WORKING_DIR}/liquibase" || exit
  curl -L https://github.com/liquibase/liquibase/releases/download/v3.10.0/liquibase-3.10.0.tar.gz | tar -xzf -
  LIQUIBASE="${WORKING_DIR}/liquibase/liquibase"
  cd "${WORKING_DIR}" || exit
fi

$LIQUIBASE --url="${DB_URL}" --changeLogFile="${DB_CHANGELOG}" --username="${DB_USERNAME}" --password="${DB_PASSWORD}" \
--classpath="$(clojure -Spath)" validate
