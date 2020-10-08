# WFL Sandboxes
### ![:deploy_parrot:](https://emojis.slackmojis.com/emojis/images/1554740062/5584/deployparrot.gif ":deploy_parrot:")

We have the ability to deploy isolated instances of WFL infrastructure so that
developers can work in parallel. Summary of the steps:
1. Copy another dev's sandbox in `gotc-deploy/deploy/` and change every instance of
their username to yours, PR in the changes, `apply` them
2. Change your Cloud SQL instance's `wfl` password to the one in
`secret/dsde/gotc/dev/zero`
3. Add the new URL to the OAuth credentials
4. Deploy WFL via `./ops/cli.py gotc-dev <username>`

Because this process might well be a new team member's first introduction to WFL,
Terraform, and/or GCP, I've added more in-depth instructions below.

## 1. Make your sandbox
Within our [deployment config repo](https://github.com/broadinstitute/gotc-deploy)
we keep our sandboxes in the `deploy` folder in folders like `{username}-sandbox`.
They should look pretty similar from person to person but ask if you're not sure
who has a good sandbox for you to copy (folks might've done special stuff to theirs).

Once you know which one to copy, do so and change every instance of their username
to yours (both in file names and contents). If you don't change occurances of their
username within your files you may break their sandbox.

```
.
└── deploy/
    ├── ...
    └── {username}-sandbox/
        └── terraform/
            ├── .gitignore
            ├── README.md
            ├── gotc-dev-remote_state.tf
            ├── {username}-sandbox_state.tf
            ├── {username}-sandbox-provider.tf
            └── {username}-wfl-instance.tf
```

PR this in (anyone on the team can review). To enact the changes, run:

!!! warning

    You'll need to be on the non-split VPN from here on out
    (you'll get TCP timeouts if you're not)

```bash
# in deploy/{username}-sandbox/terraform on the main branch
../../../scripts/terraform.sh init
../../../scripts/terraform.sh plan -out plan.out
```

Look over `plan`'s output, nothing should be destroyed. If all looks good:
```bash
../../../scripts/terraform.sh apply plan.out
rm plan.out
```

## 2. Change the SQL password
Terraform makes a random password for the `wfl` account in your new Cloud SQL instance but for simplicity the WFL installations tied to `gotc-dev` all use the same one (you'll end up deploying using `gotc-dev`'s [WFL values file](https://github.com/broadinstitute/gotc-deploy/blob/master/deploy/gotc-dev/helm/wfl-values.yaml.ctmpl) which will supply a certain password).

The password is defined in Vault at `secret/dsde/gotc/dev/zero`. You'll want to copy it to your clipboard ideally without having it end up in your console history:
- If you use Vault's UI, go to [https://clotho.broadinstitute.org:8200/ui/vault/secrets/secret/show/dsde/gotc/dev/zero](https://clotho.broadinstitute.org:8200/ui/vault/secrets/secret/show/dsde/gotc/dev/zero) and click the little copy button next to the `password` field
- If you use a Mac, you can run `vault read -field=password secret/dsde/gotc/dev/zero | pbcopy`

Next, find your Cloud SQL instance. It is named starting with `{username}-wfl` in the `broad-gotc-dev` GCP project. Go to [the list of SQL instances](https://console.cloud.google.com/sql/instances?folder=&organizationId=&project=broad-gotc-dev), click on yours, go to "Users", change the `wfl` user's password, and paste the value you copied earlier.

## 3. Add URL to OAuth credentials
WFL makes use of OAuth and the new URL of your deployment will need to be added to what the OAuth client will accept. The credentials to edit correspond to the `oauth2_client_id` field at `secret/dsde/gotc/dev/zero` (match to the "Client ID" column [here](https://console.cloud.google.com/apis/credentials?project=broad-gotc-dev)).

If you'd rather copy-paste, go to `https://console.cloud.google.com/apis/credentials/oauthclient/{oauth2_client_id}`. You'll need to the following URI to the "Authorized JavaScript origins":
```
https://{username}-wfl.gotc-dev.broadinstitute.org
```
You'll also need to add the following URIs to the "Authorized redirect URIs": 
```
https://{username}-wfl.gotc-dev.broadinstitute.org
https://{username}-wfl.gotc-dev.broadinstitute.org/oauth2_redirect.html
```
That latter one is used for authenticating the Swagger UI.

## 4. Deploy WFL
See the [Quickstart section](/docs/md/README.md) for more info on building WFL and pushing images. Assuming you've done that, run `./ops/cli.py gotc-dev {username}` to deploy the version in `./version` to your sandbox. The help text on `./ops/cli.py` has more options for customizing the deployment.
