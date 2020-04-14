module "wfl-dns" {

  source = "github.com/broadinstitute/gotc-deploy.git//terraform/auth_proxy?ref=tf_auth-proxy-0.0.1"

  providers = {
    google      = google.broad-gotc-dev
    google-beta = google-beta.broad-gotc-dev
  }
  auth_proxy_dns_name    = "workflow-launcher"
  auth_proxy_dns_zone    = "gotc-dev"
  auth_proxy_dns_project = "broad-gotc-dev"
}