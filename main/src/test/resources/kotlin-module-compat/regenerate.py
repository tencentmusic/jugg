"""Generate real compiler fixtures and optionally verify Jugg's merged indexes.

Missing Maven artifacts are downloaded into a temporary cache. JVM tests only use
committed fixtures and do not invoke this script or download compiler toolchains.
"""
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import xml.etree.ElementTree as ET

CACHE = Path.home() / ".gradle/caches/modules-2/files-2.1"
WORK = Path(tempfile.gettempdir()) / "jugg-kotlin-module-compat"
FIXTURES = Path(__file__).resolve().parent
VERSIONS = [
    "1.3.72", "1.4.32", "1.5.31", "1.6.21", "1.7.22", "1.8.21",
    "1.9.23", "2.0.21", "2.1.20", "2.2.21", "2.3.20", "2.4.21",
]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
WORK.mkdir(exist_ok=True)


def artifact(group, name, version, extension):
    filename = f"{name}-{version}.{extension}"
    cached = list((CACHE / group / name / version).glob("*/" + filename))
    if cached:
        return cached[0]
    target = WORK / filename
    if not target.exists():
        url = f"https://repo.maven.apache.org/maven2/{group.replace('.', '/')}/{name}/{version}/{filename}"
        print("download", filename, flush=True)
        partial = target.with_suffix(target.suffix + ".part")
        urllib.request.urlretrieve(url, partial)
        partial.replace(target)
    return target


def dependencies(group, name, version, seen):
    key = (group, name, version)
    if key in seen:
        return []
    seen.add(key)
    jars = [artifact(group, name, version, "jar")]
    pom = ET.parse(artifact(group, name, version, "pom"))
    for dependency in pom.findall("m:dependencies/m:dependency", NS):
        fields = {child.tag.split("}")[-1]: child.text for child in dependency}
        if fields.get("scope") in ("test", "provided") or fields.get("optional") == "true":
            continue
        jars += dependencies(fields["groupId"], fields["artifactId"], fields["version"], seen)
    return jars


def compiler_command(version):
    jars = dependencies("org.jetbrains.kotlin", "kotlin-compiler-embeddable", version, set())
    stdlib = artifact("org.jetbrains.kotlin", "kotlin-stdlib", version, "jar")
    annotations = artifact("org.jetbrains", "annotations", "13.0", "jar")
    return [
        "java", "--add-opens=java.base/java.util=ALL-UNNAMED",
        "-cp", os.pathsep.join(map(str, jars)), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-no-stdlib", "-no-reflect", "-classpath", str(stdlib) + os.pathsep + str(annotations),
        "-module-name", "compat",
    ]


def generate(version, command):
    target = FIXTURES / version
    target.mkdir(exist_ok=True)
    for stage, name in [("baseline", "Baseline"), ("incremental", "Added")]:
        source = WORK / version / stage
        source.mkdir(parents=True, exist_ok=True)
        (source / (name + ".kt")).write_text(
            f"package example\nfun {stage}() = 1\nfun Int.{stage}Extension() = this\n"
        )
        (source / (name + "Part.kt")).write_text(
            '@file:JvmName("Shared")\n@file:JvmMultifileClass\npackage example\n'
            f"fun {stage}Part() = 1\n"
        )
        classes = source / "classes"
        result = subprocess.run(
            command + ["-d", str(classes)] + list(map(str, source.glob("*.kt"))),
            capture_output=True, text=True,
        )
        if result.returncode:
            raise RuntimeError(version + " " + result.stdout + result.stderr)
        shutil.copyfile(classes / "META-INF/compat.kotlin_module", target / (stage + ".kotlin_module"))
        print(version, stage, "ok", flush=True)


def verify_merged(version, command):
    directory = WORK / version
    classpath = directory / "merged-classes"
    for stage in ["baseline", "incremental"]:
        shutil.copytree(directory / stage / "classes", classpath, dirs_exist_ok=True)
    merged = FIXTURES.parents[4] / "main/build/kotlin-module-compat" / version / "compat.kotlin_module"
    shutil.copyfile(merged, classpath / "META-INF/compat.kotlin_module")
    source = directory / "Caller.kt"
    source.write_text(
        "package caller\nimport example.*\nfun check() = baseline() + incremental() + "
        "1.baselineExtension() + 1.incrementalExtension()\n"
    )
    command = command.copy()
    index = command.index("-classpath") + 1
    command[index] += os.pathsep + str(classpath)
    subprocess.run(command + ["-d", str(directory / "caller-classes"), str(source)], check=True)
    print(version, "merged declarations resolved", flush=True)


for compiler_version in VERSIONS:
    compiler = compiler_command(compiler_version)
    generate(compiler_version, compiler)
    if "--verify-merged" in sys.argv:
        verify_merged(compiler_version, compiler)
