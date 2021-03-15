#!/usr/bin/env python3
"""
WFL Deployment Script

usage: python3 cli.py -h
"""
import argparse
import json
import os
import re
import shutil
import subprocess
from dataclasses import dataclass
from pprint import PrettyPrinter
from typing import Callable, Dict, List

import yaml
from render_ctmpl import render_ctmpl
from util.misc import error, info, shell, success, warn


@dataclass
class WflInstanceConfig:
    """Data class representing the 'state' of this script's configuration as it interacts with infrastructure."""
    command: str
    environment: None
    instance_id: None
    dry_run: bool = False
    version: str = None
    project: str = None
    cloud_sql_name: str = None
    cloud_sql_local_proxy_container: str = None
    cluster_zone: str = None
    cluster_name: str = None
    cluster_namespace: str = None
    db_username: str = None
    db_password: str = None
    db_connection_name: str = None
    rendered_values_file: str = None
    vault_token_path: str = None
    wfl_root_folder: str = f"{os.path.dirname(os.path.realpath(__file__))}/.."
    current_changelog: str = None


def check_env_instance_present(config: WflInstanceConfig) -> None:
    if not config.environment or not config.instance_id:
        info(shell(f"{config.wfl_root_folder}/cli.py -h", quiet=True), plain=True)
        error(f"No ENV and INSTANCE passed, this is required for the {config.command} command")
        exit(1)


def read_version(config: WflInstanceConfig) -> None:
    if not config.version:
        info("=>  Reading version from file at `./version`")
        with open(f"{config.wfl_root_folder}/version") as version_file:
            config.version = version_file.read().strip()
    else:
        info(f"=>  Version overridden, using {config.version} instead of `./version`")


def infer_missing_arguments_pre_validate(config: WflInstanceConfig) -> None:
    """Infer and store all arguments required for validation steps."""
    if not config.project:
        info("=>  Inferring project name to be `broad-{ENV}`")
        config.project = f"broad-{config.environment}"


def validate_cloud_sql_name(config: WflInstanceConfig) -> None:
    """Validate that the stored Cloud SQL name exists."""
    if config.cloud_sql_name:
        info("=>  Validating Cloud SQL name")
        instances = json.loads(shell(f"gcloud --project {config.project} --format=json "
                                     "sql instances list"))
        if config.cloud_sql_name not in [i["name"] for i in instances]:
            error(f"Cloud SQL instance {config.cloud_sql_name} not found")
            info(f"    Available Cloud SQL instances in {config.project}:", plain=True)
            info(shell(f"gcloud --project {config.project} sql instances list", quiet=True), plain=True)
            exit(1)
        else:
            success(f"Cloud SQL instance {config.cloud_sql_name} exists")


def validate_cluster_name(config: WflInstanceConfig) -> None:
    """Validate that the stored cluster name exists."""
    if config.cluster_name:
        info("=>  Validating GKE cluster name")
        clusters = json.loads(shell(f"gcloud --project {config.project} --format=json "
                                    f"container clusters list"))
        if config.cluster_name not in [c["name"] for c in clusters]:
            error(f"GKE cluster {config.cluster_name} not found")
            info(f"    Available clusters in {config.project}:", plain=True)
            info(shell(f"gcloud --project {config.project} container clusters list", quiet=True), plain=True)
            exit(1)
        else:
            success(f"GKE cluster {config.cluster_name} exists")


def infer_missing_arguments(config: WflInstanceConfig) -> None:
    """Infer and store all arguments not required for validation steps."""
    if not config.cloud_sql_name:
        info("=>  Inferring Cloud SQL from GCP labels")
        config.cloud_sql_name = \
            json.loads(shell(f"gcloud --project {config.project} --format=json "
                             "sql instances list "
                             f"--filter='labels.app_name=wfl AND labels.instance_id={config.instance_id}'",
                             quiet=True))[0]["name"]
    if not config.db_connection_name:
        info("=>  Fetching Cloud SQL connection name")
        config.db_connection_name = json.loads(shell(f"gcloud --project {config.project} --format=json sql instances "
                                                     f"describe {config.cloud_sql_name}",
                                                     quiet=True))["connectionName"]
    if not config.cluster_name:
        info("=>  Inferring GKE cluster name from GCP labels")
        config.cluster_name = \
            json.loads(shell(f"gcloud --project {config.project} --format=json "
                             "sql instances list "
                             f"--filter='labels.app_name=wfl AND labels.instance_id={config.instance_id}'",
                             quiet=True))[0]["settings"]["userLabels"]["app_cluster"]
        info("=>  Inferring GKE cluster zone from cluster list")
    if not config.cluster_zone:
        config.cluster_zone = json.loads(shell(f"gcloud --project {config.project} --format=json "
                                               "container clusters list "
                                               f"--filter='name={config.cluster_name}'",
                                               quiet=True))[0]["zone"]
    if not config.cluster_namespace:
        info("=>  Inferring Kubernetes namespace to be `{INSTANCE}-wfl`")
        config.cluster_namespace = f"{config.instance_id}-wfl"
    success("Inference complete")


def print_config(config: WflInstanceConfig) -> None:
    """Print the entire config, filtering out any field containing password."""
    info("=>  Current script configuration:")
    PrettyPrinter().pprint({k: v for (k, v) in vars(config).items() if "password" not in k})


def configure_kubectl(config: WflInstanceConfig) -> None:
    """Configure kubectl to use the stored cluster/namespace."""
    info(f"=>  Configuring Kubernetes for cluster {config.cluster_name}")
    shell(f"gcloud --project {config.project} container clusters get-credentials "
          f"{config.cluster_name} --zone {config.cluster_zone}")
    ctx = f"gke_{config.project}_{config.cluster_zone}_{config.cluster_name}"
    shell(f"kubectl config use-context {ctx}")
    try:
        namespaces = json.loads(shell("kubectl get namespace -o json", timeout=10))
        if config.cluster_namespace not in [item["metadata"]["name"] for item in namespaces["items"]]:
            error(f"Namespace {config.cluster_namespace} not found in {config.cluster_name}")
            info(f"    Available namespaces in {config.cluster_name}:", plain=True)
            info(shell("kubectl get namespace", quiet=True), plain=True)
            exit(1)
        else:
            shell(f"kubectl config set-context --current --namespace={config.cluster_namespace}")
            success("Kubernetes configured")
    except subprocess.TimeoutExpired as timeout:
        warn("Namespace query took too long--maybe you need to be on non-split VPN?")
        raise timeout


def configure_helm(config: WflInstanceConfig) -> None:
    """Configure helm to connect to gotc-helm-repo."""
    info(f"=>  Setting up Helm charts ahead of {config.command}")
    shell("helm repo add gotc-charts https://broadinstitute.github.io/gotc-helm-repo/")
    shell("helm repo update")
    success("Set up Helm charts")


def render_values_file(config: WflInstanceConfig) -> None:
    """Render the values file and store pertinent info from it in the config."""
    deploy = os.path.join("derived", "helm", "deploy")
    if not os.path.exists(deploy):
        os.makedirs(deploy)
    values = "wfl-values.yaml"
    config.rendered_values_file = os.path.join(deploy, values)
    ctmpl = f"{config.wfl_root_folder}/derived/2p/gotc-deploy/deploy/{config.environment}/helm/{values}.ctmpl"
    shutil.copy(ctmpl, deploy)
    render_ctmpl(ctmpl_file=f"{config.rendered_values_file}.ctmpl",
                 vault_token_path=config.vault_token_path,
                 WFL_VERSION=config.version,
                 WFL_DB_URL=f"'jdbc:postgresql://google/wfl?cloudSqlInstance={config.db_connection_name}"
                            f"&socketFactory=com.google.cloud.sql.postgres.SocketFactory'",
                 WFL_INSTANCE=config.instance_id)
    with open(config.rendered_values_file) as values_file:
        helm_values = yaml.safe_load(values_file)
        env = helm_values["api"]["env"]
        config.db_username = env.get("WFL_POSTGRES_USERNAME", env.get("ZERO_POSTGRES_USERNAME"))
        config.db_password = env.get("WFL_POSTGRES_PASSWORD", env.get("ZERO_POSTGRES_PASSWORD"))
    if not config.db_username:
        error("WFL_POSTGRES_USERNAME or ZERO_POSTGRES_USERNAME not found in helm values.")
        exit(1)
    if not config.db_password:
        error("WFL_POSTGRES_PASSWORD or ZERO_POSTGRES_PASSWORD not found in helm values.")
        exit(1)

def exit_if_dry_run(config: WflInstanceConfig) -> None:
    """Exit if the config is storing True for the dry_run flag."""
    if config.dry_run:
        warn("Exiting due to --dry-run")
        exit(0)


def configure_cloud_sql_proxy(config: WflInstanceConfig) -> None:
    """Initiate the Cloud SQL proxy and store the container name in the config."""
    info("=>  Running cloud_sql_proxy")
    token = shell("gcloud auth print-access-token")
    config.cloud_sql_local_proxy_container = \
        shell(f"docker run --rm -d -p 127.0.0.1:5432:5432 gcr.io/cloudsql-docker/gce-proxy:1.16 "
              f"/cloud_sql_proxy -token='{token}' -instances='{config.db_connection_name}=tcp:0.0.0.0:5432'",
              quiet=True)


def print_cloud_sql_proxy_instructions(config: WflInstanceConfig) -> None:
    """Print instructions for connecting to the Cloud SQL proxy contained stored in the config."""
    success(f"You have connected to WFL's CloudSQL instance {config.cloud_sql_name} "
            f"in {config.environment}'s {config.instance_id} wfl-instance")
    info(f'To use psql, run: \n\t psql "host=127.0.0.1 sslmode=disable  dbname=<DB_NAME> user=<USER_NAME>"')
    info(f"To disconnect and stop the container, run: \n\t docker stop {config.cloud_sql_local_proxy_container}")


def prompt_deploy_version(config: WflInstanceConfig) -> None:
    """Verify that the user would like to deploy the stored version."""
    if not input(f"Are you sure you want to deploy version {config.version}? [N/y]: ").lower().startswith("y"):
        exit(0)


def publish_docker_images(config: WflInstanceConfig) -> None:
    """Publish existing docker images for the stored version."""
    info(f"=>  Publishing Docker images for version {config.version}")
    for module in ["api", "ui"]:
        shell(f"docker push broadinstitute/workflow-launcher-{module}:{config.version}")
    success("Published Docker images")


def helmfile_deploy_wfl(config: WflInstanceConfig) -> None:
    """Deploy the pushed docker images for the stored version to the stored cluster."""
    info(f"=>  Deploying to {config.cluster_name} in {config.cluster_namespace} namespace")
    info("    This must run on a non-split VPN", plain=True)
    shell(f"helmfile --selector name={config.instance_id}-wfl sync")
    success("WFL deployed")


def run_liquibase_migration(config: WflInstanceConfig) -> None:
    """Run the liquibase migration using stored credentials from rendering the values file."""
    info("=>  Running liquibase migration")
    db_url = "jdbc:postgresql://localhost:5432/wfl?useSSL=false"
    changelog_dir = f"{config.wfl_root_folder}/database"
    shell(f"docker run --rm --net=host "
          f"-v {changelog_dir}:/liquibase/changelog liquibase/liquibase "
          f"--url='{db_url}' --changeLogFile=/changelog/changelog.xml "
          f"--username='{config.db_username}' --password='{config.db_password}' update", quiet=True)
    success("Ran liquibase migration")


def stop_cloud_sql_proxy(config: WflInstanceConfig) -> None:
    """Stop the stored Cloud SQL proxy container."""
    info("=>  Stopping cloud_sql_proxy")
    shell(f"docker stop {config.cloud_sql_local_proxy_container}")


def print_deployment_success(config: WflInstanceConfig) -> None:
    """Print success and get pods from existing kubectl context."""
    success(f"{config.command} is done!")
    info(shell("kubectl get pods"), plain=True)


def make_git_tag(config: WflInstanceConfig) -> None:
    info("=>  Tagging current commit with version")
    shell(f"git tag -a v{config.version} -m 'Created by cli.py {config.command}'", cwd=config.wfl_root_folder)
    shell(f"git push origin v{config.version}", cwd=config.wfl_root_folder)
    success(f"Tag 'v{config.version}' created and pushed")


def check_git_tag(config: WflInstanceConfig) -> None:
    info("=>  Checking current commit tags for version")
    if not any(t == f"v{config.version}" for t
               in shell(f"git tag --points-at HEAD", cwd=config.wfl_root_folder, quiet=True).splitlines()):
        error(f"No tag 'v{config.version}' found--did you check out the right tag?")
        info("This is necessary because liquibase changelogs are read from the repo itself", plain=True)
        info("Tags on the current commit, if any:", plain=True)
        info(shell("git tag --points-at HEAD", cwd=config.wfl_root_folder, quiet=True), plain=True)
        exit(1)


def _markdownify_commit_msg(commit: str) -> str:
    "Turn a single commit message to markdown style."
    try:
        regex = re.compile("\#[0-9][0-9][0-9]")
        num_pr = regex.search(commit)[0]
        marked_commit = regex.sub(f"[\g<0>](https://github.com/broadinstitute/wfl/pull/{num_pr[1:]})", commit)
        return f'- {marked_commit}'
    except:
        return f'- {commit}'


def get_git_commits_since_last_tag(config: WflInstanceConfig) -> None:
    "Read commit messages since last tag, store to config and print."
    command = 'git log --pretty=format:"%s" $(git describe --tags --abbrev=0 HEAD^)..HEAD'
    info("=>  Reading commit messages from git log")
    lines = shell(command).split("\n")
    info("=>  Markdown-ify log messages")
    current_changelog = "\n".join([_markdownify_commit_msg(line) for line in lines])
    config.current_changelog = current_changelog
    info("=>  Current changelog crafted")
    info(current_changelog)


def write_changelog(config: WflInstanceConfig) -> None:
    "Append current changelog info to the changelog file at start position."
    content = f"# Release {config.version}\n" + config.current_changelog
    changelog_location = f"{config.wfl_root_folder}/CHANGELOG.md"

    info("=>  Loading changelog from file at `./CHANGELOG.md`")
    shell(f"touch {changelog_location}")
    with open(changelog_location, "r") as fp:
        existing = fp.read().strip()

    with open(changelog_location, "w") as fp:
        fp.write(content)
        fp.write("\n\n")
        fp.write(existing)
    success(f"Changelog is successfully written to {changelog_location}")


command_mapping: Dict[str, List[Callable[[WflInstanceConfig], None]]] = {
    "release": [
        read_version,
        get_git_commits_since_last_tag,
        exit_if_dry_run,
        write_changelog
    ],
    "info": [
        check_env_instance_present,
        read_version,
        infer_missing_arguments_pre_validate,
        validate_cloud_sql_name,
        validate_cluster_name,
        infer_missing_arguments,
        print_config
    ],
    "connect": [
        check_env_instance_present,
        read_version,
        infer_missing_arguments_pre_validate,
        validate_cloud_sql_name,
        infer_missing_arguments,
        print_config,
        exit_if_dry_run,
        configure_cloud_sql_proxy,
        print_cloud_sql_proxy_instructions
    ],
    "deploy-from-tag": [
        check_env_instance_present,
        read_version,
        infer_missing_arguments_pre_validate,
        validate_cloud_sql_name,
        validate_cluster_name,
        infer_missing_arguments,
        check_git_tag,
        configure_kubectl,
        print_config,
        exit_if_dry_run,
        configure_cloud_sql_proxy,
        helmfile_deploy_wfl,
        run_liquibase_migration,
        stop_cloud_sql_proxy,
        print_deployment_success
    ],
    "tag-and-push-images": [
        read_version,
        exit_if_dry_run,
        make_git_tag,
        publish_docker_images
    ]
}


def cli() -> WflInstanceConfig:
    """Configure the arguments, help text, and parsing."""
    parser = argparse.ArgumentParser(description="deploy or connect to WFL infrastructure",
                                     usage="%(prog)s [-h] [-d] COMMAND [ENV INSTANCE] [...]")
    parser.add_argument("command", choices=command_mapping.keys(), metavar="COMMAND",
                        help=f"one of [{', '.join(command_mapping.keys())}]")
    parser.add_argument("environment", choices=["gotc-dev", "gotc-prod", "aou"], metavar="ENV", nargs="?",
                        help="specify 'gotc-deploy/deploy/{ENV}' with one of [%(choices)s], if applicable")
    parser.add_argument("instance_id", metavar="INSTANCE", nargs="?",
                        help="specify the ID of the instance to use in the environment, if applicable")
    parser.add_argument("-d", "--dry-run", action="store_true",
                        help="exit before COMMAND makes any remote changes")
    parser.add_argument("-v", "--version",
                        help="specify the version to use instead of the 'version' file")
    parser.add_argument("--project",
                        help="specify the GCP project name instead of 'broad-{ENV}'")
    parser.add_argument("--cloud-sql-name",
                        help="specify the Cloud SQL name instead of inferring from labels")
    parser.add_argument("--cluster-zone",
                        help="specify the zone for the GKE cluster instead of inferring from CLUSTER_NAME")
    parser.add_argument("--cluster-name",
                        help="specify the GKE cluster name instead of inferring from labels")
    parser.add_argument("--cluster-namespace",
                        help="specify the K8s namespace instead of '{INSTANCE}-wfl'")
    parser.add_argument("--vault-token-path", default='"$HOME"/.vault-token',
                        help="for operations that need a vault token, specify a specific path to one")
    return WflInstanceConfig(**vars(parser.parse_args()))


def main() -> int:
    """Call `cli` and run each function in the command mapping in order."""
    config = cli()
    try:
        for func in command_mapping[config.command]:
            func(config)
        return 0
    except Exception as err:
        error(str(err))
        return 1


if __name__ == "__main__":
    exit(main())
