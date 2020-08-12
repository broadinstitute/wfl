#!/usr/bin/env python3
import argparse
import os
import shutil
import sys
from pathlib import Path

from util.misc import info, success, error, shell_unchecked

# In case this will need to run on Windows systems
if sys.platform.lower() == "win32":
    os.system("color")


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
    args = parser.parse_args()
    file = Path(args.file)
    assert file.exists() and file.is_file(), f"{file} is not valid!"

    if args.version:
        version = args.version
    else:
        with open("version") as version_file:
            version = version_file.read().strip()

    if not shutil.which("docker"):
        error("Docker is not in PATH. Please install it!")

    exit(render_ctmpl(ctmpl_file=str(file), WFL_VERSION=version))
