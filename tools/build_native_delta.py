#!/usr/bin/env python3
"""Package pinned HDiffPatch host tools and build Android patchers with NDK r28b."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import urllib.request
import zipfile

VERSION = "5.1.3"
SOURCE_SHA256 = "f9cda55934a1251c8303bcc150656a129fb4fbdd47ba3374d1733682497fc158"
ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "main/src/main/resources/tools/hdiffpatch" / VERSION
HOSTS = {
    "macos": "9e9d7318db1ea5607dbdde41e6614cfe5b73c589034b38ee91a79f4ca80a940c",
    "linux64": "628963bf2ee9108a97260fa5eef44acd9ec94369b76090a957c9182b3abbb558",
    "linux_arm64": "03e404e16d06479deaba645a09ed5c06636778b083b82bc7fc932ba34425430b",
    "windows64": "77f141386e5d8f785c1c846e10fbbc19b6c05aa00e3f59cc44670fb3f0e2ae94",
}
ANDROID_TARGETS = {
    "arm64-v8a": "aarch64-linux-android",
    "armeabi-v7a": "armv7a-linux-androideabi",
    "x86_64": "x86_64-linux-android",
    "x86": "i686-linux-android",
}


def download(url):
    print("Download", url, flush=True)
    with urllib.request.urlopen(url, timeout=90) as response:
        return response.read()


def package_hosts(manifest):
    for host in HOSTS:
        url = (f"https://github.com/sisong/HDiffPatch/releases/download/v{VERSION}/"
               f"hdiffpatch_v{VERSION}_bin_{host}.zip")
        data = download(url)
        digest = hashlib.sha256(data).hexdigest()
        if digest != HOSTS[host]:
            raise RuntimeError("HDiffPatch host archive checksum mismatch: " + host)
        manifest["downloads"][url] = digest
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            for name in archive.namelist():
                if Path(name).name not in ("hdiffz", "hpatchz", "hdiffz.exe", "hpatchz.exe"):
                    continue
                target = OUTPUT / host / Path(name).name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(archive.read(name))
                target.chmod(0o755)


def build_android(toolchain, manifest):
    url = f"https://codeload.github.com/sisong/HDiffPatch/zip/refs/tags/v{VERSION}"
    data = download(url)
    digest = hashlib.sha256(data).hexdigest()
    if digest != SOURCE_SHA256:
        raise RuntimeError("HDiffPatch source checksum mismatch")
    manifest["downloads"][url] = digest
    with tempfile.TemporaryDirectory(prefix="jugg-hpatch-build-") as tmp:
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            archive.extractall(tmp)
        source = Path(tmp) / f"HDiffPatch-{VERSION}"
        shutil.copyfile(source / "LICENSE", OUTPUT / "LICENSE.txt")
        for abi, triple in ANDROID_TARGETS.items():
            build = Path(tmp) / abi
            shutil.copytree(source, build)
            compiler = toolchain / f"{triple}26-clang"
            subprocess.run([
                "make", "-j4", "hpatchz", f"CC={compiler}", "MT=0", "LDEF=0",
                "ZLIB=2", "LZMA=0", "ZSTD=0", "BZIP2=0", "BSD=0", "VCD=0",
                "MD5=0", "XXH=0", "DIR_DIFF=0",
                "PATCH_LINK=-lz -pie -Wl,-z,max-page-size=16384",
            ], cwd=build, check=True, env={**os.environ, "CFLAGS": "-fPIE"})
            target = OUTPUT / "android" / abi / "hpatchz"
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(build / "hpatchz", target)
            subprocess.run([str(toolchain / "llvm-strip"), str(target)], check=True)
            target.chmod(0o755)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ndk", required=True, type=Path)
    args = parser.parse_args()
    if "Pkg.Revision = 28.2.13676358" not in (args.ndk / "source.properties").read_text():
        raise RuntimeError("Use pinned NDK 28.2.13676358 to rebuild Android patchers")
    toolchain = next((args.ndk / "toolchains/llvm/prebuilt").iterdir()) / "bin"
    manifest = {"version": VERSION, "android_ndk": "28.2.13676358", "android_api": 26,
                "downloads": {}, "files": {}}
    OUTPUT.mkdir(parents=True, exist_ok=True)
    package_hosts(manifest)
    build_android(toolchain, manifest)
    for path in sorted(OUTPUT.rglob("*")):
        if path.is_file() and path.name != "manifest.json":
            manifest["files"][path.relative_to(OUTPUT).as_posix()] = hashlib.sha256(path.read_bytes()).hexdigest()
    (OUTPUT / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")


if __name__ == "__main__":
    main()
