#!/usr/bin/env python3
"""准备独立的演示工作区并创建初始提交。"""
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
    # 优先解压发行包携带的工程，直接在源码仓库运行时复制示例工程。
    archive = HERE / 'demo_project.zip'
    if archive.exists():
        with zipfile.ZipFile(archive) as source:
            source.extractall(work / 'unpacked')
        shutil.move(str(work / 'unpacked/demo_project'), project)
    else:
        source = HERE.parents[2] / 'android_demo_project'
        shutil.copytree(source, project, ignore=shutil.ignore_patterns('build', '.gradle', '.idea', '.git', '__pycache__'))
    # 发行包可能省略 .gitignore，首次提交前统一忽略各模块的构建产物。
    with (project / '.gitignore').open('a') as ignore:
        ignore.write('\nbuild/\n.gradle/\n.kotlin/\n/local.properties\n')
    wrapper = project / 'gradlew'
    if wrapper.exists():
        wrapper.chmod(wrapper.stat().st_mode | 0o100)
    # 在独立仓库中用演示身份创建提交。
    run(['git', 'init'], project)
    run(['git', 'config', 'user.name', 'Jugg demo'], project)
    run(['git', 'config', 'user.email', 'jugg-demo@localhost'], project)
    commit(project, 'initial demo')
    # 重跑会复用工作目录路径，使用标识区分服务端保存的旧基线。
    (project / '.git/jugg-demo-id').write_text(uuid.uuid4().hex)
    return project


def is_initial_demo_checkout(project):
    """仅复用本脚本创建且仍停在初始提交的仓库。"""
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
