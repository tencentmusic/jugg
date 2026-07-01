#!/usr/bin/env python3
"""Check Python script compatibility declarations against source files."""

from __future__ import annotations

import argparse
import ast
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
from typing import Iterable, List, Optional, Sequence, Tuple


ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "docs" / "skills" / "python_compat.json"
LOW_VERSION_ANNOTATION_NAMES = {"list", "dict", "tuple", "set", "frozenset", "type"}


class CompatError(Exception):
    """Raised when a script violates its declared Python compatibility target."""


class AnnotationCompatVisitor(ast.NodeVisitor):
    """Find import-time annotation forms that require postponed annotations on Python 3.7."""

    def __init__(self) -> None:
        self.issues: List[Tuple[int, str]] = []

    def visit_FunctionDef(self, node: ast.FunctionDef) -> None:
        self._check_function(node)
        self.generic_visit(node)

    def visit_AsyncFunctionDef(self, node: ast.AsyncFunctionDef) -> None:
        self._check_function(node)
        self.generic_visit(node)

    def visit_AnnAssign(self, node: ast.AnnAssign) -> None:
        self._check_annotation(node.annotation)
        self.generic_visit(node)

    def _check_function(self, node: ast.FunctionDef) -> None:
        args = list(node.args.args) + list(node.args.kwonlyargs)
        if node.args.vararg is not None:
            args.append(node.args.vararg)
        if node.args.kwarg is not None:
            args.append(node.args.kwarg)
        for arg in args:
            if arg.annotation is not None:
                self._check_annotation(arg.annotation)
        if node.returns is not None:
            self._check_annotation(node.returns)

    def _check_annotation(self, node: ast.AST) -> None:
        for child in ast.walk(node):
            if isinstance(child, ast.BinOp) and isinstance(child.op, ast.BitOr):
                self.issues.append((child.lineno, "PEP 604 union annotation requires postponed annotations"))
            if isinstance(child, ast.Subscript) and self._is_low_version_builtin(child.value):
                self.issues.append((child.lineno, "builtin generic annotation requires postponed annotations"))

    @staticmethod
    def _is_low_version_builtin(node: ast.AST) -> bool:
        return isinstance(node, ast.Name) and node.id in LOW_VERSION_ANNOTATION_NAMES


def load_manifest() -> dict:
    with MANIFEST.open("r", encoding="utf-8") as fh:
        return json.load(fh)


def parse_version(value: str) -> Tuple[int, int]:
    parts = value.split(".")
    if len(parts) != 2:
        raise CompatError("min_python must use MAJOR.MINOR format")
    return int(parts[0]), int(parts[1])


def python_files(root: Path) -> List[Path]:
    return sorted(
        path
        for path in root.rglob("*.py")
        if "__pycache__" not in path.parts
    )


def has_future_annotations(tree: ast.Module) -> bool:
    body = list(tree.body)
    if body and isinstance(body[0], ast.Expr) and isinstance(getattr(body[0], "value", None), ast.Constant):
        if isinstance(body[0].value.value, str):
            body = body[1:]
    for node in body:
        if isinstance(node, ast.ImportFrom) and node.module == "__future__":
            return any(alias.name == "annotations" for alias in node.names)
        if not isinstance(node, ast.ImportFrom):
            return False
    return False


def check_file(path: Path, min_version: Tuple[int, int]) -> List[str]:
    source = path.read_text(encoding="utf-8")
    errors: List[str] = []
    try:
        ast.parse(source, filename=str(path), feature_version=min_version)
    except SyntaxError as exc:
        errors.append(f"{path}:{exc.lineno}: syntax is not valid for Python {min_version[0]}.{min_version[1]}: {exc.msg}")
        return errors

    tree = ast.parse(source, filename=str(path))
    if min_version < (3, 10) and not has_future_annotations(tree):
        visitor = AnnotationCompatVisitor()
        visitor.visit(tree)
        for line, message in visitor.issues:
            errors.append(f"{path}:{line}: {message}")
    return errors


def check_static(target: str, config: dict) -> None:
    path = ROOT / config["path"]
    min_version = parse_version(config["min_python"])
    errors: List[str] = []
    for file_path in python_files(path):
        errors.extend(check_file(file_path, min_version))
    if errors:
        raise CompatError(f"{target} static compatibility failed:\n" + "\n".join(errors))


def find_python(min_version: Tuple[int, int]) -> Optional[str]:
    exact = "python%d.%d" % min_version
    found = shutil.which(exact)
    if found:
        return found
    if sys.version_info[:2] == min_version:
        return sys.executable
    return None


def expand_command(command: Sequence[str], python: str, files: Sequence[Path]) -> List[str]:
    expanded: List[str] = []
    for item in command:
        if item == "{files}":
            expanded.extend(str(path.relative_to(ROOT)) for path in files)
        else:
            expanded.append(item.replace("{python}", python))
    return expanded


def run_runtime(target: str, config: dict, strict: bool) -> None:
    min_version = parse_version(config["min_python"])
    python = find_python(min_version)
    if python is None:
        message = f"{target}: skip runtime check, python{min_version[0]}.{min_version[1]} not found"
        if strict:
            raise CompatError(message)
        print(message)
        return

    files = python_files(ROOT / config["path"])
    for raw_command in config.get("runtime_commands", []):
        command = expand_command(raw_command, python, files)
        subprocess.run(command, cwd=str(ROOT), check=True)


def iter_targets(manifest: dict, target: Optional[str]) -> Iterable[Tuple[str, dict]]:
    if target is not None:
        if target not in manifest:
            raise CompatError("unknown target: %s" % target)
        yield target, manifest[target]
        return
    for name in sorted(manifest):
        yield name, manifest[name]


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", choices=sorted(load_manifest().keys()))
    parser.add_argument("--list", action="store_true", help="Print declared compatibility targets")
    parser.add_argument("--strict-runtime", action="store_true", help="Fail when the minimum interpreter is missing")
    args = parser.parse_args(argv)

    manifest = load_manifest()
    if args.list:
        for name, config in iter_targets(manifest, args.target):
            print("%s: %s, Python %s+" % (name, config["path"], config["min_python"]))
        return 0

    try:
        for name, config in iter_targets(manifest, args.target):
            check_static(name, config)
            run_runtime(name, config, args.strict_runtime)
    except (CompatError, subprocess.CalledProcessError) as exc:
        print(str(exc), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
