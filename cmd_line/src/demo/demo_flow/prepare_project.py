#!/usr/bin/env python3
"""Prepare an isolated demo checkout and its first commit."""
import argparse
from pathlib import Path
import shutil
import uuid
import zipfile

from flow_common import HERE, run


def prepare(work):
    project = work / 'project'
    if project.exists():
        if is_initial_demo_checkout(project):
            print(f'Reusing prepared demo checkout: {project}')
            return project
        raise RuntimeError(f'Project already exists and is not an untouched demo checkout: {project}')
    # Prefer the packaged demo archive. Use the source tree for repository runs.
    archive = HERE / 'demo_project.zip'
    if archive.exists():
        with zipfile.ZipFile(archive) as source:
            source.extractall(work / 'unpacked')
        shutil.move(str(work / 'unpacked/demo_project'), project)
    else:
        source = HERE.parents[2] / 'android_demo_project'
        shutil.copytree(source, project, ignore=shutil.ignore_patterns('build', '.gradle', '.idea', '.git', '__pycache__'))
    # A packaged project may omit .gitignore. Exclude build outputs before committing.
    with (project / '.gitignore').open('a') as ignore:
        ignore.write('\nbuild/\n.gradle/\n.kotlin/\n/local.properties\n')
    wrapper = project / 'gradlew'
    if wrapper.exists():
        wrapper.chmod(wrapper.stat().st_mode | 0o100)
    # Use a local demo identity in this repository.
    run(['git', 'init'], project)
    run(['git', 'config', 'user.name', 'Jugg demo'], project)
    run(['git', 'config', 'user.email', 'jugg-demo@localhost'], project)
    commit(project, 'initial demo')
    # Distinguish checkouts that reuse the same path across demo runs.
    (project / '.git/jugg-demo-id').write_text(uuid.uuid4().hex)
    return project


def is_initial_demo_checkout(project):
    """Reuse only a checkout created here that still points to its initial commit."""
    if not (project / '.git').is_dir() or not (project / 'settings.gradle').is_file():
        return False
    if not (project / 'app/build.gradle').is_file():
        return False
    try:
        identity = run(['git', 'config', 'user.name'], project)
        commit_count = run(['git', 'rev-list', '--count', 'HEAD'], project)
        subject = run(['git', 'log', '-1', '--format=%s'], project)
    except RuntimeError:
        return False
    return identity == 'Jugg demo' and commit_count == '1' and subject == 'initial demo'


def commit(project, message):
    run(['git', 'add', '.'], project)
    run(['git', 'commit', '-m', message], project)
    return run(['git', 'rev-parse', 'HEAD'], project)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--work-dir', type=Path, required=True)
    args = parser.parse_args()
    work = args.work_dir.resolve()
    work.mkdir(parents=True, exist_ok=True)
    prepare(work)


if __name__ == '__main__':
    main()
