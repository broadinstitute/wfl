#!/bin/bash

set -e

# print with style
# all available styles:
# info | error | success | warn | debug
function print_style {
    if [ "$1" == "info" ];
    then
        # print gray
        printf '\e[1;90m%-6s\e[m\n' "$2"
    elif [ "$1" == "error" ];
    then
         # print red
        printf '\e[1;91m%-6s\e[m\n' "$2"
    elif [ "$1" == "success" ];
    then
        # print green
        printf '\e[1;92m%-6s\e[m\n' "$2"
    elif [ "$1" == "warn" ];
    then
        # print yellow
        printf '\e[1;93m%-6s\e[m\n' "$2"
    elif [ "$1" == "debug" ];
    then
        if [ "${DEBUG}" == "true" ];
        then
            # print gray
            printf '\e[1;90m%-6s\e[m\n' "$2"
        fi
    else
        printf "%s\n" "$1"
    fi
}

# check if the command is available in the shell
function check_availability {
    local some_command=$1

    hash "$some_command" 2>/dev/null || { 
        print_style >&2 "error" "${some_command} is required but not found! Aborting..."; 
        print_style "info" "${INSTALL_MSG}"
        exit 1; 
    }
}

# draw a line of char
function line {
    local char=${1:-"-"}
    printf %"$(tput cols)"s |tr " " "${char}"
}

# render Helm values
function render_helm_values {
    local CTMPL_FILE=$1

    docker run -ti \
               --rm \
               -v "$(pwd)":/working \
               -v "${HOME}"/.vault-token:/root/.vault-token \
               broadinstitute/dsde-toolbox:dev \
               /usr/local/bin/render-ctmpls.sh \
               -k "${CTMPL_FILE}"
}

function setup_and_update_helm_charts {
    local CHART_REPO_ALIAS=${1:-gotc-charts}

    # give the charts an alias "gotc-charts"
    helm repo add "${CHART_REPO_ALIAS}" https://broadinstitute.github.io/gotc-helm-repo/

    # attempt to fetch updates for the charts
    helm repo update

    # list available charts
    helm repo list
}

function deploy_helm_charts {
    local DEPLOYMENT_NAME=$1
    local CHART_REPO_ALIAS=$2
    local CHART_NAME=$3
    local RENDERED_VALUES=$4
    local LOCAL=$5

    if [ -z "${LOCAL}" ]; 
    then
        # upgrade a Helm deployment, if it does not exist yet, install it
        helm upgrade "${DEPLOYMENT_NAME}" "${CHART_REPO_ALIAS}/${CHART_NAME}" -f "${RENDERED_VALUES}" --install
    else
        # upgrade a Helm deployment, if it does not exist yet, install it
        # also turn off the generation of ingress for local testing
        helm upgrade "${DEPLOYMENT_NAME}" "${CHART_REPO_ALIAS}/${CHART_NAME}" -f "${RENDERED_VALUES}" --set ingress.enabled=false --install
    fi
   
    # list deployments
    helm list
}

function deploy_to_local {
    check_availability helm
    check_availability kubectl
    check_availability minikube
    
    line "-"

    # start a minikube local cluster
    # won't be an issue if there's already running one
    minikube start

    line "-"

    # render values for Helm
    # render_helm_values "wfl-values.yaml.ctmpl"

    line "-"

    # setup helm and charts
    setup_and_update_helm_charts "gotc-charts"

    line "-"

    # deploy the helm charts
    deploy_helm_charts "gotc-dev" "gotc-charts" "wfl" "wfl-values.yaml" "true"

    line "-"

    # it takes 5-10 seconds helm to spin up the pod 
    sleep 10
    POD_NAME=$(kubectl get pods --no-headers -o custom-columns=":metadata.name")
    print_style "success" "Deployment on local minikube cluster is set up with pod ${POD_NAME}!"

    # forward the pod port to host port 18982, hope it's not taken yet
    HOST_PORT="18982"
    print_style "success" "You could access http://localhost:${HOST_PORT} to view it in browser"
    sudo kubectl port-forward "${POD_NAME}" "${HOST_PORT}":80
}

function deploy_wfl_to_cloud {
     check_availability helm
     check_availability kubectl
     
     # switch to gotc-dev shared cluster
     kubectl config use-context gke_broad-gotc-dev_us-central1-a_gotc-dev-shared-us-central1-a
     
     # render values for Helm
     render_helm_values "wfl-values.yaml.ctmpl"

     # setup helm and charts
     setup_and_update_helm_charts "gotc-charts"

     # deploy the helm charts
     deploy_helm_charts "gotc-dev" "gotc-charts" "wfl" "wfl-values.yaml"
}

# Main function part
export HELP_MSG="""
Available commands:

local       |-> Deploy WFL on local Minikube for testing

            usage: local
            example: render values.yaml.ctmpl

deploy      |-> Deploy WFL to Cloud GKE (VPN required)

            usage: deploy
            example: render values.yaml.ctmpl

render      |-> Just render ctmpl files

            usage: render \${YOUR_CTMPL_FILE}
            example: render values.yaml.ctmpl
"""

export INSTALL_MSG="""

To install:

kubectl     |-> Follow https://cloud.google.com/sdk/install
minikube    |-> Follow https://minikube.sigs.k8s.io/docs/start/ or 'brew install minikube' and then 'brew link minikube'
helm        |-> Follow https://helm.sh/docs/intro/install/ or 'brew install helm'
vault       |-> Follow https://broadinstitute.atlassian.net/wiki/spaces/DO/pages/113874856/Vault
"""

COMMAND=${1}

if [ -z "${COMMAND}" ]; 
then
    print_style "info" "${HELP_MSG}"

# local command
elif [ "${COMMAND}" == "local" ];
then
    print_style "info" "Mode: local"
    deploy_to_local

# deploy command
elif [ "${COMMAND}" == "deploy" ];
then
    print_style "info" "Mode: deploy"
    deploy_wfl_to_cloud

# render command
elif [ "${COMMAND}" == "render" ];
then
    CTMPL_FILE=${2}
    if [ -z "${CTMPL_FILE}" ]; 
    then
        print_style "error" "A valid path to ctmpl file is required!"
        print_style "info" "${HELP_MSG}"
    else
        print_style "info" "Rendering ${CTMPL_FILE} for you..."
        render_helm_values "${CTMPL_FILE}"
    fi
fi
