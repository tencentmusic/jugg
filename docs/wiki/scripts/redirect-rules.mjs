/**
 * Comprehensive redirect rules for Jugg Wiki to resolve legacy URLs and 404 access.
 */

export const STATIC_REDIRECTS = {
  // Chinese flat guide/reference/onboarding routes
  'zh/run': 'zh/guide/run',
  'zh/control-panel': 'zh/guide/control-panel',
  'zh/run-configuration': 'zh/guide/run-configuration',
  'zh/downgrade-gradle': 'zh/guide/downgrade-gradle',
  'zh/export-incremental-apk': 'zh/guide/export-incremental-apk',
  'zh/restart-app': 'zh/guide/restart-app',
  'zh/clean-data': 'zh/guide/clean-data',
  'zh/multi-device': 'zh/guide/multi-device',
  'zh/android-remoteviews': 'zh/guide/android-remoteviews',
  'zh/compat-device': 'zh/guide/compat-device',
  'zh/debug': 'zh/guide/debug',
  'zh/android-test': 'zh/guide/android-test',
  'zh/test': 'zh/guide/android-test',
  'zh/cli': 'zh/guide/cli',
  'zh/mcp': 'zh/guide/mcp',
  'zh/ui-inspection': 'zh/guide/ui-inspection',
  'zh/remote-gradle': 'zh/guide/remote-gradle',
  'zh/custom-compiler': 'zh/guide/custom-compiler',
  'zh/advanced-options': 'zh/guide/advanced-options',
  'zh/report-issue': 'zh/guide/report-issue',
  'zh/jugg-backend': 'zh/guide/jugg-backend/',
  'zh/compile': 'zh/guide/compile',
  'zh/deploy': 'zh/guide/deploy',
  'zh/tools': 'zh/capabilities/tools/',

  // Chinese onboarding
  'zh/first-run': 'zh/onboarding/first-run',
  'zh/installation': 'zh/onboarding/installation',
  'zh/agent-setup': 'zh/onboarding/agent-setup',

  // Chinese reference
  'zh/compatibility': 'zh/reference/compatibility',
  'zh/configuration': 'zh/reference/configuration',
  'zh/limits': 'zh/reference/limits',
  'zh/log-files': 'zh/reference/log-files',
  'zh/glossary': 'zh/reference/glossary',
  'zh/mcp-tools': 'zh/reference/mcp-tools',
  'zh/cli-commands': 'zh/reference/cli-commands',

  // Chinese concepts
  'zh/deploy-data-and-impact': 'zh/concepts/deploy-data-and-impact',
  'zh/how-jugg-works': 'zh/concepts/how-jugg-works',

  // Chinese technical articles
  'zh/01-jugg-introduction': 'zh/articles/01-jugg-introduction/',
  'zh/02-source-incremental-compilation': 'zh/articles/02-source-incremental-compilation/',
  'zh/03-resource-incremental-compilation': 'zh/articles/03-resource-incremental-compilation/',
  'zh/04-incremental-deployment': 'zh/articles/04-incremental-deployment/',
  'zh/05-jugg-2-0': 'zh/articles/05-jugg-2-0/',
  'zh/06-time-savings': 'zh/articles/06-time-savings/',
  'zh/07-jugg-2-x-evolution': 'zh/articles/07-jugg-2-x-evolution/',
  'zh/08-jugg-3-0': 'zh/articles/08-jugg-3-0/',

  // English incremental compile under concepts
  'concepts/const-ref': 'concepts/incremental-compile/const-ref',
  'concepts/resource': 'concepts/incremental-compile/resource',
  'concepts/source': 'concepts/incremental-compile/source',
  'concepts/kmp-source': 'concepts/incremental-compile/kmp-source',
  'concepts/recompile-propagation': 'concepts/incremental-compile/recompile-propagation',
  'concepts/manifest': 'concepts/incremental-compile/manifest',
  'concepts/release-compile': 'concepts/incremental-compile/release-compile',
  'concepts/assets-native': 'concepts/incremental-compile/assets-native',
  'concepts/dependency-incremental': 'concepts/incremental-compile/dependency-incremental',
  'concepts/custom-compiler': 'concepts/incremental-compile/custom-compiler',
  'concepts/compose-multiplatform-resource': 'concepts/incremental-compile/compose-multiplatform-resource',
  'concepts/databinding-viewbinding': 'concepts/incremental-compile/databinding-viewbinding',

  // Capabilities subfolder omissions (capabilities/xxx -> capabilities/subfolder/xxx)
  'capabilities/source-compile': 'capabilities/compile/source-compile',
  'capabilities/gradle-fallback': 'capabilities/compile/gradle-fallback',
  'capabilities/manifest': 'capabilities/compile/manifest',
  'capabilities/resource-compile': 'capabilities/compile/resource-compile',
  'capabilities/so-update': 'capabilities/compile/so-update',
  'capabilities/recompile-propagation': 'capabilities/compile/recompile-propagation',
  'capabilities/custom-compiler': 'capabilities/compile/custom-compiler',
  'capabilities/databinding-viewbinding': 'capabilities/compile/databinding-viewbinding',
  'capabilities/dependency-incremental': 'capabilities/compile/dependency-incremental',
  'capabilities/kotlin-compose': 'capabilities/compile/kotlin-compose',
  'capabilities/kmp-compose-multiplatform': 'capabilities/compile/kmp-compose-multiplatform',
  'capabilities/annotation-processors': 'capabilities/compile/annotation-processors',
  'capabilities/release-compile': 'capabilities/compile/release-compile',
  'capabilities/aab-resguard': 'capabilities/compile/aab-resguard',

  'capabilities/clean-reinstall': 'capabilities/deploy/clean-reinstall',
  'capabilities/code-swap': 'capabilities/deploy/code-swap',
  'capabilities/multi-apk': 'capabilities/deploy/multi-apk',
  'capabilities/recover-and-retry': 'capabilities/deploy/recover-and-retry',
  'capabilities/custom-apk-install-script': 'capabilities/deploy/custom-apk-install-script',
  'capabilities/custom-apk-sign-script': 'capabilities/deploy/custom-apk-sign-script',
  'capabilities/deploy-history-cache': 'capabilities/deploy/deploy-history-cache',
  'capabilities/direct-overlay': 'capabilities/deploy/direct-overlay',
  'capabilities/full-swap': 'capabilities/deploy/full-swap',
  'capabilities/harmonyos-compat': 'capabilities/deploy/harmonyos-compat',
  'capabilities/hot-reload': 'capabilities/deploy/hot-reload',
  'capabilities/jvmti-runtime': 'capabilities/deploy/jvmti-runtime',
  'capabilities/multi-device': 'capabilities/deploy/multi-device',
  'capabilities/restart': 'capabilities/deploy/restart',

  'capabilities/application-android-test': 'capabilities/test/application-android-test',
  'capabilities/library-android-test': 'capabilities/test/library-android-test',
  'capabilities/logcat-attribution': 'capabilities/test/logcat-attribution',
  'capabilities/test-results-ui': 'capabilities/test/test-results-ui',

  'capabilities/cli': 'capabilities/tools/cli',
  'capabilities/agent-skills': 'capabilities/tools/agent-skills',
  'capabilities/cli-android-test': 'capabilities/tools/cli-android-test',
  'capabilities/cli-build-deploy': 'capabilities/tools/cli-build-deploy',
  'capabilities/cli-runtime-device': 'capabilities/tools/cli-runtime-device',
  'capabilities/layout-verify': 'capabilities/tools/layout-verify',
  'capabilities/mcp': 'capabilities/tools/mcp',
  'capabilities/remote-diagnosis': 'capabilities/tools/remote-diagnosis',
  'capabilities/run-context-and-no-change': 'capabilities/tools/run-context-and-no-change',
  'capabilities/ui-automation': 'capabilities/tools/ui-automation',

  // Capabilities prefix omissions (compile/*, deploy/*, tools/* -> capabilities/*)
  'compile/recompile-propagation': 'capabilities/compile/recompile-propagation',
  'compile/gradle-fallback': 'capabilities/compile/gradle-fallback',
  'compile/manifest': 'capabilities/compile/manifest',
  'compile/resource-compile': 'capabilities/compile/resource-compile',
  'compile/so-update': 'capabilities/compile/so-update',
  'compile/source-compile': 'capabilities/compile/source-compile',
  'deploy/code-swap': 'capabilities/deploy/code-swap',
  'deploy/clean-reinstall': 'capabilities/deploy/clean-reinstall',
  'deploy/multi-apk': 'capabilities/deploy/multi-apk',
  'deploy/recover-and-retry': 'capabilities/deploy/recover-and-retry',
  'tools/agent-skills': 'capabilities/tools/agent-skills',
  'tools/cli': 'capabilities/tools/cli',
  'tools/layout-verify': 'capabilities/tools/layout-verify',
  'tools/ui-automation': 'capabilities/tools/ui-automation',

  // English top-level flat routes
  'cli': 'guide/cli',
  'debug': 'guide/debug',
  'deploy': 'guide/deploy',
  'run': 'guide/run',
  'faq': 'troubleshooting/supported-packaging',
  'tools': 'capabilities/tools/',
  'compile': 'capabilities/compile/',
  'guide/self-hosting': 'guide/jugg-backend/self-hosting',
  'guide/diagnostics': 'guide/jugg-backend/diagnostics',
  'guide/plugin-delivery': 'guide/jugg-backend/plugin-delivery',
  'guide/getting-started': 'onboarding/',
  'guide/run-app': 'guide/run',

  // Misunderstood /en/ prefix
  'en/guide/jugg-backend/': 'guide/jugg-backend/',
  'en/guide/report-issue': 'guide/report-issue',

  // Offline release download attempts
  'Download jugg-3.4.3-release.zip': 'https://github.com/tencentmusic/jugg/releases',
  'download/jugg-3.4.3-release.zip': 'https://github.com/tencentmusic/jugg/releases',
  'downloa': 'https://github.com/tencentmusic/jugg/releases'
}

/**
 * Resolves a requested pathname into its redirect target, or null if no redirect matches.
 * Handles both relative and prefixed pathnames (e.g., /jugg/zh/run or zh/run).
 */
export function resolveRedirect(rawPath, base = '/') {
  if (!rawPath) return null

  // Normalize base
  const normalizedBase = base.endsWith('/') ? base : `${base}/`

  // Strip query and hash
  let pathname = rawPath.split(/[?#]/)[0]

  // Strip base prefix if present
  if (normalizedBase !== '/' && pathname.startsWith(normalizedBase)) {
    pathname = pathname.slice(normalizedBase.length)
  }

  // Strip leading slash
  pathname = pathname.replace(/^\/+/, '')

  // 1. Direct match in STATIC_REDIRECTS
  const directTarget = STATIC_REDIRECTS[pathname] || STATIC_REDIRECTS[pathname.replace(/\/+$/, '')]
  if (directTarget) {
    return formatTarget(directTarget, normalizedBase)
  }

  // 2. Dynamic pattern: /en/* -> /*
  if (pathname.startsWith('en/')) {
    const stripped = pathname.slice(3)
    return formatTarget(stripped, normalizedBase)
  }

  // 3. Dynamic pattern: Download / download package -> GitHub Releases
  if (/^download(\.html)?$/i.test(pathname) || /jugg-.*\.zip$/i.test(pathname)) {
    return 'https://github.com/tencentmusic/jugg/releases'
  }

  return null
}

function formatTarget(target, base) {
  if (target.startsWith('http://') || target.startsWith('https://')) {
    return target
  }
  const cleanTarget = target.replace(/^\/+/, '')
  return `${base}${cleanTarget}`
}
