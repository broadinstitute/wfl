#!/usr/bin/env python3
"""WFL Deployment Script 
@rex

requirements: pip3 install gitpython

usage: python3 cli.py -h
"""
import argparse
import sys
from typing import Callable
import subprocess
from pathlib import Path
import tempfile
import uuid
import git
import shutil
from enum import Enum
import os
import sys
import logging


# In case this will need to run on Windows systems
if sys.platform.lower() == "win32":
    os.system("color")


class AvailableColors(Enum):
    GRAY = 90
    RED = 91
    GREEN = 92
    YELLOW = 93
    BLUE = 94
    PURPLE = 95
    WHITE = 97
    BLACK = 30
    DEFAULT = 39


def dye_msg_with_color(msg: str, color: str) -> str:
    """Dye message with color, fall back to default if it fails."""
    color_code = AvailableColors["DEFAULT"].value
    try:
        color_code = AvailableColors[color.upper()].value
    except KeyError:
        pass
    return f"\033[{color_code}m{msg}\033[0m"


def registerableCLI(cls):
    """Class decorator to register methodss with @register into a set."""
    cls.registered_commands = set([])
    for name in dir(cls):
        method = getattr(cls, name)
        if hasattr(method, "registered"):
            cls.registered_commands.add(name)
    return cls


def register(func: Callable) -> Callable:
    """Method decorator to register CLI commands."""
    func.registered = True
    return func


@registerableCLI
class CLI:
    def __init__(self):
        parser = argparse.ArgumentParser(
            description="WFL Development CLI", usage=self._usage()
        )
        parser.add_argument("command", help="command from the above list to run")

        # Print help if no command provided
        if len(sys.argv[1:2]) == 0:
            parser.print_help()
            exit(1)
        self.main_parser = parser

    def __call__(self) -> int:
        args = self.main_parser.parse_args(sys.argv[1:2])
        if args.command not in self.registered_commands:
            self.main_parser.print_help()
            return 1
        return getattr(self, args.command)(sys.argv[2:])

    def _usage(self) -> str:
        msg, space = "\n", 20
        for command in sorted(self.registered_commands):
            msg += f"   {command}{'  ' * (space - len(command))} |-> {getattr(self, command).__doc__}\n"
        return msg

    @register
    def render(self, arguments: list) -> int:
        """Render a CTMPL file"""
        parser = argparse.ArgumentParser(description=f"%(prog) {self.render.__doc__}")
        parser.add_argument("file", help="Path to the CTMPL file needs to be rendered")
        args = parser.parse_args(arguments)

        file = Path(args.file)
        assert file.exists() and file.is_file(), f"{file} is not valid!"
        return CLI._render_ctmpl(ctmpl_file=str(file))

    @staticmethod
    def _clone_config_repo(dest: str, branch: str = "master"):
        """Clone the gotc-deploy repo, this requires permission to clone private Broad repos."""
        print(
            dye_msg_with_color(
                msg=f"=> Cloning gotc-deploy repo to {dest}, default branch: {branch}",
                color="blue",
            )
        )
        git.Repo.clone_from(
            url="git@github.com:broadinstitute/gotc-deploy.git",
            to_path=dest,
            branch=branch,
        )
        print(
            dye_msg_with_color(
                msg=f"=> Cloned gotc-deploy repo to {dest}", color="green"
            )
        )
        return 0

    @staticmethod
    def _render_ctmpl(ctmpl_file: str) -> int:
        """Render a ctmpl file."""
        print(
            dye_msg_with_color(
                msg=f"=> Rendering ctmpl file {ctmpl_file}", color="blue"
            )
        )
        COMMAND = f'docker run -i --rm -v "$(pwd)":/working -v "$HOME"/.vault-token:/root/.vault-token broadinstitute/dsde-toolbox:dev /usr/local/bin/render-ctmpls.sh -k "{ctmpl_file}"'
        return subprocess.call(COMMAND, shell=True)

    @staticmethod
    def _is_available(*commands: list):
        for cmd in commands:
            if not shutil.which(cmd):
                print(
                    dye_msg_with_color(
                        f"=> {cmd} is missing in PATH, please check and install!",
                        color="red",
                    )
                )

    @staticmethod
    def _set_up_helm() -> int:
        print(dye_msg_with_color(msg=f"=> Setting up Helm charts", color="blue"))
        HELM_ADD = f"helm repo add gotc-charts https://broadinstitute.github.io/gotc-helm-repo/;"
        HELM_REPO = f"helm repo update;"
        return subprocess.call(HELM_ADD + HELM_REPO, shell=True)

    @staticmethod
    def _set_up_k8s(environment: str = "dev", namespace: str = "") -> int:
        """Connect to K8S cluster and setup namespace with gcloud and kubectl."""
        print(
            dye_msg_with_color(
                msg=f"=> Setting up Kubernetes, with namespace {namespace or 'N/A' }",
                color="blue",
            )
        )
        GCLOUD_SETUP = f"gcloud container clusters get-credentials gotc-{environment}-shared-us-central1-a --zone us-central1-a --project broad-gotc-{environment}"
        code1 = subprocess.call(GCLOUD_SETUP, shell=True)

        print(
            dye_msg_with_color(
                msg=f"=> Setting up Kubernetes cluster context", color="blue"
            )
        )
        CTX = f"gke_broad-gotc-{environment}_us-central1-a_gotc-{environment}-shared-us-central1-a"
        KUBE_CONTEXT = f"kubectl config use-context {CTX}"
        code2 = subprocess.call(KUBE_CONTEXT, shell=True)

        code3 = 0
        if namespace:
            KUBE_SET_NAMESPACE = f"kubectl config set-context $(kubectl config current-context) --namespace={namespace}"
            code3 = subprocess.call(KUBE_SET_NAMESPACE, shell=True)
            print(
                dye_msg_with_color(
                    msg=f"=> Set up namespace to {namespace}", color="green"
                )
            )
        return any([code1, code2, code3])

    @staticmethod
    def _helm_deploy_wfl(values: str) -> int:
        """Use Helm to deploy WFL to K8S."""
        print(
            dye_msg_with_color(
                msg=f"=> Deploying to K8S cluster with Helm (this requires VPN)",
                color="blue",
            )
        )
        HELM_UPGRADE = f"helm upgrade wfl-k8s gotc-charts/wfl -f {values} --install"
        print(
            dye_msg_with_color(
                msg=f"=> WFL is deployed, run `kubectl get pods` to see details",
                color="green",
            )
        )
        return subprocess.call(HELM_UPGRADE, shell=True)

    @register
    def deploy(self, arguments):
        """Deploy WFL to Cloud GKE (VPN is required)"""
        parser = argparse.ArgumentParser(description=f"{self.deploy.__doc__}")
        parser.add_argument(
            "-e",
            "--environment",
            dest="environment",
            default="dev",
            choices={"dev",},
            help="Environment to deploy to",
        )
        parser.add_argument(
            "-n",
            "--namespace",
            dest="namespace",
            default="",
            help="The namespace you want to use for deployemnt",
        )
        args = parser.parse_args(arguments)

        CLI._is_available("helm", "kubectl", "gcloud", "git", "docker")
        CLI._set_up_helm()
        CLI._set_up_k8s(namespace=args.namespace)

        with tempfile.TemporaryDirectory(
            prefix=str(uuid.uuid4()), dir=Path("./")
        ) as dir_name:
            CLI._clone_config_repo(
                dest=Path(f"./{dir_name}/gotc-deploy"), branch="master"
            )

            file_name = "wfl-values.yaml.ctmpl"
            shutil.copy(
                str(Path(f"./{dir_name}/gotc-deploy/deploy/gotc-dev/helm/{file_name}")),
                Path(f"./{dir_name}/{file_name}"),
            )
            CLI._render_ctmpl(ctmpl_file=str(Path(f"./{dir_name}/{file_name}")))

            CLI._helm_deploy_wfl(values=str(Path(f"./{dir_name}/wfl-values.yaml")))


if __name__ == "__main__":
    c = CLI()
    exit(c())
