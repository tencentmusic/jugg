"""Arguments, Chinese progress and subprocess handling for the continuous walkthrough."""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import sys

SCRIPTS = Path(__file__).resolve().parents[1] / 'scripts'


def arguments(description):
    parser = argparse.ArgumentParser(description=description)
    parser.add_argument('--work-dir', type=Path, required=True,
                        help='Disposable directory; deleted before each walkthrough')
    parser.add_argument('--scenario', choices=('debug', 'release'), default='debug')
    parser.add_argument('--cmd-line', default=str(SCRIPTS.parents[1] / 'bin/cmd_line'))
    parser.add_argument('--server', default='http://127.0.0.1:18766')
    parser.add_argument('--project-id', default='default', help='基线服务中的项目标识')
    parser.add_argument('--install', action='store_true')
    parser.add_argument('--quite', action='store_true',
                        help='隐藏命令输出，保留中文流程日志')
    args = parser.parse_args()
    args.work_dir = args.work_dir.resolve()
    args.cmd_line = str(Path(args.cmd_line).resolve())
    return args


def log(message):
    print(message, flush=True)


def run_command(command, quite, cwd=None):
    # Silence only child processes; walkthrough progress always goes to stdout.
    if not quite:
        print('+', ' '.join(map(str, command)), flush=True)
    result = subprocess.run(command, cwd=cwd,
                            stdout=subprocess.DEVNULL if quite else None,
                            stderr=subprocess.DEVNULL if quite else None)
    if result.returncode:
        log(f'命令执行失败：{Path(command[0]).name}，退出码 {result.returncode}；演示终止。')
        raise SystemExit(result.returncode)


def script(args, name, *arguments):
    """Use separate processes so walkthroughs exercise the public script boundaries."""
    command = [sys.executable, str(SCRIPTS / name), *map(str, arguments)]
    run_command(command, args.quite)


def clean_work_dir(work):
    # Walkthrough work directories are disposable, including all previous checkouts and artifacts.
    log(f'清理工作目录：{work}')
    if work.exists():
        shutil.rmtree(work)
    work.mkdir(parents=True)


def prepare(args, title):
    log(f'开始演示：{title}（{args.scenario}）')
    log(f'项目标识：{args.project_id}')
    clean_work_dir(args.work_dir)
    log('准备演示工程：解压源码并初始化 Git 仓库。')
    script(args, 'prepare_project.py', '--work-dir', args.work_dir)
    log('演示工程准备完成。')


def build(args, expected_mode):
    # Every step uses the same build arguments; only the committed checkout advances.
    script(args, 'jugg_build.py', '--work-dir', args.work_dir, '--scenario', args.scenario,
           '--cmd-line', args.cmd_line, '--server', args.server, '--project-id', args.project_id)
    result = json.loads((args.work_dir / 'build-result.json').read_text())
    recovered = result['mode'] == 'buildGradleBase' and (result['reason'] or '').startswith('Jugg CI failed:')
    if result['mode'] != expected_mode and not recovered:
        log(f'构建模式不符合预期：预期 {expected_mode}，实际 {result["mode"]}；演示终止。')
        raise SystemExit(1)
    log(f'构建提交：{result["commit"]}')
    changes = Path(result['runDir']) / 'changed-files.json'
    if changes.exists():
        files = json.loads(changes.read_text())['files']
        ignored = sum(Path(f['file']) == args.work_dir / 'project/README.md' for f in files)
        log(f'变更文件：{len(files)} 个；编排主动过滤 README：{ignored} 个。')
    reason = result['reason']
    if reason:
        reasons = {'no compatible baseline': '没有可复用基线',
                   'build file changed': '构建文件已修改',
                   'unrecognized changed files': '存在未处理的 Unknown，可能影响构建结果',
                   'changed input is missing': '变更文件不存在',
                   'Gradle explicitly requested': '已主动选择 Gradle 构建'}
        log('选择 Gradle 的原因：' + reasons.get(
            reason, reason.replace(' changed files exceed limit ', ' 个变更文件超过增量上限 ')
            .replace('Jugg CI failed: ', 'Jugg CI 执行失败：')))
    mode = {'buildIncrementalApk': '增量编译', 'buildGradleBase': 'Gradle 基线构建并保存基线',
            'reuseBaseApk': '没有可编译变更，复用基线 APK'}[result['mode']]
    log(f'构建完成：{mode}；APK 目录：{result["apkDir"]}')
    return result


def commit(project, files, message, quite):
    # Stage only the scenario inputs, never build artifacts from the first CI run.
    log('提交开发变更：将本场景修改保存为新的 Git 提交。')
    run_command(['git', 'add', '--', *map(str, files)], quite, cwd=project)
    run_command(['git', 'commit', '-m', message], quite, cwd=project)
    log('开发变更提交完成。')


def install(args, result):
    if args.install:
        log('安装并启动：将最终 APK 安装到设备并启动应用。')
        script(args, 'install_and_launch.py', '--apk-dir', result['apkDir'])
        log('应用安装并启动完成。')
    else:
        log('未指定 --install，跳过设备安装和启动。')
    log(f'演示完成，APK 目录：{result["apkDir"]}')
