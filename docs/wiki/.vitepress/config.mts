import { defineConfig } from 'vitepress'

const isWikiDev = process.env.JUGG_WIKI_DEV === 'true' || process.argv.includes('dev')
const productionSrcExclude = isWikiDev ? [] : ['dev/**', 'zh/dev/**']

const englishNav = [
  { text: 'Onboarding', link: '/onboarding/' },
  { text: 'Guide', link: '/guide/' },
  { text: 'Concepts', link: '/concepts/' },
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
        { text: 'Debug', link: '/guide/debug' },
        { text: 'Android Test', link: '/guide/android-test' },
        { text: 'CLI', link: '/guide/cli' },
        { text: 'MCP', link: '/guide/mcp' },
        { text: 'UI Inspection', link: '/guide/ui-inspection' },
        { text: 'Remote Gradle', link: '/guide/remote-gradle' },
        { text: 'Custom Compiler', link: '/guide/custom-compiler' },
        {
          text: 'Advanced Results',
          collapsed: true,
          items: [
            { text: 'Compile Stage', link: '/guide/compile' },
            { text: 'Deploy Results', link: '/guide/deploy' }
          ]
        }
      ]
    }
  ],
  '/concepts/': [
    {
      text: 'Concepts',
      items: [
        { text: 'Overview', link: '/concepts/' },
        { text: 'How Jugg Works', link: '/concepts/how-jugg-works' },
        { text: 'Incremental Compile', link: '/concepts/incremental-compile' },
        { text: 'Deploy Strategy', link: '/concepts/deploy-strategy' },
        { text: 'Fallback and Limits', link: '/concepts/fallback-and-limits' },
        { text: 'Project Model', link: '/concepts/project-model' },
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
  { text: '概念', link: '/zh/concepts/' },
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
        { text: 'Debug', link: '/zh/guide/debug' },
        { text: 'Android Test', link: '/zh/guide/android-test' },
        { text: 'CLI', link: '/zh/guide/cli' },
        { text: 'MCP', link: '/zh/guide/mcp' },
        { text: 'UI 检查', link: '/zh/guide/ui-inspection' },
        { text: '远端 Gradle', link: '/zh/guide/remote-gradle' },
        { text: '自定义编译器', link: '/zh/guide/custom-compiler' },
        {
          text: '进阶结果说明',
          collapsed: true,
          items: [
            { text: '编译阶段说明', link: '/zh/guide/compile' },
            { text: '部署结果说明', link: '/zh/guide/deploy' }
          ]
        }
      ]
    }
  ],
  '/zh/concepts/': [
    {
      text: '概念',
      items: [
        { text: '概览', link: '/zh/concepts/' },
        { text: 'Jugg 工作原理', link: '/zh/concepts/how-jugg-works' },
        { text: '增量编译', link: '/zh/concepts/incremental-compile' },
        { text: '部署策略', link: '/zh/concepts/deploy-strategy' },
        { text: '回退与限制', link: '/zh/concepts/fallback-and-limits' },
        { text: '项目模型', link: '/zh/concepts/project-model' },
        { text: '编译流水线', link: '/zh/concepts/compile-pipeline' },
        { text: '部署数据与影响分析', link: '/zh/concepts/deploy-data-and-impact' },
        { text: 'JVMTI Agent', link: '/zh/concepts/jvmti-agent' },
        { text: 'Android Test 流程', link: '/zh/concepts/android-test-flow' },
        { text: 'MCP 与 CLI', link: '/zh/concepts/mcp-and-cli' },
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
        { text: '模块', link: '/zh/reference/modules' },
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
