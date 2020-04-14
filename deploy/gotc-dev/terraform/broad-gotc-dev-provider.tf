# Configure the Google Cloud provider
# Every google project that resources are going ot be created in will have its own
#  google provider definition using the google project name as its alias so as to be
#  clear

provider "google" {
  alias   = "broad-gotc-dev"
  project = "broad-gotc-dev"
  region  = var.region
  version = "~> 3.2"
}

provider "google-beta" {
  alias   = "broad-gotc-dev"
  project = "broad-gotc-dev"
  region  = var.region
  version = "~> 3.2"
}
