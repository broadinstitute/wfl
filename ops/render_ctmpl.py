#!/usr/bin/env python3
import argparse
from pathlib import Path

from util.misc import info, success, shell_unchecked


def render_ctmpl(ctmpl_file: str, **kwargs) -> int:
    """Render a ctmpl file."""
    info("=>  Rendering ctmpl file {ctmpl_file}")
    envs = ""
    if kwargs:
        for k, v in kwargs.items():
            envs += f"-e {k}={v}"
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
    args = parser.parse_args()
    file = Path(args.file)
    assert file.exists() and file.is_file(), f"{file} is not valid!"

    if not args.no_version:
        if args.version:
            version = args.version
        else:
            with open("version") as version_file:
                version = version_file.read().strip()
        exit(render_ctmpl(ctmpl_file=str(file), WFL_VERSION=version))
    else:
        exit(render_ctmpl(ctmpl_file=str(file)))
