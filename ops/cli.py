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
    dry_run: bool = False
    version: str = None
    wfl_root_folder: str = f"{os.path.dirname(os.path.realpath(__file__))}"
    current_changelog: str = None


def read_version(config: WflInstanceConfig) -> None:
    if not config.version:
        info("=>  Reading version from file at `./version`")
        with open(f"{config.wfl_root_folder}/../version") as version_file:
            config.version = version_file.read().strip()
    else:
        info(f"=>  Version overridden, using {config.version} instead of `./version`")


def exit_if_dry_run(config: WflInstanceConfig) -> None:
    """Exit if the config is storing True for the dry_run flag."""
    if config.dry_run:
        warn("Exiting due to --dry-run")
        exit(0)


def publish_docker_images(config: WflInstanceConfig) -> None:
    """Publish existing docker images for the stored version."""
    info(f"=>  Publishing Docker images for version {config.version}")
    for module in ["api", "ui"]:
        shell(f"docker push broadinstitute/workflow-launcher-{module}:{config.version}")
    success("Published Docker images")


def make_git_tag(config: WflInstanceConfig) -> None:
    info("=>  Tagging current commit with version")
    shell(f"git tag -a v{config.version} -m 'Created by cli.py {config.command}'", cwd=config.wfl_root_folder)
    shell(f"git push origin v{config.version}", cwd=config.wfl_root_folder)
    success(f"Tag 'v{config.version}' created and pushed")


def _markdownify_commit_msg(commit: str) -> str:
    """Turn a single commit message to markdown style."""
    try:
        regex = re.compile("\#[0-9][0-9][0-9]")
        num_pr = regex.search(commit)[0]
        marked_commit = regex.sub(f"[\g<0>](https://github.com/broadinstitute/wfl/pull/{num_pr[1:]})", commit)
        return f'- {marked_commit}'
    except:
        return f'- {commit}'


def get_git_commits_since_last_tag(config: WflInstanceConfig) -> None:
    """Read commit messages since last tag, store to config and print."""
    command = 'git log --pretty=format:"%s" $(git describe --tags --abbrev=0 HEAD^)..HEAD'
    info("=>  Reading commit messages from git log")
    lines = shell(command).split("\n")
    info("=>  Markdown-ify log messages")
    current_changelog = "\n".join([_markdownify_commit_msg(line) for line in lines])
    config.current_changelog = current_changelog
    info("=>  Current changelog crafted")
    info(current_changelog)


def write_changelog(config: WflInstanceConfig) -> None:
    """Append current changelog info to the changelog file at start position."""
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

    parser.add_argument("-d", "--dry-run", action="store_true",
                        help="exit before COMMAND makes any remote changes")

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
