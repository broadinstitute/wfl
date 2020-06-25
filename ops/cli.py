#!/usr/bin/env python3
"""WFL Deployment Script
@rex

requirements: pip3 install gitpython

Example: ./ops/cli.py -t latest

usage: python3 cli.py -h
"""
from enum import Enum
from pathlib import Path
from typing import Callable
import argparse
import git
import os
import shutil
import subprocess
import sys
import tempfile

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


def _apply_color(color: str, message: str) -> str:
    """Dye message with color, fall back to default if it fails."""
    color_code = AvailableColors["DEFAULT"].value
    try:
        color_code = AvailableColors[color.upper()].value
    except KeyError:
        pass
    return f"\033[1;{color_code}m{message}\033[0m"


def info(message: str):
    """Log the info to stdout"""
    print(_apply_color("default", message))


def success(message: str):
    """Log the success to stdout"""
    print(_apply_color("green", message))


def error(message: str):
    """Log the error to stderr"""
    print(_apply_color("red", message), file=sys.stderr)


def warn(message: str):
    """Log the warning to stdout"""
    print(_apply_color("yellow", message))


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


def shell(command: str):
    """Run COMMAND in a subprocess."""
    info(f"Running: {command}")
    return subprocess.check_call(command, shell=True)


def shell_unchecked(command: str):
    """Run COMMAND in a subprocess and who cares whether it fails!"""
    warn(f"Running unchecked: {command}")
    return subprocess.call(command, shell=True)


def clone_config_repo(dest: str, branch: str):
    """Clone the gotc-deploy repo."""
    info(f"=> Cloning gotc-deploy repo to {dest}, branch: {branch}")
    git.Repo.clone_from(
        url="git@github.com:broadinstitute/gotc-deploy.git",
        to_path=dest,
        branch=branch
    )
    success(f"[✔] Cloned gotc-deploy repo to {dest}")


def render_ctmpl(ctmpl_file: str, **kwargs) -> int:
    """Render a ctmpl file."""
    info("=> Rendering ctmpl file {ctmpl_file}")
    envs = ""
    if kwargs:
        for k, v in kwargs.items():
            envs += f"-e {k}={v}"

    info(f"=> Feeding variables: {envs}")
    command = " ".join(['docker run -i --rm -v "$(pwd)":/working',
                        '-v "$HOME"/.vault-token:/root/.vault-token',
                        f'{envs}',
                        'broadinstitute/dsde-toolbox:dev',
                        '/usr/local/bin/render-ctmpls.sh -k',
                        f'"{ctmpl_file}"'])

    shell_unchecked(command)
    success(f"[✔] Rendered file {ctmpl_file.split('.ctmpl')[0]}")


def is_available(*commands: list):
    for cmd in commands:
        if not shutil.which(cmd):
            error(f"=> {cmd} is not in PATH. Please install it!")


def set_up_helm():
    info("=> Setting up Helm charts")
    ADD = " ".join(["helm repo add gotc-charts",
                    "https://broadinstitute.github.io/gotc-helm-repo/"])
    shell(ADD)
    shell("helm repo update")
    success("[✔] Set up Helm charts")


def set_up_k8s(environment: str, namespace: str):
    """Connect to K8S cluster and set up namespace."""
    info(f"=> Setting up Kubernetes with namespace {namespace}.")

    ZONE = "us-central1-a"
    GCLOUD = " ".join(["gcloud container clusters get-credentials",
                       f"gotc-{environment}-shared-{ZONE} --zone {ZONE}",
                       f"--project broad-gotc-{environment}"])
    shell(GCLOUD)

    info("=> Setting up Kubernetes cluster context.")
    context = f"gke_broad-gotc-{environment}_{ZONE}_gotc-{environment}-shared-{ZONE}"
    shell(f"kubectl config use-context {context} --namespace={namespace}")
    success(f"[✔] Set context to {context}.")
    success(f"[✔] Set namespace to {namespace}.")


def helm_deploy_wfl(values: str):
    """Use Helm to deploy WFL to K8S using VALUES."""
    info("=> Deploying to K8S cluster with Helm.")
    info("=> This must run on a non-split VPN.")

    shell(f"helm upgrade wfl-k8s gotc-charts/wfl -f {values} --install")
    info("[✔] WFL is deployed. Run `kubectl get pods` for details.")


@registerableCLI
class CLI:
    def __init__(self):
        parser = argparse.ArgumentParser(
            description="Deploy the Workflow Launcher API and UI.",
            usage=self.usage()
        )
        parser.add_argument("command", help="command from above to run")
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

    def usage(self) -> str:
        max = 0
        for command in sorted(self.registered_commands):
            if len(command) > max:
                max = len(command)
        msg = "\n"
        for command in sorted(self.registered_commands):
            msg += f"  {command}{' ' * (max - len(command))}"
            msg += f" |-> {getattr(self, command).__doc__}\n"
        return msg

    @register
    def render(self, arguments: list) -> int:
        """Render a CTMPL file."""
        parser = argparse.ArgumentParser(
            description=f"%(prog) {self.render.__doc__}"
        )
        parser.add_argument("file", help="CTMPL file to be rendered")
        args = parser.parse_args(arguments)
        file = Path(args.file)
        assert file.exists() and file.is_file(), f"{file} is not valid!"
        render_ctmpl(ctmpl_file=str(file))
        return 0

    @register
    def deploy(self, arguments) -> int:
        """Deploy WFL to Cloud GKE (VPN is required)"""
        parser = argparse.ArgumentParser(description=f"{self.deploy.__doc__}")
        parser.add_argument(
            "-b",
            "--branch",
            dest="branch",
            default="master",
            help="Use this branch of gotc-deploy."
        )
        parser.add_argument(
            "-e",
            "--environment",
            dest="environment",
            default="dev",
            choices={"dev"},
            help="Deploy to this environment."
        )
        parser.add_argument(
            "-n",
            "--namespace",
            dest="namespace",
            default="default",
            help="Deploy to this namespace."
        )
        parser.add_argument(
            "-t",
            "--tag",
            dest="tag",
            default="latest",
            help="Deploy image with this tag in DockerHub."
        )
        args = parser.parse_args(arguments)
        is_available("helm", "kubectl", "gcloud", "git", "docker")
        set_up_helm()
        set_up_k8s(environment=args.environment, namespace=args.namespace)
        with tempfile.TemporaryDirectory() as dir:
            pwd = os.getcwd()
            os.chdir(dir)
            clone_config_repo(dest="gotc-deploy", branch=args.branch)
            values = "wfl-values.yaml"
            shutil.copy(f"gotc-deploy/deploy/gotc-dev/helm/{values}.ctmpl", dir)
            render_ctmpl(ctmpl_file=f"{values}.ctmpl", WFL_VERSION=args.tag)
            helm_deploy_wfl(values=values)
            os.chdir(pwd)
        success("[✔] Deployment is done!")
        shell("kubectl get pods")
        return 0


if __name__ == "__main__":
    c = CLI()
    exit(c())
