"""连续演示共用的参数、进度和子进程调用。"""
import argparse
from contextlib import contextmanager
import json
from pathlib import Path
import shutil
import subprocess
import sys
from time import monotonic, sleep

DEMO_FLOW = Path(__file__).resolve().parent
# 允许直接运行演示脚本并复用 CI 脚本的命令执行。
sys.path.insert(0, str(DEMO_FLOW.parent))
from jugg_ci._common import HERE, run

BUILD_SCRIPT = HERE / 'jugg_ci/build.py'


def arguments(description):
    parser = argparse.ArgumentParser(description=description)
    parser.add_argument('--work-dir', type=Path, default=HERE / 'build/ci-demo',
                        help='演示工作目录，每次启动时重建，默认 build/ci-demo')
    parser.add_argument('--scenario', choices=('debug', 'release'), default='debug')
    parser.add_argument('--cmd-line', default=str(DEMO_FLOW.parents[1] / 'bin/cmd_line'))
    parser.add_argument('--server', help='基线服务地址，默认使用本机服务')
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
    # 静默选项只隐藏子进程输出，演示进度仍写入终端。
    if not quite:
        print('+', ' '.join(map(str, command)), flush=True)
    result = subprocess.run(command, cwd=cwd,
                            stdout=subprocess.DEVNULL if quite else None,
                            stderr=subprocess.DEVNULL if quite else None)
    if result.returncode:
        log(f'命令执行失败：{Path(command[0]).name}，退出码 {result.returncode}；演示终止。')
        raise SystemExit(result.returncode)


def script(args, path, *arguments):
    """以独立进程执行各脚本，走与分步操作相同的入口。"""
    command = [sys.executable, str(path), *map(str, arguments)]
    run_command(command, args.quite)


def clean_work_dir(work):
    # 连续演示启动时清理上次使用的工作区和产物。
    log(f'清理工作目录：{work}')
    if work.exists():
        shutil.rmtree(work)
    work.mkdir(parents=True)


@contextmanager
def baseline_server(args):
    if args.server:
        log(f'使用指定基线服务：{args.server}')
        yield
        return

    # 基线数据随演示工作目录一起重建，动态端口允许多份演示独立运行。
    store = args.work_dir / 'server'
    store.mkdir()
    log_path = store / 'server.log'
    command = [sys.executable, '-X', 'utf8', str(HERE / 'jugg_ci/base_build_server.py'),
               '--host', '127.0.0.1', '--port', '0', '--data-file', str(store / 'base-builds.json')]
    with log_path.open('w', encoding='utf-8') as output, subprocess.Popen(
            command, stdout=output, stderr=subprocess.STDOUT) as process:
        try:
            args.server = wait_for_server(process, log_path)
            log(f'启动本地基线服务：{args.server}')
            yield
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
            log(f'本地基线服务已关闭，日志：{log_path}')


def wait_for_server(process, log_path):
    deadline = monotonic() + 10
    with log_path.open(encoding='utf-8') as log_file:
        while process.poll() is None and monotonic() < deadline:
            line = log_file.readline()
            if line.startswith('基线服务：http://127.0.0.1:') and line.endswith('\n'):
                return line.split('：', 1)[1].strip()
            log_file.seek(0)
            sleep(0.05)
    raise RuntimeError(f'无法启动本地基线服务，日志：{log_path}')


def prepare(args, title):
    log(f'开始演示：{title}（{args.scenario}）')
    log(f'项目标识：{args.project_id}')
    clean_work_dir(args.work_dir)
    log('准备演示工程：解压源码并初始化 Git 仓库。')
    script(args, DEMO_FLOW / 'prepare_project.py', '--work-dir', args.work_dir)
    log('演示工程准备完成。')


def build(args, expected_mode):
    # 每轮使用相同的构建参数，由演示步骤推进 Git 提交。
    server = ['--server', args.server] if args.server else []
    script(args, BUILD_SCRIPT, '--work-dir', args.work_dir, '--scenario', args.scenario,
           '--cmd-line', args.cmd_line, '--project-id', args.project_id, *server)
    result = json.loads((args.work_dir / 'build-result.json').read_text())
    recovered = result['mode'] == 'buildGradleBase' and (result['reason'] or '').startswith('Jugg 基线复用失败:')
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
        log('选择 Gradle 的原因：' + reason)
    mode = {'buildIncrementalApk': '增量编译', 'buildGradleBase': 'Gradle 基线构建并保存基线',
            'reuseBaseApk': '没有可编译变更，复用基线 APK'}[result['mode']]
    log(f'构建完成：{mode}；APK 目录：{result["apkDir"]}')
    return result


def commit(project, files, message, quite):
    # 只提交这一轮的场景输入。
    log('提交开发变更：将本场景修改保存为新的 Git 提交。')
    run_command(['git', 'add', '--', *map(str, files)], quite, cwd=project)
    run_command(['git', 'commit', '-m', message], quite, cwd=project)
    log('开发变更提交完成。')


def install(args, result):
    if args.install:
        log('安装并启动：将最终 APK 安装到设备并启动应用。')
        script(args, DEMO_FLOW / 'install_and_launch.py', '--apk-dir', result['apkDir'])
        log('应用安装并启动完成。')
    else:
        log('未指定 --install，跳过设备安装和启动。')
    log(f'演示完成，APK 目录：{result["apkDir"]}')
