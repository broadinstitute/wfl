# Deployment of WorkFlow Launcher

## Make a deployment
The WorkFlow Launcher is currently running on a Kubernetes cluster, and the deployment is managed by [Helm](https://helm.sh/docs/intro/install/). In order to make a deployment or upgrade the current deployment manually, you have to make the following preparations:

### Setup Kubernetes
- Make sure you have the `kubectl` command available, while in the Broad network or using the VPN, run a command like the following to setup the connection to the desired cluster:
    ```bash
    gcloud container clusters get-credentials gotc-dev-shared-us-central1-a --zone us-central1-a --project broad-gotc-dev
    ```
- Run `kubectl config get-contexts` to make sure you are connected to the right cluster.
- Later you could run `kubectl config use-context $CONTEXT_NAME` to switch (back) to other contexts.

### Setup Helm
- Install Helm, please follow the Helm instructions or simply try `brew install helm`, assuming you have [HomeBrew](https://brew.sh/) installed on your macOS.
- Run:
    ```bash
    helm repo add gotc-charts https://broadinstitute.github.io/gotc-helm-repo/
    ```
    to add gotc’s Helm repo to your Helm. Note `gotc-charts` is just an alias, you could give it any name you want. 

- Run:
    ```bash
    helm repo update
    ```
    to make the local cached charts update-to-date to the remote repo and also run:

    ```bash
    helm repo list
    ```
    to check the list of repo you have connected to anytime you want.

- In the Broad network or on VPN and your `kubectl` is setup to connect to the right cluster,  run:
    ```bash
    helm list
    ```
    to check the current deployments that are managed by Helm.

### Deployment
Once you have finished the above preparations, you could take a look at your custom values for the deployment. Usually that means a rendered version of [these YML files](https://github.com/broadinstitute/gotc-deploy/tree/master/deploy/gotc-dev/helm), with your modifications to some specific values in it. 

**Note:** some of the values in these YML files contain credentials or sensitive information, so **DO NOT** checkout them into your version control system or make them public!!!

Now with Helm and the custom values YML files, you could run for instance:

```bash
helm install gotc-dev gotc-charts/authproxy -f custom-authvals.yaml
```
where:

- `gotc-dev` is the name of the deployment 
- `gotc-charts/authproxy` is following `chart-repo-alias/chartname`
- `custom-authvals.yaml` is the path to your custom values YML file

to make a new deployment or
```bash
helm upgrade gotc-dev gotc-charts/authproxy -f custom-authvals.yaml
```
To update an existing deployment without re-creating all of the resources which could slow down the process.

You could run `kubectl get pods` to check the readiness of your deployments, `kubectl logs $POD_NAME $POD_APPLICATION_NAME` to view the logs.

## Testing the deployment locally
Similar to the above process, but in addition to the above preparations, you also have to:

- Make sure you have a relatively new version of Docker client installed, which has Docker -> Preferences -> Kubernetes -> Enable Kubernetes. Turn on that options and restart your Docker client.
- Run `kubectl config use-context $CONTEXT_NAME` to the Docker-built-in Kubernetes context, usually it should be called something like `docker-for-mac`.

Similar to how you have setup the Helm charts, you could run:

```bash
helm install gotc-dev gotc-charts/authproxy -f custom-authvals.yaml
```
Or 
```bash
helm upgrade gotc-dev gotc-charts/authproxy -f custom-authvals.yaml
```
To install or upgrade the deployments on your local cluster. One thing to note is that you **CANNOT** create an `ingress` resource locally, so it’s important to `disable` the ingress creation in your custom values YML files.
