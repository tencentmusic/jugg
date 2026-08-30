import { defineConfig } from 'vitepress'

const isWikiDev = process.env.JUGG_WIKI_DEV === 'true' || process.argv.includes('dev')
const productionSrcExclude = isWikiDev ? [] : ['dev/**', 'zh/dev/**']
const wikiBase = process.env.JUGG_WIKI_BASE || '/'

const englishNav = [
  { text: 'Get started', link: '/onboarding/' },
  { text: 'Guide', link: '/guide/' },
  { text: 'How it works', link: '/concepts/' },
  { text: 'Capabilities', link: '/capabilities/' },
  { text: 'Troubleshooting', link: '/troubleshooting/' },
  { text: 'Reference', link: '/reference/' },
  { text: 'Technical Articles', link: '/articles/' },
  ...(isWikiDev ? [{ text: 'Dev', link: '/dev/elements-demo' }] : [])
]

const englishSidebar = {
  '/onboarding/': [
    {
      text: 'Get started',
      items: [
        { text: 'Overview', link: '/onboarding/' },
        { text: 'Installation', link: '/onboarding/installation' },
        { text: 'First run', link: '/onboarding/first-run' },
        { text: 'Remote build machine setup', link: '/onboarding/agent-setup' }
      ]
    }
  ],
  '/guide/': [
    {
      text: 'Guide',
      items: [
        { text: 'Overview', link: '/guide/' },
        { text: 'Run an app', link: '/guide/run' },
        { text: 'Jugg Control Panel', link: '/guide/control-panel' },
        { text: 'Run configurations and build variants', link: '/guide/run-configuration' },
        { text: 'Fall back to Gradle compilation', link: '/guide/downgrade-gradle' },
        { text: 'Export an incremental APK', link: '/guide/export-incremental-apk' },
        { text: 'Restart the app', link: '/guide/restart-app' },
        { text: 'Clear app data', link: '/guide/clean-data' },
        { text: 'Select multiple devices', link: '/guide/multi-device' },
        { text: 'Android RemoteViews', link: '/guide/android-remoteviews' },
        { text: 'Compatibility deployment', link: '/guide/compat-device' },
        { text: 'Debug', link: '/guide/debug' },
        { text: 'Android Test', link: '/guide/android-test' },
        { text: 'CLI', link: '/guide/cli' },
        { text: 'MCP', link: '/guide/mcp' },
        { text: 'UI inspection', link: '/guide/ui-inspection' },
        { text: 'Remote Gradle', link: '/guide/remote-gradle' },
        { text: 'Custom compiler', link: '/guide/custom-compiler' },
        { text: 'Advanced options', link: '/guide/advanced-options' },
        { text: 'Report an issue', link: '/guide/report-issue' },
        {
          text: 'Jugg backend',
          collapsed: true,
          items: [
            { text: 'Overview', link: '/guide/jugg-backend/' },
            { text: 'Self-hosting checklist', link: '/guide/jugg-backend/self-hosting' },
            { text: 'Project configuration distribution', link: '/guide/jugg-backend/project-config' },
            { text: 'Plugin distribution and hot updates', link: '/guide/jugg-backend/plugin-delivery' },
            { text: 'Diagnostics reporting', link: '/guide/jugg-backend/diagnostics' },
            { text: 'Remote-machine application', link: '/guide/jugg-backend/remote-server-apply' }
          ]
        }
      ]
    }
  ],
  '/concepts/': [
    {
      text: 'How it works',
      items: [
        { text: 'Overview', link: '/concepts/' },
        { text: 'How Jugg works', link: '/concepts/how-jugg-works' },
        {
          text: 'Incremental compilation',
          collapsed: false,
          items: [
            { text: 'Overview', link: '/concepts/incremental-compile/' },
            { text: 'Source incremental compilation', link: '/concepts/incremental-compile/source' },
            { text: 'KMP source incremental compilation', link: '/concepts/incremental-compile/kmp-source' },
            { text: 'Recompilation', link: '/concepts/incremental-compile/recompile-propagation' },
            { text: 'Constant reference analysis', link: '/concepts/incremental-compile/const-ref' },
            { text: 'Resource incremental compilation', link: '/concepts/incremental-compile/resource' },
            { text: 'Compose Multiplatform resources', link: '/concepts/incremental-compile/compose-multiplatform-resource' },
            { text: 'DataBinding / ViewBinding', link: '/concepts/incremental-compile/databinding-viewbinding' },
            { text: 'Android Manifest compilation', link: '/concepts/incremental-compile/manifest' },
            { text: 'Release incremental compilation', link: '/concepts/incremental-compile/release-compile' },
            { text: 'Assets / native libraries', link: '/concepts/incremental-compile/assets-native' },
            { text: 'Dependency incremental compilation', link: '/concepts/incremental-compile/dependency-incremental' },
            { text: 'Custom compiler', link: '/concepts/incremental-compile/custom-compiler' }
          ]
        },
        {
          text: 'Incremental deployment',
          collapsed: false,
          items: [
            { text: 'Overview', link: '/concepts/deploy-strategy' },
            { text: 'Deploy data and impact analysis', link: '/concepts/deploy-data-and-impact' },
            { text: 'Apply Changes', link: '/concepts/apply-changes' },
            { text: 'APK update and installation', link: '/concepts/apk-update-and-install' },
            { text: 'Direct Overlay', link: '/concepts/direct-overlay' },
            { text: 'Compatibility deployment', link: '/concepts/compat-deploy' },
            { text: 'Deployment state and recovery', link: '/concepts/deploy-state-recover' },
            { text: 'Deployment self-healing', link: '/concepts/deploy-self-healing' }
          ]
        },
        { text: 'Gradle fallback and baseline rebuild', link: '/concepts/gradle-fallback-baseline' },
        { text: 'Project context', link: '/concepts/project-model' },
        { text: 'Project information refresh and recovery', link: '/concepts/project-info-refresh' },
        { text: 'Compilation orchestration', link: '/concepts/compile-pipeline' },
        { text: 'In-app Jugg Runtime', link: '/concepts/jugg-runtime' },
        { text: 'Jugg JVMTI Agent', link: '/concepts/jugg-jvmti-agent' },
        { text: 'Android Test flow', link: '/concepts/android-test-flow' },
        { text: 'Layout dump and UI evidence', link: '/concepts/layout-dump-and-ui-evidence' },
        { text: 'Android Studio version compatibility', link: '/concepts/compatibility-layer' }
      ]
    }
  ],
  '/capabilities/': [
    {
      text: 'Capabilities',
      items: [
        { text: 'Overview', link: '/capabilities/' },
        {
          text: 'Compile',
          collapsed: false,
          items: [
            { text: 'Overview', link: '/capabilities/compile/' },
            { text: 'Source compilation', link: '/capabilities/compile/source-compile' },
            { text: 'KMP and Compose Multiplatform', link: '/capabilities/compile/kmp-compose-multiplatform' },
            { text: 'Recompilation', link: '/capabilities/compile/recompile-propagation' },
            { text: 'Resource compilation', link: '/capabilities/compile/resource-compile' },
            { text: 'AndroidManifest compilation', link: '/capabilities/compile/manifest' },
            { text: 'Native library updates', link: '/capabilities/compile/so-update' },
            { text: 'DataBinding / ViewBinding', link: '/capabilities/compile/databinding-viewbinding' },
            { text: 'Kotlin Compose', link: '/capabilities/compile/kotlin-compose' },
            { text: 'Annotation processors', link: '/capabilities/compile/annotation-processors' },
            { text: 'Dependency incremental compilation', link: '/capabilities/compile/dependency-incremental' },
            { text: 'Release compilation', link: '/capabilities/compile/release-compile' },
            { text: 'AabResGuard', link: '/capabilities/compile/aab-resguard' },
            { text: 'Gradle fallback', link: '/capabilities/compile/gradle-fallback' },
            { text: 'Custom compiler', link: '/capabilities/compile/custom-compiler' }
          ]
        },
        {
          text: 'Deploy',
          collapsed: false,
          items: [
            { text: 'Overview', link: '/capabilities/deploy/' },
            { text: 'Clean Reinstall', link: '/capabilities/deploy/clean-reinstall' },
            { text: 'Code Swap', link: '/capabilities/deploy/code-swap' },
            { text: 'Full Swap', link: '/capabilities/deploy/full-swap' },
            { text: 'Hot Reload', link: '/capabilities/deploy/hot-reload' },
            { text: 'Restart', link: '/capabilities/deploy/restart' },
            { text: 'Direct Overlay', link: '/capabilities/deploy/direct-overlay' },
            { text: 'Recover and Retry', link: '/capabilities/deploy/recover-and-retry' },
            { text: 'Multi-APK', link: '/capabilities/deploy/multi-apk' },
            { text: 'Multiple devices', link: '/capabilities/deploy/multi-device' },
            { text: 'Deploy History and cache', link: '/capabilities/deploy/deploy-history-cache' },
            { text: 'HarmonyOS compatibility deployment', link: '/capabilities/deploy/harmonyos-compat' },
            { text: 'JVMTI Runtime', link: '/capabilities/deploy/jvmti-runtime' }
          ]
        },
        {
          text: 'Test',
          collapsed: false,
          items: [
            { text: 'Overview', link: '/capabilities/test/' },
            { text: 'Application Android Test', link: '/capabilities/test/application-android-test' },
            { text: 'Library Android Test', link: '/capabilities/test/library-android-test' },
            { text: 'Test Results UI', link: '/capabilities/test/test-results-ui' },
            { text: 'Logcat attribution', link: '/capabilities/test/logcat-attribution' }
          ]
        },
        {
          text: 'Jugg CLI and Agent Skills',
          collapsed: false,
          items: [
            { text: 'Overview', link: '/capabilities/tools/' },
            { text: 'Agent Skills', link: '/capabilities/tools/agent-skills' },
            {
              text: 'Jugg CLI',
              collapsed: false,
              items: [
                { text: 'Overview', link: '/capabilities/tools/cli' },
                { text: 'Build and deploy', link: '/capabilities/tools/cli-build-deploy' },
                { text: 'Run context and no-change results', link: '/capabilities/tools/run-context-and-no-change' },
                { text: 'Android Test', link: '/capabilities/tools/cli-android-test' },
                { text: 'Runtime and device', link: '/capabilities/tools/cli-runtime-device' },
                { text: 'UI automation', link: '/capabilities/tools/ui-automation' },
                { text: 'UI layout evidence', link: '/capabilities/tools/layout-verify' },
                { text: 'Remote diagnosis', link: '/capabilities/tools/remote-diagnosis' }
              ]
            },
            { text: 'MCP for agents', link: '/capabilities/tools/mcp' }
          ]
        }
      ]
    }
  ],
  '/troubleshooting/': [
    {
      text: 'Troubleshooting',
      items: [
        { text: 'Overview', link: '/troubleshooting/' },
        { text: 'Compilation failed', link: '/troubleshooting/compile-failed' },
        { text: 'Changes did not take effect', link: '/troubleshooting/changes-not-applied' },
        { text: 'App crashed after deployment', link: '/troubleshooting/runtime-crash' },
        { text: 'Installation, deployment, launch, or Debug failed', link: '/troubleshooting/app-cannot-run' },
        { text: 'Jugg is slow or stuck', link: '/troubleshooting/jugg-slow-or-stuck' }
      ]
    },
    {
      text: 'Feature-specific issues',
      items: [
        { text: 'Remote compilation failed', link: '/troubleshooting/remote-build-failed' },
        { text: 'Android Test run or test failed', link: '/troubleshooting/android-test-failed' },
        { text: 'Agent or CLI command failed', link: '/troubleshooting/agent-command-failed' }
      ]
    }
  ],
  '/reference/': [
    {
      text: 'Reference',
      items: [
        { text: 'Overview', link: '/reference/' },
        { text: 'Compatibility', link: '/reference/compatibility' },
        { text: 'Glossary', link: '/reference/glossary' },
        { text: 'CLI commands', link: '/reference/cli-commands' },
        { text: 'MCP tools', link: '/reference/mcp-tools' },
        { text: 'Configuration', link: '/reference/configuration' },
        { text: 'Log files', link: '/reference/log-files' },
        { text: 'Limits', link: '/reference/limits' }
      ]
    }
  ],
  '/articles/': [
    {
      text: 'Technical Articles',
      items: [
        { text: 'Overview', link: '/articles/' },
        { text: 'Jugg (1): Architecture and Usage', link: '/articles/01-jugg-introduction/' },
        { text: 'Jugg (2): Source Incremental Compilation', link: '/articles/02-source-incremental-compilation/' },
        { text: 'Jugg (3): Resource Incremental Compilation', link: '/articles/03-resource-incremental-compilation/' },
        { text: 'Jugg (4): Incremental Deployment', link: '/articles/04-incremental-deployment/' },
        { text: 'Jugg 2.0', link: '/articles/05-jugg-2-0/' },
        { text: 'How Much Compilation Time Did Jugg Save?', link: '/articles/06-time-savings/' },
        { text: 'Jugg 2.x Evolution', link: '/articles/07-jugg-2-x-evolution/' },
        { text: 'Jugg 3.0: From Fast Compilation to Agent Verification', link: '/articles/08-jugg-3-0/' }
      ]
    }
  ],
  ...(isWikiDev
    ? {
        '/dev/': [
          {
            text: 'Dev',
            items: [{ text: 'Wiki Elements Demo', link: '/dev/elements-demo' }]
          }
        ]
      }
    : {})
}

const chineseNav = [
  { text: '快速开始', link: '/zh/onboarding/' },
  { text: '使用指南', link: '/zh/guide/' },
  { text: '实现原理', link: '/zh/concepts/' },
  { text: '能力', link: '/zh/capabilities/' },
  { text: '问题排查', link: '/zh/troubleshooting/' },
  { text: '参考', link: '/zh/reference/' },
  { text: '技术文章', link: '/zh/articles/' },
  ...(isWikiDev ? [{ text: 'Dev', link: '/zh/dev/elements-demo' }] : [])
]

const chineseSidebar = {
  '/zh/onboarding/': [
    {
      text: '快速开始',
      items: [
        { text: '概览', link: '/zh/onboarding/' },
        { text: '安装', link: '/zh/onboarding/installation' },
        { text: '首次运行', link: '/zh/onboarding/first-run' },
        { text: '云开发机配置', link: '/zh/onboarding/agent-setup' }
      ]
    }
  ],
  '/zh/guide/': [
    {
      text: '使用指南',
      items: [
        { text: '概览', link: '/zh/guide/' },
        { text: '运行 App', link: '/zh/guide/run' },
        { text: 'Jugg 运行面板', link: '/zh/guide/control-panel' },
        { text: '运行配置与构建变体', link: '/zh/guide/run-configuration' },
        { text: '降级 Gradle 编译', link: '/zh/guide/downgrade-gradle' },
        { text: '导出增量 APK', link: '/zh/guide/export-incremental-apk' },
        { text: '重启 App', link: '/zh/guide/restart-app' },
        { text: '清理数据', link: '/zh/guide/clean-data' },
        { text: '多设备选择', link: '/zh/guide/multi-device' },
        { text: 'Android RemoteViews', link: '/zh/guide/android-remoteviews' },
        { text: '设备兼容部署', link: '/zh/guide/compat-device' },
        { text: 'Debug', link: '/zh/guide/debug' },
        { text: 'Android Test', link: '/zh/guide/android-test' },
        { text: 'CLI', link: '/zh/guide/cli' },
        { text: 'MCP', link: '/zh/guide/mcp' },
        { text: 'UI 检查', link: '/zh/guide/ui-inspection' },
        { text: '远端 Gradle', link: '/zh/guide/remote-gradle' },
        { text: '自定义编译器', link: '/zh/guide/custom-compiler' },
        { text: '高级选项', link: '/zh/guide/advanced-options' },
        { text: '报告问题', link: '/zh/guide/report-issue' },
        {
          text: 'Jugg 后台',
          collapsed: true,
          items: [
            { text: '概览', link: '/zh/guide/jugg-backend/' },
            { text: '自建接入清单', link: '/zh/guide/jugg-backend/self-hosting' },
            { text: '项目配置下发', link: '/zh/guide/jugg-backend/project-config' },
            { text: '插件分发与热更新', link: '/zh/guide/jugg-backend/plugin-delivery' },
            { text: '诊断上报', link: '/zh/guide/jugg-backend/diagnostics' },
            { text: '远端机器申请', link: '/zh/guide/jugg-backend/remote-server-apply' }
          ]
        }
      ]
    }
  ],
  '/zh/concepts/': [
    {
      text: '实现原理',
      items: [
        { text: '概览', link: '/zh/concepts/' },
        { text: 'Jugg 工作原理', link: '/zh/concepts/how-jugg-works' },
        {
          text: '增量编译',
          collapsed: false,
          items: [
            { text: '总览', link: '/zh/concepts/incremental-compile/' },
            { text: '源码增量编译', link: '/zh/concepts/incremental-compile/source' },
            { text: 'KMP 源码增量编译', link: '/zh/concepts/incremental-compile/kmp-source' },
            { text: '重编译 / 扩散编译', link: '/zh/concepts/incremental-compile/recompile-propagation' },
            { text: '常量引用分析', link: '/zh/concepts/incremental-compile/const-ref' },
            { text: '资源增量编译', link: '/zh/concepts/incremental-compile/resource' },
            { text: 'Compose Multiplatform 资源', link: '/zh/concepts/incremental-compile/compose-multiplatform-resource' },
            { text: 'DataBinding / ViewBinding', link: '/zh/concepts/incremental-compile/databinding-viewbinding' },
            { text: 'Android Manifest 编译', link: '/zh/concepts/incremental-compile/manifest' },
            { text: 'release 增量编译', link: '/zh/concepts/incremental-compile/release-compile' },
            { text: 'assets / native lib', link: '/zh/concepts/incremental-compile/assets-native' },
            { text: '依赖库增量编译', link: '/zh/concepts/incremental-compile/dependency-incremental' },
            { text: '自定义编译器', link: '/zh/concepts/incremental-compile/custom-compiler' }
          ]
        },
        {
          text: '增量部署',
          collapsed: false,
          items: [
            { text: '总览', link: '/zh/concepts/deploy-strategy' },
            { text: '部署数据与影响分析', link: '/zh/concepts/deploy-data-and-impact' },
            { text: 'Apply Changes', link: '/zh/concepts/apply-changes' },
            { text: 'APK 更新与安装', link: '/zh/concepts/apk-update-and-install' },
            { text: 'Direct Overlay', link: '/zh/concepts/direct-overlay' },
            { text: '兼容部署', link: '/zh/concepts/compat-deploy' },
            { text: '部署状态与恢复', link: '/zh/concepts/deploy-state-recover' },
            { text: '部署自愈机制', link: '/zh/concepts/deploy-self-healing' }
          ]
        },
        { text: 'Gradle 回退与基线重建', link: '/zh/concepts/gradle-fallback-baseline' },
        { text: '工程上下文获取', link: '/zh/concepts/project-model' },
        { text: '工程信息刷新与恢复', link: '/zh/concepts/project-info-refresh' },
        { text: '编译调度流程', link: '/zh/concepts/compile-pipeline' },
        { text: 'App 进程内 Jugg runtime', link: '/zh/concepts/jugg-runtime' },
        { text: 'Jugg JVMTI Agent', link: '/zh/concepts/jugg-jvmti-agent' },
        { text: 'Android Test 流程', link: '/zh/concepts/android-test-flow' },
        { text: '布局 dump 与 UI 证据', link: '/zh/concepts/layout-dump-and-ui-evidence' },
        { text: 'Android Studio 版本兼容', link: '/zh/concepts/compatibility-layer' }
      ]
    }
  ],
  '/zh/capabilities/': [
    {
      text: '能力',
      items: [
        { text: '概览', link: '/zh/capabilities/' },
        {
          text: '编译',
          collapsed: false,
          items: [
            { text: '概览', link: '/zh/capabilities/compile/' },
            { text: '源码编译', link: '/zh/capabilities/compile/source-compile' },
            { text: 'KMP 与 Compose Multiplatform', link: '/zh/capabilities/compile/kmp-compose-multiplatform' },
            { text: '重编译/扩散编译', link: '/zh/capabilities/compile/recompile-propagation' },
            { text: '资源编译', link: '/zh/capabilities/compile/resource-compile' },
            { text: 'AndroidManifest 编译', link: '/zh/capabilities/compile/manifest' },
            { text: 'so 更新', link: '/zh/capabilities/compile/so-update' },
            { text: 'DataBinding/ViewBinding', link: '/zh/capabilities/compile/databinding-viewbinding' },
            { text: 'Kotlin Compose', link: '/zh/capabilities/compile/kotlin-compose' },
            { text: '注解器', link: '/zh/capabilities/compile/annotation-processors' },
            { text: '依赖库增量编译', link: '/zh/capabilities/compile/dependency-incremental' },
            { text: 'Release 编译', link: '/zh/capabilities/compile/release-compile' },
            { text: 'AabResGuard', link: '/zh/capabilities/compile/aab-resguard' },
            { text: 'Gradle 回退', link: '/zh/capabilities/compile/gradle-fallback' },
            { text: '自定义编译器', link: '/zh/capabilities/compile/custom-compiler' }
          ]
        },
        {
          text: '部署',
          collapsed: false,
          items: [
            { text: '概览', link: '/zh/capabilities/deploy/' },
            { text: 'Clean Reinstall', link: '/zh/capabilities/deploy/clean-reinstall' },
            { text: 'Code Swap', link: '/zh/capabilities/deploy/code-swap' },
            { text: 'Full Swap', link: '/zh/capabilities/deploy/full-swap' },
            { text: 'Hot Reload', link: '/zh/capabilities/deploy/hot-reload' },
            { text: 'Restart', link: '/zh/capabilities/deploy/restart' },
            { text: 'Direct Overlay', link: '/zh/capabilities/deploy/direct-overlay' },
            { text: 'Recover 与 Retry', link: '/zh/capabilities/deploy/recover-and-retry' },
            { text: '多 APK', link: '/zh/capabilities/deploy/multi-apk' },
            { text: '多设备', link: '/zh/capabilities/deploy/multi-device' },
            { text: '部署历史与缓存', link: '/zh/capabilities/deploy/deploy-history-cache' },
            { text: 'HarmonyOS 兼容部署', link: '/zh/capabilities/deploy/harmonyos-compat' },
            { text: 'JVMTI Runtime', link: '/zh/capabilities/deploy/jvmti-runtime' }
          ]
        },
        {
          text: '测试',
          collapsed: false,
          items: [
            { text: '概览', link: '/zh/capabilities/test/' },
            { text: 'Application Android Test', link: '/zh/capabilities/test/application-android-test' },
            { text: 'Library Android Test', link: '/zh/capabilities/test/library-android-test' },
            { text: 'Test Results UI', link: '/zh/capabilities/test/test-results-ui' },
            { text: 'Logcat 归因', link: '/zh/capabilities/test/logcat-attribution' }
          ]
        },
        {
          text: 'Jugg CLI 与 Agent Skills',
          collapsed: false,
          items: [
            { text: '概览', link: '/zh/capabilities/tools/' },
            { text: 'Agent Skills', link: '/zh/capabilities/tools/agent-skills' },
            {
              text: 'Jugg CLI',
              collapsed: false,
              items: [
                { text: '概览', link: '/zh/capabilities/tools/cli' },
                { text: '构建与部署', link: '/zh/capabilities/tools/cli-build-deploy' },
                { text: '运行上下文与无变化结果', link: '/zh/capabilities/tools/run-context-and-no-change' },
                { text: 'Android Test', link: '/zh/capabilities/tools/cli-android-test' },
                { text: '运行时与设备', link: '/zh/capabilities/tools/cli-runtime-device' },
                { text: 'UI 自动化', link: '/zh/capabilities/tools/ui-automation' },
                { text: 'UI 布局证据', link: '/zh/capabilities/tools/layout-verify' },
                { text: '远端诊断', link: '/zh/capabilities/tools/remote-diagnosis' }
              ]
            },
            { text: '面向 Agent 的 MCP', link: '/zh/capabilities/tools/mcp' }
          ]
        }
      ]
    }
  ],
  '/zh/troubleshooting/': [
    {
      text: '问题排查',
      items: [
        { text: '概览', link: '/zh/troubleshooting/' },
        { text: '编译失败', link: '/zh/troubleshooting/compile-failed' },
        { text: '改动没有生效', link: '/zh/troubleshooting/changes-not-applied' },
        { text: '部署后 App 崩溃', link: '/zh/troubleshooting/runtime-crash' },
        { text: '无法安装、部署、启动或 Debug', link: '/zh/troubleshooting/app-cannot-run' },
        { text: 'Jugg 运行缓慢或卡住', link: '/zh/troubleshooting/jugg-slow-or-stuck' }
      ]
    },
    {
      text: '特定功能问题',
      items: [
        { text: '远程编译失败', link: '/zh/troubleshooting/remote-build-failed' },
        { text: 'Android Test 运行或测试失败', link: '/zh/troubleshooting/android-test-failed' },
        { text: 'Agent 或 CLI 执行失败', link: '/zh/troubleshooting/agent-command-failed' }
      ]
    }
  ],
  '/zh/reference/': [
    {
      text: '参考',
      items: [
        { text: '概览', link: '/zh/reference/' },
        { text: '兼容性', link: '/zh/reference/compatibility' },
        { text: '术语表', link: '/zh/reference/glossary' },
        { text: 'CLI 命令', link: '/zh/reference/cli-commands' },
        { text: 'MCP 工具', link: '/zh/reference/mcp-tools' },
        { text: '配置', link: '/zh/reference/configuration' },
        { text: '日志文件', link: '/zh/reference/log-files' },
        { text: '限制', link: '/zh/reference/limits' }
      ]
    }
  ],
  '/zh/articles/': [
    {
      text: '技术文章',
      items: [
        { text: '概览', link: '/zh/articles/' },
        { text: 'Jugg（1）：整体方案与使用介绍', link: '/zh/articles/01-jugg-introduction/' },
        { text: 'Jugg（2）：源码增量编译方案', link: '/zh/articles/02-source-incremental-compilation/' },
        { text: 'Jugg（3）：资源增量编译', link: '/zh/articles/03-resource-incremental-compilation/' },
        { text: 'Jugg（4）：增量部署方案', link: '/zh/articles/04-incremental-deployment/' },
        { text: 'Jugg 2.0', link: '/zh/articles/05-jugg-2-0/' },
        { text: 'Jugg 节省了安卓开发多少编译时间？', link: '/zh/articles/06-time-savings/' },
        { text: 'Jugg 2.X 能力演进', link: '/zh/articles/07-jugg-2-x-evolution/' },
        { text: 'Jugg 3.0：从秒级编译到 Agent 自验证', link: '/zh/articles/08-jugg-3-0/' }
      ]
    }
  ],
  ...(isWikiDev
    ? {
        '/zh/dev/': [
          {
            text: 'Dev',
            items: [{ text: 'Wiki 元素 Demo', link: '/zh/dev/elements-demo' }]
          }
        ]
      }
    : {})
}

export default defineConfig({
  base: wikiBase,
  title: 'Jugg Wiki',
  description: 'User documentation for Jugg',
  cleanUrls: true,
  srcExclude: productionSrcExclude,
  markdown: {
    config(md) {
      const normalizeHistoricalAssets = (content: string) =>
        content.replace(/(<img\b[^>]*\bsrc=["'])(?![./]|https?:|data:)([^"']+)(["'])/g, '$1./$2$3')
      const renderHtmlInline = md.renderer.rules.html_inline ?? ((tokens, idx) => tokens[idx].content)
      const renderHtmlBlock = md.renderer.rules.html_block ?? ((tokens, idx) => tokens[idx].content)
      md.renderer.rules.html_inline = (tokens, idx, options, env, self) => {
        const content = tokens[idx].content
        if (env.relativePath?.startsWith('zh/articles/') && /^<\/?[A-Za-z][A-Za-z0-9]*>$/.test(content)) {
          return md.utils.escapeHtml(content)
        }
        const normalized = env.relativePath?.startsWith('zh/articles/') ? normalizeHistoricalAssets(content) : content
        return renderHtmlInline([{ ...tokens[idx], content: normalized }], 0, options, env, self)
      }
      md.renderer.rules.html_block = (tokens, idx, options, env, self) => {
        const content = tokens[idx].content
        const normalized = env.relativePath?.startsWith('zh/articles/') ? normalizeHistoricalAssets(content) : content
        return renderHtmlBlock([{ ...tokens[idx], content: normalized }], 0, options, env, self)
      }
    }
  },
  locales: {
    root: {
      label: 'English',
      lang: 'en-US',
      title: 'Jugg Wiki',
      description: 'User documentation for Jugg',
      themeConfig: {
        nav: englishNav,
        sidebar: englishSidebar
      }
    },
    zh: {
      label: '简体中文',
      lang: 'zh-CN',
      title: 'Jugg Wiki',
      description: 'Jugg 用户文档',
      themeConfig: {
        nav: chineseNav,
        sidebar: chineseSidebar
      }
    }
  },
  themeConfig: {
    search: {
      provider: 'local'
    }
  }
})
