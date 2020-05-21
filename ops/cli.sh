#!/bin/bash

set -e

declare -r HELP_MSG="Available commands:
local       |-> Deploy WFL on local Minikube for testing
            usage: local
            example: render values.yaml.ctmpl
deploy      |-> Deploy WFL to Cloud GKE (VPN required)
            usage: deploy
            example: render values.yaml.ctmpl
render      |-> Just render ctmpl files
            usage: render \${YOUR_CTMPL_FILE}
            example: render values.yaml.ctmpl
"

declare -r INSTALL_MSG="
To install:

kubectl     |-> Follow https://cloud.google.com/sdk/install
minikube    |-> Follow https://minikube.sigs.k8s.io/docs/start/ or 'brew install minikube' and then 'brew link minikube'
helm        |-> Follow https://helm.sh/docs/intro/install/ or 'brew install helm'
vault       |-> Follow https://broadinstitute.atlassian.net/wiki/spaces/DO/pages/113874856/Vault
"

info    () {     printf '\e[1;90m%-6s\e[m\n' "$*"; } # gray
error   () { >&2 printf '\e[1;91m%-6s\e[m\n' "$*"; } # red
success () {     printf '\e[1;92m%-6s\e[m\n' "$*"; } # green
warn    () {     printf '\e[1;93m%-6s\e[m\n' "$*"; } # yellow
debug   () {                                         # gray
    test "$DEBUG" = true && printf '\e[1;90m%-6s\e[m\n' "$*"
}

# Draw a line of - across the width of the terminal.
#
line () { printf "%.$(tput cols)d\n" 0 | tr 0 -; }


# OK when each CMD is on the PATH.
#
function is_available () {
    local cmd ok=ok
    for cmd in "$@"
    do
        if 2>/dev/null hash "$cmd"
        then
            : OK, we have "$cmd"
        else
            unset ok
            error "$cmd" is required but not found!
            info "${INSTALL_MSG}"
        fi
    done
    test "$ok"
}

# Wait at least 10 seconds for a result from "@".
#
wait_for_result () {
    local n result
    for n in 0 1 2 3 4 5 6 7 8 9
    do
        sleep 1
        result=$("$@")
        test "$result" && echo "$result" && break
    done
    test "$result"
}

# Render Helm values to CTMPL_FILE.
#
function run_render () {
    local -r ctmpl_file=$1
    if test "$ctmpl_file"
    then
        info Rendering "$ctmpl_file" for you...
        docker run -i \
               --rm \
               -v "$(pwd)":/working \
               -v "$HOME"/.vault-token:/root/.vault-token \
               broadinstitute/dsde-toolbox:dev \
               /usr/local/bin/render-ctmpls.sh \
               -k "$ctmpl_file"
    else
        error A valid path to ctmpl file is required!
        info "$HELP_MSG"
    fi
}

function setup_and_update_helm_charts () {
    local -r repo=${1:-gotc-charts}
    helm repo add "$repo" https://broadinstitute.github.io/gotc-helm-repo/
    helm repo update
    helm repo list
}

# Install or upgrade DEPLOYMENT, then list.
# Enable ingress when when INGRESS is set.
#
function deploy_helm_charts () {
    local -r deployment=$1 repo=$2 chart=$3 rendered=$4 ingress=$5
    local -a upgrade=(helm upgrade "$deployment" "$repo/$chart"
                      -f "$rendered" --install)
    test "$ingress" || upgrade+=(--set ingress.enabled=false)
    "${upgrade[@]}"
    helm list
}

# Deploy on the gotc-dev shared cluster.
#
function run_deploy () {
    local -r ctx=gke_broad-gotc-dev_us-central1-a_gotc-dev-shared-us-central1-a
    kubectl config use-context $ctx
    run_render wfl-values.yaml.ctmpl
    setup_and_update_helm_charts gotc-charts
    deploy_helm_charts gotc-dev gotc-charts wfl wfl-values.yaml ingress
}

# Deploy to a local minikube and forward the pod to PORT.
#
function run_local () {
    local -r port=18982
    local -ar getpods=(kubectl get pods --no-headers
                       -o custom-columns=:metadata.name)
    line
    # minikube start is not idempotent, kill
    # all previous ones for the sake of your
    # laptop's safety
    minikube stop
    line
    minikube start
    line
    # render values for Helm
    # render_helm_values "wfl-values.yaml.ctmpl"
    line
    setup_and_update_helm_charts gotc-charts
    line
    deploy_helm_charts gotc-dev gotc-charts wfl wfl-values.yaml
    line
    local -r pod=$(wait_for_result "${getpods[@]}")
    success Deployed "$pod" to a local minikube cluster.
    success Browse http://localhost:$port to view it.
    sudo kubectl port-forward "$pod" $port:80
}

function main () {
    local -r command=$1 ; shift || true
    local -r run="run_$command"
    is_available docker helm kubectl minikube sudo || exit 1
    if 1>&2 >/dev/null type "$run"
    then
        info "${command}" "$@"
        $run "$@"
    else
        test "$command" && error Unknown command: "$command"
        info "$HELP_MSG"
        exit 1
    fi
}

main "$@"
