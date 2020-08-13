#!/usr/bin/env python3
import argparse
from pathlib import Path

from util.misc import info, success, shell_unchecked


def render_ctmpl(ctmpl_file: str, **kwargs) -> int:
    """Render a ctmpl file."""
    info(f"=>  Rendering ctmpl file {ctmpl_file}")
    envs = " ".join([f"-e {k}={v}" for k, v in kwargs.items()]) if kwargs else ""
    info(f"=>  Feeding variables: {envs}")
    shell_unchecked(" ".join(['docker run -i --rm -v "$(pwd)":/working',
                              '-v "$HOME"/.vault-token:/root/.vault-token',
                              f'{envs}',
                              'broadinstitute/dsde-toolbox:dev',
                              '/usr/local/bin/render-ctmpls.sh -k',
                              f'"{ctmpl_file}"']))
    success(f"Rendered {ctmpl_file.split('.ctmpl')[0]}")
    return 0


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("file", help="CTMPL file to be rendered")
    parser.add_argument("-v", "--version", help="A specific version to use other than the root's `version` file")
    parser.add_argument("--no-version", action="store_true", help="Don't pass a WFL_VERSION variable")
    parser.add_argument("-e", "--environment", metavar="KEY=VALUE", action="append",
                        help="Pass extra things in the environment (can exist multiple times)")
    args = parser.parse_args()
    file = Path(args.file)
    assert file.exists() and file.is_file(), f"{file} is not valid!"

    # We have to run render-ctmpls.sh unchecked, so manually parse arguments here despite reassembly later
    # to help catch issues that would otherwise silently fail
    environment = {k: v for k, v in map(lambda i: i.split("="), args.environment)} if args.environment else {}

    if not args.no_version:
        if args.version:
            environment["WFL_VERSION"] = args.version
        else:
            with open("version") as version_file:
                environment["WFL_VERSION"] = version_file.read().strip()

    exit(render_ctmpl(ctmpl_file=str(file), **environment))
