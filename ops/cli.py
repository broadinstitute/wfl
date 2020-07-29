#!/usr/bin/env python3
"""WFL Deployment Script
@rex

requirements: pip3 install gitpython pyyaml

usage: python3 cli.py -h
"""
from enum import Enum
from pathlib import Path
from typing import Callable
import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile
import yaml

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


def clone(url: str, branch: str):
    """Clone the BRANCH of git repo at URL."""
    info(f"=> Cloning {branch} of {url} ...")
    shell(f"git clone {url} --branch {branch}")
    success(f"[✔] Cloned {branch} of {url}.")


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


def commands_available():
    commands = ["boot", "docker", "gcloud", "git", "helm", "java", "kubectl", "jq"]
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


def set_up_k8s(project: str, namespace: str, cluster: str, zone: str):
    """Connect to K8S cluster and set up namespace."""
    info(f"=> Setting up Kubernetes with namespace {namespace}.")
    GCLOUD = " ".join(["gcloud container clusters get-credentials",
                       f"{cluster} --zone {zone}",
                       f"--project {project}"])
    shell(GCLOUD)
    info("=> Setting up Kubernetes cluster context.")
    ctx = f"gke_{project}_{zone}_{cluster}"
    shell(f"kubectl config use-context {ctx} --namespace={namespace}")
    success(f"[✔] Set context to {ctx}.")
    success(f"[✔] Set namespace to {namespace}.")


def run_cloud_sql_proxy(project: str, cloudsql_instance_name):
    """Connect to a google cloud sql instance using the cloud sql proxy."""
    info("=> Running cloud_sql_proxy")
    token = subprocess.check_output("gcloud auth print-access-token", shell=True, encoding='utf-8').strip()
    instance_command = " ".join([f"gcloud --format=json sql --project {project}",
                             f"instances describe {cloudsql_instance_name}",
                             "| jq .connectionName | tr -d '\"'"])
    instance = subprocess.check_output(instance_command, shell=True, encoding='utf-8').strip()
    docker_command = " ".join(['docker run --rm -d -p 127.0.0.1:5432:5432 gcr.io/cloudsql-docker/gce-proxy:1.16 /cloud_sql_proxy',
                        f'-token="{token}" -instances="{instance}=tcp:0.0.0.0:5432"'])
    return subprocess.check_output(docker_command, shell=True, encoding='utf-8').strip()


def run_liquibase_migration(db_username, db_password):
    """Run liquibase migration on the database that the cloudsql proxy is connected to."""
    info("=> Running liquibase")
    db_url = "jdbc:postgresql://localhost:5432/wfl?useSSL=false"
    pwd = os.getcwd()
    changelog_dir = f"{pwd}/wfl/database"
    command = ' '.join(['docker run --rm --net=host',
                        f'-v {changelog_dir}:/liquibase/changelog liquibase/liquibase',
                        '/liquibase/liquibase',
                        f'--url="{db_url}" --changeLogFile=/changelog/changelog.xml',
                        f'--username="{db_username}" --password="{db_password}" update'])
    shell(command)
    success("[✔] Ran liquibase migration")


def helm_deploy_wfl(values: str):
    """Use Helm to deploy WFL to K8S using VALUES."""
    info("=> Deploying to K8S cluster with Helm.")
    info("=> This must run on a non-split VPN.")
    shell(f"helm upgrade wfl-k8s gotc-charts/wfl -f {values} --install")
    info("[✔] WFL is deployed. Run `kubectl get pods` for details.")


def build(directory: str) -> int:
    """Boot build WFL in DIRECTORY and return its version string."""
    cwd = os.getcwd()
    os.chdir(directory)
    shell("boot build")
    command = "java -jar ./target/wfl-*.jar version-json"
    info(f"Running: {command}")
    output = subprocess.check_output(command, shell=True)
    version = json.loads(output)
    os.chdir(cwd)
    return version["version"]


def docker(directory: str, tag: str) -> int:
    """Push API and UI docker images with TAG in DIRECTORY."""
    build = "docker build -t broadinstitute"
    shell(f"{build}/workflow-launcher-api:{tag} {directory}")
    shell(f"{build}/workflow-launcher-ui:{tag} {directory}/ui")
    push = "docker push broadinstitute"
    shell(f"{push}/workflow-launcher-api:{tag}")
    shell(f"{push}/workflow-launcher-ui:{tag}")


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
        parser = argparse.ArgumentParser(description=f"{self.render.__doc__}")
        parser.add_argument("file", help="CTMPL file to be rendered")
        args = parser.parse_args(arguments)
        file = Path(args.file)
        assert file.exists() and file.is_file(), f"{file} is not valid!"
        render_ctmpl(ctmpl_file=str(file))
        return 0

    @register
    def connect(self, arguments: list) -> int:
        """Connect to the Cloud SQL proxy through docker."""
        parser = argparse.ArgumentParser(description=f"{self.connect.__doc__}")
        parser.add_argument("-p", "--project", default="broad-gotc-dev", dest="project", help="The google project that Cloud SQL is running in. e.g. broad-gotc-dev")
        parser.add_argument("-i", "--instance", default="zero-postgresql", dest="instance", help="The name of the Cloud SQL instance. e.g. zero-postgresql")
        args = parser.parse_args(arguments)
        container = run_cloud_sql_proxy(gcloud_project=args.project, cloudsql_instance_name=args.instance)
        success(f"[✔] You have connected to Zero's Cloud SQL instance {args.instance} in {args.project}")
        info(f'To use psql, run: \n\t psql "host=127.0.0.1 sslmode=disable dbname=<DB_NAME> user=<USER_NAME>"')
        info(f"To disconnect and stop the container, run: \n\t docker stop {container}")
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
            "-z",
            "--zone",
            dest="zone",
            default="us-central1-a",
            help="Use this location for the google cloud cluster."
        )
        parser.add_argument(
            "-e",
            "--environment",
            dest="environment",
            default="gotc-dev",
            choices=["gotc-dev", "gotc-prod", "aou"],
            help="Deploy to project 'broad-{ENVIRONMENT}' in cluster '{ENVIRONMENT}-shared-{ZONE}' via "
                 "'gotc-deploy/deploy/{ENVIRONMENT}'."
        )
        parser.add_argument(
            "-c",
            "--cluster",
            dest="cluster",
            help="Overrides ENVIRONMENT; specify cluster name as '{CLUSTER}-{ZONE}'."
        )
        parser.add_argument(
            "-p",
            "--project",
            dest="project",
            help="Overrides ENVIRONMENT; specify exact gcp project name."
        )
        parser.add_argument(
            "-g",
            "--gotc-folder",
            dest="gotc_folder",
            help="Overrides ENVIRONMENT; specify exact 'gotc-deploy/deploy/{GOTC_FOLDER}' path to use."
        )
        parser.add_argument(
            "-n",
            "--namespace",
            dest="namespace",
            default="default",
            help="Deploy to this namespace."
        )
        parser.add_argument(
            "-d",
            "--dry-run",
            dest="dry_run",
            action="store_true",
            help="When provided, exit after printing parsed arguments."
        )
        parser.add_argument(
            "--cluster-no-zone-name",
            dest="cluster_no_zone_name",
            action="store_true",
            help="When provided, do not append '-{ZONE}' to the CLUSTER name."
        )
        args = parser.parse_args(arguments)
        project = args.project if args.project is not None else f"broad-{args.environment}"
        cluster = args.cluster if args.cluster is not None else f"{args.environment}-shared"
        if not args.cluster_no_zone_name:
            cluster = f"{cluster}-{args.zone}"
        gotc_folder = args.gotc_folder if args.gotc_folder is not None else args.environment
        info("=> Deploy config:")
        info(f"   GCP project: {project}")
        info(f"   GCP zone: {args.zone}")
        info(f"   GCP cluster: {cluster} (namespace: {args.namespace})")
        info(f"   GOTC deploy folder: gotc-deploy/deploy/{gotc_folder}")
        if args.dry_run:
            info("=> Exiting due to --dry-run")
            return 0

        commands_available()
        set_up_helm()
        set_up_k8s(project=project, namespace=args.namespace, cluster=cluster, zone=args.zone)
        with tempfile.TemporaryDirectory() as dir:
            pwd = os.getcwd()
            os.chdir(dir)
            for url in ["git@github.com:broadinstitute/gotc-deploy.git",
                        "git@github.com:broadinstitute/wfl.git"]:
                clone(url=url, branch=args.branch)
            tag = build("./wfl")
            docker(directory="./wfl", tag=tag)
            values = "wfl-values.yaml"
            shutil.copy(f"gotc-deploy/deploy/{gotc_folder}/helm/{values}.ctmpl", dir)
            render_ctmpl(ctmpl_file=f"{values}.ctmpl", WFL_VERSION=tag)
            helm_deploy_wfl(values=values)

            with open(values) as f:
                helm_values = yaml.safe_load(f)
            db_username = helm_values['api']['env']['ZERO_POSTGRES_USERNAME']
            db_password = helm_values['api']['env']['ZERO_POSTGRES_PASSWORD']
            container = run_cloud_sql_proxy(project=project, cloudsql_instance_name="zero-postgresql")
            run_liquibase_migration(db_username, db_password)
            info("=> Stopping cloud_sql_proxy")
            shell(f"docker stop {container}")
            os.chdir(pwd)

        success("[✔] Deployment is done!")
        shell("kubectl get pods")
        return 0


if __name__ == "__main__":
    c = CLI()
    exit(c())
