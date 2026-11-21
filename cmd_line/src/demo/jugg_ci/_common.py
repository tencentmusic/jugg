"""CI 演示共用的目录定位、阶段日志和命令执行。"""
from pathlib import Path
import shlex
import subprocess
import sys

HERE = Path(__file__).resolve().parents[1]


def log(step, message, error=False):
    stream = sys.stderr if error else sys.stdout
    for line in str(message).splitlines():
        print(f'[{step:02d}] {line}', file=stream, flush=True)


def run(command, cwd=None):
    argv = list(map(str, command))
    print('+', shlex.join(argv), flush=True)
    result = subprocess.run(argv, cwd=cwd, text=True, capture_output=True)
    print(result.stdout, end='')
    print(result.stderr, end='', file=sys.stderr)
    if result.returncode:
        raise RuntimeError(f'命令执行失败（退出码 {result.returncode}）：{argv[0]}')
    return result.stdout.strip()


def run_logged(command, cwd, step, log_path):
    """实时显示 Jugg 输出，同时将完整输出保存到本轮日志。"""
    argv = list(map(str, command))
    log(step, shlex.join(argv))
    with log_path.open('w', encoding='utf-8', buffering=1) as stream, subprocess.Popen(
            argv, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, encoding='utf-8', errors='replace') as process:
        for line in process.stdout:
            stream.write(line)
            log(step, line)
        status = process.wait()
    if status:
        raise RuntimeError(f'命令执行失败（退出码 {status}），日志：{log_path}')
