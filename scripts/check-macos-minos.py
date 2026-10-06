#!/usr/bin/env python3
"""Fail unless every given macOS binary loads on exactly the macOS the build targets (#1086).

The expected version is pom.xml's macos.deployment.target, the value the native builds link with;
each binary's is the minos of its LC_BUILD_VERSION, which dyld enforces. Pure Python, so it runs
on the Linux job that publishes the release, against the very files it uploads. Anything else --
a fat binary, a missing LC_BUILD_VERSION -- fails rather than passing unchecked.

Usage: check-macos-minos.py BINARY...
"""
import re
import struct
import sys
from pathlib import Path

MH_MAGIC_64 = 0xFEEDFACF
LC_BUILD_VERSION = 0x32
PLATFORM_MACOS = 1


def version(packed):
    """xxxx.yy.zz nibble-packed, as Mach-O stores it."""
    return f"{packed >> 16}.{(packed >> 8) & 0xFF}" + (f".{packed & 0xFF}" if packed & 0xFF else "")


def build_version(path):
    """(minos, sdk) of a thin 64-bit Mach-O, reading only its header and load commands."""
    with open(path, "rb") as f:
        header = f.read(32)
        if len(header) < 32 or struct.unpack_from("<I", header)[0] != MH_MAGIC_64:
            raise ValueError("not a thin 64-bit Mach-O")
        ncmds, sizeofcmds = struct.unpack_from("<II", header, 16)
        commands = f.read(sizeofcmds)
    cursor = 0
    for _ in range(ncmds):
        cmd, size = struct.unpack_from("<II", commands, cursor)
        if cmd == LC_BUILD_VERSION:
            platform, minos, sdk = struct.unpack_from("<III", commands, cursor + 8)
            if platform == PLATFORM_MACOS:
                return version(minos), version(sdk)
        cursor += size
    raise ValueError("no macOS LC_BUILD_VERSION")


def main(paths):
    if not paths:
        sys.exit(__doc__)
    pom = Path(__file__).resolve().parent.parent / "pom.xml"
    expected = re.search(r"<macos\.deployment\.target>([^<]+)<", pom.read_text()).group(1)
    failed = False
    for path in paths:
        try:
            minos, sdk = build_version(path)
        except (OSError, ValueError, struct.error) as e:
            print(f"::error::{path}: {e}")
            failed = True
            continue
        if minos == expected:
            print(f"{path}: minos {minos} (sdk {sdk})")
        else:
            print(f"::error::{path} needs macOS {minos} (sdk {sdk}), but the supported minimum is "
                  f"{expected} (pom.xml macos.deployment.target)")
            failed = True
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main(sys.argv[1:])
