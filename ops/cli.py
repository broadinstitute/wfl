#!/usr/bin/env python3
"""
WFL Deployment Script

requirements: pip3 install pyyaml

usage: python3 cli.py -h
"""
import argparse
import json
import os
import shutil
import subprocess
import sys
from typing import Callable

import yaml

from render_ctmpl import render_ctmpl
from util.misc import info, success, error, warn, shell

# In case this will need to run on Windows systems
if sys.platform.lower() == "win32":
    os.system("color")


class Config:
    """Base class for storing/resolving/validating command line input."""

    def __init__(self, parsed_args: argparse.Namespace):
        """Resolve and store input from parsed arguments."""
        self.dry_run: bool = parsed_args.dry_run
        self.force: bool = parsed_args.force
        self.environment: str = parsed_args.environment
        info(f"=>  Resolving configuration")
        self.version: str = \
            self.__resolve_config_value("Version", parsed_args.version, lambda: self.__read_version())
        self.gotc_deploy_folder: str = \
            self.__resolve_config_value("GotC Deploy folder", f"gotc-deploy/deploy/{self.environment}")
        self.instance_id: str = \
            self.__resolve_config_value("Instance ID", parsed_args.instance)
        self.project: str = \
            self.__resolve_config_value("GCP project", parsed_args.project, lambda: f"broad-{self.environment}")
        self.cloudsql_name: str = \
            self.__resolve_config_value("CloudSQL name", parsed_args.cloud_sql, lambda: self.__find_cloudsql_name())
        self.cluster_name: str = \
            self.__resolve_config_value("GKE cluster name", parsed_args.cluster, lambda: self.__find_cluster_name())
        self.cluster_zone: str = \
            self.__resolve_config_value("GKE cluster zone", parsed_args.zone, lambda: self.__find_cluster_zone())
        self.cluster_namespace: str = \
            self.__resolve_config_value("Kubernetes namespace", parsed_args.namespace,
                                        lambda: f"{self.instance_id}-wfl")
        success("Resolved configuration") if not self.force else warn("Resolved forced configuration")

    @staticmethod
    def __resolve_config_value(description: str, specific: str, default: Callable[[], str] = lambda: None) -> str:
        """Either use the specific value if truthy or calculate the default, printing accordingly."""
        val = specific or default()
        info(f"    {description.ljust(20, '.')}: {val}{' (specifically supplied)' if specific else ''}")
        return val

    @staticmethod
    def __read_version() -> str:
        with open("version") as version_file:
            return version_file.read().strip()

    def __find_cloudsql_name(self) -> str:
        """Use labels to identify the Cloud SQL name."""
        instances = json.loads(shell(f"gcloud --project {self.project} --format=json "
                                     "sql instances list "
                                     f"--filter='labels.app_name=wfl AND labels.instance_id={self.instance_id}'",
                                     quiet=True))
        if len(instances) == 1:
            return instances[0]["name"]
        else:
            error("No CloudSQL instance could be inferred")
            info("    Couldn't find a single match for "
                 f"`--filter='labels.app_name=wfl AND labels.instance_id={self.instance_id}'`")
            info(f"    Available instances in {self.project}:", plain=True)
            info(shell(f"gcloud --project {self.project} sql instances list", quiet=True), plain=True)
            return None if self.force else exit(1)

    def __find_cluster_name(self) -> str:
        """Look at the Cloud SQL labels to find the cluster name."""
        instances = json.loads(shell(f"gcloud --project {self.project} --format=json "
                                     "sql instances list "
                                     f"--filter='labels.app_name=wfl AND labels.instance_id={self.instance_id}'",
                                     quiet=True))
        if len(instances) == 1:
            return instances[0]["settings"]["userLabels"]["app_cluster"]
        else:
            error("No cluster name could be inferred")
            info("    Couldn't find a single match for "
                 f"`--filter='labels.app_name=wfl AND labels.instance_id={self.instance_id}'`")
            info("    The `app_cluster` label is used to find cluster name, see wfl-instance documentation.")
            info(f"    Available clusters in {self.project}:", plain=True)
            info(shell(f"gcloud --project {self.project} container clusters list", quiet=True), plain=True)
            return None if self.force else exit(1)

    def __find_cluster_zone(self) -> str:
        """Look in the cluster list for the cluster's zone."""
        clusters = json.loads(shell(f"gcloud --project {self.project} --format=json "
                                    "container clusters list "
                                    f"--filter='name={self.cluster_name}'",
                                    quiet=True))
        if len(clusters) == 1:
            return clusters[0]["zone"]
        else:
            error(f"No cluster zone could be found for cluster named {self.cluster_name}")
            info("    Couldn't find a single match for "
                 f"`--filter='name={self.cluster_name}'`")
            return None if self.force else exit(1)

    def validate(self):
        """Validate stored configuration, exiting upon failure if not forced."""
        info(f"=>  Validating configuration")
        issues = 0
        if self.__cloudsql_exists():
            success(f"CloudSQL instance {self.cloudsql_name} exists")
        else:
            error(f"CloudSQL instance {self.cloudsql_name} not found")
            info(f"    Available instances in {self.project}:", plain=True)
            info(shell(f"gcloud --project {self.project} sql instances list", quiet=True), plain=True)
            issues += 1 if self.force else exit(1)
        if self.__cluster_exists():
            success(f"GKE cluster {self.cluster_name} exists")
        else:
            error(f"GKE cluster {self.cluster_name} not found")
            info(f"    Available clusters in {self.project}:", plain=True)
            info(shell(f"gcloud --project {self.project} container clusters list", quiet=True), plain=True)
            issues += 1 if self.force else exit(1)
        if self.__namespace_exists():
            success(f"Namespace {self.cluster_namespace} exists in cluster")
            shell(f"kubectl config set-context --current --namespace={self.cluster_namespace}")
            info("    Namespace now set")
        else:
            error(f"Namespace {self.cluster_namespace} not found in cluster")
            info(f"    Available namespaces in {self.cluster_name}:", plain=True)
            info(shell("kubectl get namespace", quiet=True), plain=True)
            issues += 1 if self.force else exit(1)
        if issues != 0:
            warn(f"{issues} issues but continuing due to '--force'")
        else:
            success("Validated configuration")

    def __cluster_exists(self) -> bool:
        """Check that the cluster exists."""
        clusters = json.loads(shell(f"gcloud --project {self.project} --format=json "
                                    f"container clusters list "
                                    f'{f"--zone {self.cluster_zone}" if self.cluster_zone else ""}'))
        return self.cluster_name in [c["name"] for c in clusters]

    def __cloudsql_exists(self) -> bool:
        """Check that the Cloud SQL instanc exists."""
        instances = json.loads(shell(f"gcloud --project {self.project} --format=json "
                                     f"sql instances list"))
        return self.cloudsql_name in [i["name"] for i in instances]

    def __namespace_exists(self) -> bool:
        """Configure K8s context and check that the namespace is present."""
        info(f"=>  Configuring Kubernetes for cluster {self.cluster_name}")
        shell(f"gcloud --project {self.project} container clusters get-credentials "
              f"{self.cluster_name} --zone {self.cluster_zone}")
        ctx = f"gke_{self.project}_{self.cluster_zone}_{self.cluster_name}"
        shell(f"kubectl config use-context {ctx}")
        try:
            namespaces = json.loads(shell("kubectl get namespace -o json", timeout=5))
            return self.cluster_namespace in [item["metadata"]["name"] for item in namespaces["items"]]
        except subprocess.TimeoutExpired:
            error("Namespace query took too long--maybe you need to be on non-split VPN?")
            return False


class InfoCommand(Config):
    """Class for info command that doesn't do anything special, just resolves/validates arguments."""

    def __init__(self, parsed_args: argparse.Namespace):
        super().__init__(parsed_args)

    @staticmethod
    def execute() -> int:
        return 0


class ConnectCommand(Config):
    """Class for connect command that enables `psql` to the Cloud SQL instance."""

    def __init__(self, parsed_args: argparse.Namespace):
        super().__init__(parsed_args)

    def execute(self) -> int:
        """Run the Cloud SQL proxy and print `psql` instructions."""
        container = self.run_cloudsql_proxy()
        success(f"You have connected to WFL's CloudSQL instance {self.cloudsql_name} "
                f"in {self.environment}'s {self.instance_id} wfl-instance")
        info(f'To use psql, run: \n\t psql "host=127.0.0.1 sslmode=disable  dbname=<DB_NAME> user=<USER_NAME>"')
        info(f"To disconnect and stop the container, run: \n\t docker stop {container}")
        return 0

    def run_cloudsql_proxy(self):
        """Use docker via gce-proxy to connect to the Cloud SQL instance."""
        info("=>  Running cloud_sql_proxy")
        token = shell("gcloud auth print-access-token")
        connectionName = json.loads(shell(f"gcloud --project {self.project} --format=json sql instances"
                                          f"describe {self.cloudsql_name}"))["connectionName"]
        return shell(f"docker run --rm -d -p 127.0.0.1:5432:5432 gcr.io/cloudsql-docker/gce-proxy:1.16 "
                     f"/cloud_sql_proxy -token='{token}' -instances='{connectionName}=tcp:0.0.0.0:5432'")


class DeployCommand(ConnectCommand):
    """Class for deploy command that deploys WFL to infrastructure."""

    def __init__(self, parsed_args: argparse.Namespace):
        super().__init__(parsed_args)

    def execute(self) -> int:
        """Set up helm, publish docker, render CTMPL, helm deploy, migrate liquibase."""

        if not input(f"Are you sure you want to deploy version {self.version}? [N/y]").lower().startswith("y"):
            exit(0)

        self.__set_up_helm()

        self.__publish_docker_images()

        deploy = os.path.join("derived", "helm", "deploy")
        if not os.path.exists(deploy):
            os.makedirs(deploy)

        values = "wfl-values.yaml"
        ctmpl = f"derived/2p/{self.gotc_deploy_folder}/helm/{values}.ctmpl"
        shutil.copy(ctmpl, deploy)

        render_ctmpl(ctmpl_file=f"{deploy}/{values}.ctmpl", WFL_VERSION=self.version)
        self.__helm_deploy_wfl(values=f"{deploy}/{values}")

        container = self.run_cloudsql_proxy()
        with open(f"{deploy}/{values}") as values_file:
            helm_values = yaml.safe_load(values_file)
            env = helm_values['api']['env']
            self.__run_liquibase_migration(env['ZERO_POSTGRES_USERNAME'], env['ZERO_POSTGRES_PASSWORD'])
        info("=>  Stopping cloud_sql_proxy")
        shell(f"docker stop {container}")

        success("Deployment is done!")
        info(shell("kubectl get pods"), plain=True)

        return 0

    @staticmethod
    def __set_up_helm():
        """Add helm repo and update."""
        info("=>  Setting up Helm charts")
        shell("helm repo add gotc-charts https://broadinstitute.github.io/gotc-helm-repo/")
        shell("helm repo update")
        success("Set up Helm charts")

    def __publish_docker_images(self):
        """Publish docker build."""
        info(f"=>  Publishing Docker images for version {self.version}")
        for module in ["api", "ui"]:
            shell(f"docker push broadinstitute/workflow-launcher-{module}:{self.version}")
        success("Published Docker images")

    def __helm_deploy_wfl(self, values: str):
        """Helm deploy using gotc-charts/wfl."""
        info(f"=>  Deploying to {self.cluster_name} in {self.cluster_namespace} namespace")
        info("    This must run on a non-split VPN", plain=True)
        shell(f"helm upgrade wfl-k8s gotc-charts/wfl -f {values} --install")
        success("WFL deployed")

    @staticmethod
    def __run_liquibase_migration(db_username: str, db_password: str):
        info("=>  Running liquibase migration")
        db_url = "jdbc:postgresql://localhost:5432/wfl?useSSL=false"
        changelog_dir = os.path.join(os.getcwd(), "database")
        shell(f"docker run --rm --net=host "
              f"-v {changelog_dir}:/liquibase/changelog liquibase/liquibase "
              f"--url='{db_url}' --changeLogFile=/changelog/changelog.xml "
              f"--username='{db_username}' --password='{db_password}' update")
        success("[✔] Ran liquibase migration")


class CLI:
    def __init__(self):
        """Set up the parser."""
        parser = argparse.ArgumentParser(description="Deploy or connect to WFL infrastructure instances",
                                         usage="%(prog)s [-h] [-d | -f] COMMAND [-e ENV] [-i INSTANCE] [...]")
        parser.add_argument("-d", "--dry-run", action="store_true",
                            help="Prevent COMMAND from enacting changes")
        parser.add_argument("-f", "--force", action="store_true",
                            help="Force COMMAND to run even if validation fails")
        commands = parser.add_argument_group("COMMAND")
        commands.add_argument("command",
                              type=lambda s: getattr(sys.modules[__name__], f"{s.title()}Command"),
                              choices=[DeployCommand, ConnectCommand, InfoCommand],
                              metavar="{deploy, connect, info}",
                              help="Deploy the local build to the instance, "
                                   "connect to the instance's Cloud SQL, "
                                   "or display instance config")
        typical = parser.add_argument_group("typical arguments",
                                            "(usually necessary to target a particular instance)")
        typical.add_argument("-e", "--environment", default="gotc-dev", metavar="ENV",
                             choices=["gotc-dev", "gotc-prod", "aou"],
                             help="Specify 'gotc-deploy/deploy/{ENV}' with one of: %(choices)s")
        typical.add_argument("-i", "--instance", default="dev",
                             help="Provide the ID of the target instance in the environment")
        specific = parser.add_argument_group("specific arguments",
                                             "(rarely necessary, inferred from typical arguments)")
        specific.add_argument("-v", "--version",
                              help="Specify the exact version to use instead of the 'version' file")
        specific.add_argument("--project",
                              help="Specify the exact GCP project name instead of 'broad-{ENV}'")
        specific.add_argument("--cloud-sql",
                              help="Specify the exact Cloud SQL name instead of inferring from labels")
        specific.add_argument("--zone",  # don't set default here so Config knows if this was set
                              help="Specify the exact GCP cluster zone instead of inferring from labels")
        specific.add_argument("--cluster",
                              help="Specify the exact GKE cluster name instead of inferring from labels")
        specific.add_argument("--namespace",
                              help="Specify the exact K8s namespace instead of '{INSTANCE}-wfl'")
        self.parser = parser

    def execute(self) -> int:
        """Run command."""
        args = self.parser.parse_args()
        for cmd in ["docker", "gcloud", "git", "helm", "java", "kubectl", "jq"]:
            if not shutil.which(cmd):
                error(f"{cmd} is not in PATH. Please install it!")
        config = args.command(args)
        config.validate()
        if config.dry_run:
            success("Exiting due to '--dry-run'")
            return 0
        else:
            return config.execute()


if __name__ == "__main__":
    exit(CLI().execute())
