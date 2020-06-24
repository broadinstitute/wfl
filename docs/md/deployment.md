# Deployment of WorkFlow Launcher

## Make a deployment

The WorkFlow Launcher is currently running on a Kubernetes cluster,
and the deployment is managed by [Helm](https://helm.sh/docs/intro/install/).
In order to make a deployment or upgrade the current deployment manually,
you have to make the following preparations:

### Setup Kubernetes

- Make sure you have the `kubectl` command available,
  while in the Broad network or using the VPN,
  run a command like the following
  to set up the connection to the desired cluster:

  ```bash
  gcloud container clusters get-credentials \
    gotc-dev-shared-us-central1-a --zone us-central1-a \
    --project broad-gotc-dev
  ```

- Run `kubectl config get-contexts` to make sure you are connected
  to the right cluster.

- Later you could run `kubectl config use-context $CONTEXT_NAME`
  to switch (back) to other contexts.

### Setup Helm

- Install Helm,
  please follow the Helm instructions
  or simply try `brew install helm`,
  assuming you have [HomeBrew](https://brew.sh/)
  installed on your MacOS.

- Run:

  ```bash
  helm repo add gotc-charts https://broadinstitute.github.io/gotc-helm-repo/
  ```
  to add gotc’s Helm repo to your Helm.
  Note `gotc-charts` is just an alias, you could give it any name you want.

- Run:

  ```bash
  helm repo update
  ```
  to make the local cached charts update-to-date
  to the remote repo and also run:

  ```bash
  helm repo list
  ```
  to check the list of repo you have connected to anytime you want.

- In the Broad network or on VPN and your `kubectl`
  is setup to connect to the right cluster,  run:

  ```bash
  helm list
  ```
  to check the current deployments that are managed by Helm.

### Build

Build a new WFL jar it you want one.

```bash
boot build
```

Note the `:version` printed by the build,
or run this to recover the `:version` string.

``` bash
java -jar ./target/wfl-*.tar version
```

The version string should look something like this.

``` bash
2020-06-24t16-41-44z
```

Compose an docker image tag for later use.

``` bash
IMAGE=2020-06-24t16-41-44z-$USER
```

### Dockerize

Compose a new docker image if you want one.
Tag it with some helpful name,
then push it to DockerHub
so Kubernetes can find it.

```bash
docker build -t broadinstitute/workflow-launcher-api:$IMAGE .
docker build -t broadinstitute/workflow-launcher-ui:$IMAGE  ui/.
docker push broadinstitute/workflow-launcher-api:$IMAGE
docker push broadinstitute/workflow-launcher-ui:$IMAGE
```

You should see it listed here.

https://hub.docker.com/repository/docker/broadinstitute/workflow-launcher-api

### Clone the gotc-deploy repository.

``` bash
git clone --branch <the branch you want> \
  https://github.com/broadinstitute/gotc-deploy.git
```

### Render the chart

Render the `wfl-values.yaml` file.

``` bash
docker run -i --rm -v "$(pwd)":/working \
  -v "$HOME"/.vault-token:/root/.vault-token \
  -e WFL_VERSION=latest \
  broadinstitute/dsde-toolbox:dev \
  /usr/local/bin/render-ctmpls.sh \
  -k ./gotc-deploy/deploy/gotc-dev/helm/wfl-values.yaml.ctmpl
```

**Note:**
That command always fails,
so look at `./gotc-deploy/deploy/gotc-dev/helm/wfl-values.yaml`
to verify that the values
substituted into the template are correct.

**Note:**
Some of the values in these YML files
contain credentials or sensitive information,
so **DO NOT** check them in
to your version control system
or make them public!!!

### Deploy

Run this to upgrade a deployment release.

```bash
helm upgrade wfl-k8s gotc-charts/wfl --install \
  -f ./gotc-deploy/deploy/gotc-dev/helm/wfl-values.yaml
```

That doesn't recreate ingress resources and so on,
but it will `--install` everything necessary
if a release is not deployed already.

Run `kubectl get pods` to review your deployments.

``` bash
# kubectl get pods
NAME                                              READY STATUS  AGE
cromwell-auth-gotc-dev-authproxy-6f76598bb4-snpp6 1/1   Running 11d
hjf-test-authproxy-78758bbb6b-7lnfg               2/2   Running 42d
wfl-k8s-6948f6566d-2bdcp                          2/2   Running 2d5h
#
```

Run `kubectl logs wfl-k8s-6948f6566d-2bdcp workflow-launcher-api`
to view the logs for the WFL-API server.

Run `kubectl logs wfl-k8s-6948f6566d-2bdcp workflow-launcher-ui`
to view the logs for the WFL-UI front end.

[//]: # (tbl stopped fixing stuff here.)

## Testing the deployment locally

Similar to the above process,
but in addition to the above preparations,
you also have to:

- Make sure you have a relatively new version of Docker client installed,
  which has Docker -> Preferences -> Kubernetes -> Enable Kubernetes.
  Turn on that options and restart your Docker client.

- Run `kubectl config use-context $CONTEXT_NAME`
  to the Docker-built-in Kubernetes context,
  usually it should be called something like `docker-for-mac`.

Similar to how you have setup the Helm charts, you could run:

```bash
helm install gotc-dev gotc-charts/authproxy -f custom-authvals.yaml
```

Or

```bash
helm upgrade gotc-dev gotc-charts/authproxy -f custom-authvals.yaml
```

To install or upgrade the deployments on your local cluster.
One thing to note is that you **CANNOT** create
an `ingress` resource locally,
so it’s important to `disable` the ingress creation
in your custom values YML files.
