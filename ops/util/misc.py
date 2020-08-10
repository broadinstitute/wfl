"""
Console and shell utilities shared among ops scripts.
"""

import subprocess
import sys
from enum import Enum
from typing import Optional


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


def info(message: str, plain=False):
    """Log the info to stdout"""
    print(_apply_color("default", message) if not plain else message)


def success(message: str):
    """Log the success to stdout"""
    print(_apply_color("green", f"[✔] {message}"))


def error(message: str):
    """Log the error to stderr"""
    print(_apply_color("red", f"[✗] {message}"), file=sys.stderr)


def warn(message: str):
    """Log the warning to stdout"""
    print(_apply_color("yellow", f"[!] {message}"))


def shell(command: str, quiet: bool = False, timeout: Optional[float] = None) -> str:
    """Run COMMAND in a subprocess."""
    if not quiet:
        info(f"Running: {command}")
    try:
        return subprocess.check_output(command, shell=True, timeout=timeout, encoding="utf-8").strip()
    except subprocess.CalledProcessError as err:
        error(f"Error running: {command}")
        error(err.output)
        exit(err.returncode)
