#!/usr/bin/env python3
"""Run CI scenarios sequentially in one checkout, advancing HEAD each round."""
import json
import re
import zipfile
from pathlib import Path

from flow_common import arguments, baseline_server, build, commit, install, log, prepare


def configure_project(project, quite):
    # Exercise custom Gradle inputs outside Jugg source roots.
    config = project / 'config.json'
    config.write_text(json.dumps({'message': 'initial config'}, indent=2) + '\n')
    build_file = project / 'app/build.gradle'
    with build_file.open('a') as stream:
        stream.write("""
// 将工程根目录的 config.json 打包到 APK 的 assets 目录。
def demoConfigAssets = layout.buildDirectory.dir('generated/demoConfigAssets')
android.sourceSets.main.assets.srcDir(demoConfigAssets)
def generateDemoConfig = tasks.register('generateDemoConfig', Copy) {
    from(rootProject.file('config.json'))
    into(demoConfigAssets)
}
tasks.named('preBuild').configure { dependsOn(generateDemoConfig) }
""")
    commit(project, [config, build_file], 'configure custom JSON build input', quite)


def modify_source(project, quite):
    activity = project / 'app/src/main/java/com/example/myapplication/MainActivity.kt'
    text, count = re.subn(r'(super\.onCreate\([^\n]*\))',
                         r'\1\n        android.widget.Toast.makeText(this, "Hello Jugg CI!", android.widget.Toast.LENGTH_SHORT).show()',
                         activity.read_text(), count=1)
    if count != 1:
        raise RuntimeError('MainActivity onCreate anchor not found')
    activity.write_text(text)
    layout = project / 'app/src/main/res/layout/activity_main.xml'
    text, count = re.subn(r'android:text="[^\"]*"', 'android:text="Hello Jugg CI!"', layout.read_text(), count=1)
    if count != 1:
        raise RuntimeError('Layout text anchor not found')
    layout.write_text(text)
    commit(project, [activity, layout], 'change Kotlin and layout', quite)


def modify_build_file(project, quite):
    build_file = project / 'app/build.gradle'
    with build_file.open('a') as stream:
        stream.write('\n// 演示构建脚本变更触发 Gradle 降级。\n'
                     'android.defaultConfig.buildConfigField "String", "JUGG_CI_DEMO", \'"changed"\'\n')
    commit(project, [build_file], 'change Gradle BuildConfig field', quite)


def modify_many_files(project, quite):
    directory = project / 'app/src/main/java/com/example/myapplication/cidemo'
    directory.mkdir(parents=True)
    files = []
    # Keep the fixture size in sync with the CI file limit.
    for index in range(101):
        source = directory / f'CiDemo{index}.java'
        source.write_text('package com.example.myapplication.cidemo;\n'
                          f'public class CiDemo{index} {{}}\n')
        files.append(source)
    commit(project, files, 'add 101 source files', quite)


def modify_readme(project, quite):
    readme = project / 'README.md'
    with readme.open('a') as stream:
        stream.write('\nCI demonstration: documentation-only update.\n')
    commit(project, [readme], 'change README documentation', quite)


def modify_config(project, quite):
    config = project / 'config.json'
    config.write_text(json.dumps({'message': 'updated config'}, indent=2) + '\n')
    commit(project, [config], 'change custom JSON build input', quite)


def modify_route(project, quite):
    source = project / 'app/src/main/java/com/sickworm/jugg/demo/testcase/annotation/kotlin/KtARouterActivity.kt'
    text, count = re.subn(r'@Route\(path = "/app/activity_kt"\)', '@Route(path = "/app/activity_ci_demo")',
                         source.read_text(), count=1)
    if count != 1:
        raise RuntimeError('找不到演示路由注解')
    source.write_text(text)
    commit(project, [source], 'change an ARouter route', quite)


def check_config_apk(result):
    # Read the APK to verify the custom input was updated.
    apks = sorted(Path(result['apkDir']).glob('*.apk'))
    with zipfile.ZipFile(apks[0]) as archive:
        actual = json.loads(archive.read('assets/config.json'))
    if actual != {'message': 'updated config'}:
        raise RuntimeError(f'APK custom configuration was not updated: {actual}')
    log('产物验证：APK 的 assets/config.json 已更新。')


def run_scenarios(args):
    project = args.work_dir / 'project'
    log('准备项目自定义构建输入：将根目录 config.json 打包到 APK assets。')
    configure_project(project, args.quite)
    log('首次构建：建立一份初始 Gradle 基线。')
    results = [build(args, 'buildGradleBase')]
    (args.work_dir / 'flow-results.json').write_text(json.dumps(results, ensure_ascii=False, indent=2))
    cases = [
        ('正常增量编译', modify_source, 'buildIncrementalApk', None),
        ('修改 build 文件降级', modify_build_file, 'buildGradleBase', f'存在需要 Gradle 处理的构建输入：{project / "app/build.gradle"}'),
        ('修改文件过多降级', modify_many_files, 'buildGradleBase', '变更超过 100 个文件'),
        ('README 主动过滤', modify_readme, 'reuseBaseApk', None),
        ('修改 ARouter 路由后增量构建', modify_route, 'buildIncrementalApk', None),
        ('自定义 config.json 未识别后降级', modify_config, 'buildGradleBase', f'存在需要 Gradle 处理的构建输入：{project / "config.json"}'),
    ]
    for title, modify, mode, reason in cases:
        log(f'开发修改：{title}；保留前面的提交和构建目录。')
        modify(project, args.quite)
        log(f'再次构建：{title}。')
        result = build(args, mode)
        recovered = (result['reason'] or '').startswith('Jugg 基线复用失败:')
        if result['reason'] != reason and not recovered:
            raise RuntimeError(f'Unexpected decision for {title}: {result["reason"]}')
        results.append(result)
        # Persist each result as soon as the scenario completes.
        (args.work_dir / 'flow-results.json').write_text(json.dumps(results, ensure_ascii=False, indent=2))
    check_config_apk(results[-1])
    install(args, results[-1])


def main():
    args = arguments(__doc__)
    prepare(args, '一次运行六个场景，Git 提交持续向前叠加')
    with baseline_server(args):
        run_scenarios(args)


if __name__ == '__main__':
    main()
