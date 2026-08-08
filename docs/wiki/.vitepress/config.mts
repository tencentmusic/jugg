import { defineConfig } from 'vitepress'

const isWikiDev = process.env.JUGG_WIKI_DEV === 'true' || process.argv.includes('dev')
const productionSrcExclude = isWikiDev ? [] : ['dev/**', 'zh/dev/**']
const wikiBase = process.env.JUGG_WIKI_BASE || '/'

const englishNav = [
  { text: 'Onboarding', link: '/onboarding/' },
  { text: 'Guide', link: '/guide/' },
  { text: 'How It Works', link: '/concepts/' },
  { text: 'Capabilities', link: '/capabilities/' },
  { text: 'Troubleshooting', link: '/troubleshooting/' },
  { text: 'Reference', link: '/reference/' },
  ...(isWikiDev ? [{ text: 'Dev', link: '/dev/elements-demo' }] : [])
]

const englishSidebar = {
  '/onboarding/': [
    {
      text: 'Onboarding',
      items: [
        { text: 'Overview', link: '/onboarding/' },
        { text: 'Installation', link: '/onboarding/installation' },
        { text: 'First Run', link: '/onboarding/first-run' },
        { text: 'Agent Setup', link: '/onboarding/agent-setup' }
      ]
    }
  ],
  '/guide/': [
    {
      text: 'Guide',
      items: [
        { text: 'Overview', link: '/guide/' },
        { text: 'Run App', link: '/guide/run' },
        { text: 'Jugg Control Panel', link: '/guide/control-panel' },
        { text: 'Run Configuration and Variants', link: '/guide/run-configuration' },
        { text: 'Debug', link: '/guide/debug' },
        { text: 'Android Test', link: '/guide/android-test' },
        { text: 'CLI', link: '/guide/cli' },
        { text: 'MCP', link: '/guide/mcp' },
        { text: 'UI Inspection', link: '/guide/ui-inspection' },
        { text: 'Remote Gradle', link: '/guide/remote-gradle' },
        { text: 'Custom Compiler', link: '/guide/custom-compiler' },
        { text: 'Advanced Options', link: '/guide/advanced-options' },
        {
          text: 'Jugg Backend',
          collapsed: true,
          items: [
            { text: 'Overview', link: '/guide/jugg-backend/' },
            { text: 'Self-hosting Checklist', link: '/guide/jugg-backend/self-hosting' },
            { text: 'Project Configuration', link: '/guide/jugg-backend/project-config' },
            { text: 'Plugin Delivery', link: '/guide/jugg-backend/plugin-delivery' },
            { text: 'Diagnostics', link: '/guide/jugg-backend/diagnostics' },
            { text: 'Remote Server Apply', link: '/guide/jugg-backend/remote-server-apply' }
          ]
        }
      ]
    }
  ],
  '/concepts/': [
    {
      text: 'How It Works',
      items: [
        { text: 'Overview', link: '/concepts/' },
        { text: 'How Jugg Works', link: '/concepts/how-jugg-works' },
        {
          text: 'Incremental Compile',
          collapsed: false,
          items: [
            { text: 'Overview', link: '/concepts/incremental-compile/' },
            { text: 'Android Manifest', link: '/concepts/incremental-compile/manifest' },
            { text: 'Release Incremental Compile', link: '/concepts/incremental-compile/release-compile' }
          ]
        },
        { text: 'Deploy Strategy', link: '/concepts/deploy-strategy' },
        { text: 'Fallback and Limits', link: '/concepts/fallback-and-limits' },
        { text: 'Project Model', link: '/concepts/project-model' },
        { text: 'Project Information Refresh', link: '/concepts/project-info-refresh' },
        { text: 'Compile Pipeline', link: '/concepts/compile-pipeline' },
        { text: 'Deploy Data and Impact', link: '/concepts/deploy-data-and-impact' },
        { text: 'JVMTI Agent', link: '/concepts/jvmti-agent' },
        { text: 'Android Test Flow', link: '/concepts/android-test-flow' },
        { text: 'MCP and CLI', link: '/concepts/mcp-and-cli' },
        { text: 'Compatibility Layer', link: '/concepts/compatibility-layer' }
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
            { text: 'Incremental Compile', link: '/capabilities/compile/incremental-compile' },
            { text: 'KMP and Compose Multiplatform', link: '/capabilities/compile/kmp-compose-multiplatform' },
            { text: 'Dependency Incremental Compile', link: '/capabilities/compile/dependency-incremental' },
            { text: 'Resource Compile', link: '/capabilities/compile/resource-compile' },
            { text: 'DataBinding and ViewBinding', link: '/capabilities/compile/databinding-viewbinding' },
            { text: 'Manifest', link: '/capabilities/compile/manifest' },
            { text: 'Native Library Update', link: '/capabilities/compile/so-update' },
            { text: 'Release Compile', link: '/capabilities/compile/release-compile' },
            { text: 'Constant Reference Analysis', link: '/capabilities/compile/const-ref' },
            { text: 'AabResGuard', link: '/capabilities/compile/aab-resguard' },
            { text: 'Gradle Fallback', link: '/capabilities/compile/gradle-fallback' },
            { text: 'Custom Compiler', link: '/capabilities/compile/custom-compiler' }
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
            { text: 'Multi APK', link: '/capabilities/deploy/multi-apk' },
            { text: 'Multi Device', link: '/capabilities/deploy/multi-device' },
            { text: 'Deploy History and Cache', link: '/capabilities/deploy/deploy-history-cache' },
            { text: 'HarmonyOS Compatible Deploy', link: '/capabilities/deploy/harmonyos-compat' },
            { text: 'JVMTI Runtime', link: '/capabilities/deploy/jvmti-runtime' }
          ]
        },
        {
          text: 'Test',
          collapsed: false,
          items: [
            { text: 'Overview', link: '/capabilities/test/' },
            { text: 'Android Test', link: '/capabilities/test/android-test' },
            { text: 'Library Test APK', link: '/capabilities/test/library-test-apk' },
            { text: 'Test Results UI', link: '/capabilities/test/test-results-ui' },
            { text: 'Logcat Attribution', link: '/capabilities/test/logcat-attribution' }
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
                { text: 'Build and Deploy', link: '/capabilities/tools/cli-build-deploy' },
                { text: 'Run Context and No-change Results', link: '/capabilities/tools/run-context-and-no-change' },
                { text: 'Android Test', link: '/capabilities/tools/cli-android-test' },
                { text: 'Runtime and Device', link: '/capabilities/tools/cli-runtime-device' },
                { text: 'UI Automation', link: '/capabilities/tools/ui-automation' },
                { text: 'UI Layout Evidence', link: '/capabilities/tools/layout-verify' },
                { text: 'Remote Diagnosis', link: '/capabilities/tools/remote-diagnosis' }
              ]
            },
            { text: 'MCP for Agents', link: '/capabilities/tools/mcp' }
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
        { text: 'Compile', link: '/troubleshooting/compile' },
        { text: 'Deploy', link: '/troubleshooting/deploy' },
        { text: 'Runtime', link: '/troubleshooting/runtime' },
        { text: 'Logs', link: '/troubleshooting/logs' },
        { text: 'Android Test', link: '/troubleshooting/android-test' },
        { text: 'Debug', link: '/troubleshooting/debug' },
        { text: 'MCP and CLI', link: '/troubleshooting/mcp-cli' },
        { text: 'Remote Gradle', link: '/troubleshooting/remote-gradle' },
        { text: 'UI Tools', link: '/troubleshooting/ui-tools' },
        { text: 'Performance', link: '/troubleshooting/performance' }
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
        { text: 'CLI Commands', link: '/reference/cli-commands' },
        { text: 'MCP Tools', link: '/reference/mcp-tools' },
        { text: 'Configuration', link: '/reference/configuration' },
        { text: 'Log Files', link: '/reference/log-files' },
        { text: 'Modules', link: '/reference/modules' },
        { text: 'Limits', link: '/reference/limits' }
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
            { text: '重编译 / 扩散编译', link: '/zh/concepts/incremental-compile/recompile-propagation' },
            { text: '常量引用分析', link: '/zh/concepts/incremental-compile/const-ref' },
            { text: '资源增量编译', link: '/zh/concepts/incremental-compile/resource' },
            { text: 'DataBinding / ViewBinding', link: '/zh/concepts/incremental-compile/databinding-viewbinding' },
            { text: 'Android Manifest 编译', link: '/zh/concepts/incremental-compile/manifest' },
            { text: 'release 增量编译', link: '/zh/concepts/incremental-compile/release-compile' },
            { text: 'assets / native lib', link: '/zh/concepts/incremental-compile/assets-native' },
            { text: '依赖库增量编译', link: '/zh/concepts/incremental-compile/dependency-incremental' },
            { text: '自定义编译器', link: '/zh/concepts/incremental-compile/custom-compiler' }
          ]
        },
        { text: '部署策略', link: '/zh/concepts/deploy-strategy' },
        { text: '部署自愈机制', link: '/zh/concepts/deploy-self-healing' },
        { text: 'Gradle 回退与基线重建', link: '/zh/concepts/gradle-fallback-baseline' },
        { text: '工程上下文获取', link: '/zh/concepts/project-model' },
        { text: '工程信息刷新与恢复', link: '/zh/concepts/project-info-refresh' },
        { text: '编译调度流程', link: '/zh/concepts/compile-pipeline' },
        { text: '部署数据与影响分析', link: '/zh/concepts/deploy-data-and-impact' },
        { text: '部署状态与恢复', link: '/zh/concepts/deploy-state-recover' },
        { text: '兼容部署', link: '/zh/concepts/compat-deploy' },
        { text: 'Jugg Runtime', link: '/zh/concepts/jugg-runtime' },
        { text: 'JVMTI Agent', link: '/zh/concepts/jvmti-agent' },
        { text: 'Android Test 流程', link: '/zh/concepts/android-test-flow' },
        { text: '布局 dump 与 UI 证据', link: '/zh/concepts/layout-dump-and-ui-evidence' },
        { text: '兼容层', link: '/zh/concepts/compatibility-layer' }
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
            { text: '常量引用分析', link: '/zh/capabilities/compile/const-ref' },
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
        { text: '编译', link: '/zh/troubleshooting/compile' },
        { text: '部署', link: '/zh/troubleshooting/deploy' },
        { text: '运行时', link: '/zh/troubleshooting/runtime' },
        { text: '日志', link: '/zh/troubleshooting/logs' },
        { text: 'Android Test', link: '/zh/troubleshooting/android-test' },
        { text: 'Debug', link: '/zh/troubleshooting/debug' },
        { text: 'MCP 与 CLI', link: '/zh/troubleshooting/mcp-cli' },
        { text: '远端 Gradle', link: '/zh/troubleshooting/remote-gradle' },
        { text: 'UI 工具', link: '/zh/troubleshooting/ui-tools' },
        { text: '性能', link: '/zh/troubleshooting/performance' }
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
