---
title: Jugg Wiki
description: 让大规模 Android 工程的日常修改在 3 秒内看到效果。
layout: page
---

<main class="jugg-home">
  <section class="jugg-hero" aria-labelledby="jugg-home-title">
    <div class="jugg-hero-copy">
      <div class="jugg-product-mark">
        <img src="/assets/run_configuration.svg" alt="" width="28" height="28">
        <span>Android Studio plugin</span>
      </div>
      <h1 id="jugg-home-title">Jugg</h1>
      <p class="jugg-hero-statement">大规模 Android 工程<br><strong>3 秒看到修改效果</strong></p>
      <p class="jugg-hero-detail">
        Jugg 复用 Gradle 可信产物，只编译本轮变化及其影响范围，再让代码和资源快速生效。
      </p>
      <p class="jugg-hero-facts">仅需 IDE 插件 · 不改 Gradle 脚本 · 不修改工程文件</p>
      <div class="jugg-actions">
        <a class="jugg-button primary" href="./onboarding/">开始接入 <span aria-hidden="true">→</span></a>
        <a class="jugg-button secondary" href="./reference/compatibility">查看兼容范围</a>
      </div>
    </div>
    <div class="jugg-run-scene" aria-label="一次典型的 Jugg Run 模拟日志">
      <div class="jugg-run-toolbar">
        <span class="jugg-run-title">Jugg 运行示例</span>
        <span class="jugg-run-target">jugg:app · Pixel 8</span>
        <span class="jugg-run-live"><i></i> running</span>
      </div>
      <div class="jugg-run-baseline">
        <span>BASELINE</span>
        <strong>Gradle 产物收集完成</strong>
        <small>工程信息 · APK · 编译产物路径</small>
      </div>
      <ol class="jugg-run-log">
        <li>
          <time>0.0s</time>
          <span class="jugg-log-state blue">CHANGE</span>
          <p><strong>检测到 6 个源码文件更改</strong><small>Kotlin 4 · resources 2</small></p>
        </li>
        <li>
          <time>0.1s</time>
          <span class="jugg-log-state green">BUILD</span>
          <p><strong>编译修改的文件</strong><small>单文件编译，自动扩散编译</small></p>
        </li>
        <li>
          <time>2.2s</time>
          <span class="jugg-log-state green">DEPLOY</span>
          <p><strong>部署变更</strong><small>热重载 / 热修复</small></p>
        </li>
        <li class="complete">
          <time>2.9s</time>
          <span class="jugg-log-state done">DONE</span>
          <p><strong>App 应用新的变更</strong><small>JVMTI 技术，新增/部分修改无需重启</small></p>
        </li>
      </ol>
    </div>
  </section>

  <section class="jugg-proof" aria-label="Jugg 开源前验证数据">
    <div class="jugg-proof-item"><strong>8+</strong><span>大型 Android 工程</span></div>
    <div class="jugg-proof-item"><strong>80万+</strong><span>累计增量编译</span></div>
    <div class="jugg-proof-item"><strong>3.6万+</strong><span>累计节省编译等待（小时）</span></div>
    <p class="jugg-proof-note">以上为 Jugg 开源发布前累计验证数据；开源后不再采集工程使用统计。</p>
  </section>

  <section class="jugg-demo jugg-band">
    <header class="jugg-section-head">
      <p class="jugg-eyebrow">Demo</p>
      <h2>看一次修改，如何快速编译并生效</h2>
      <p>视频展示源码和资源修改。所有运行都从同一个 Run 入口处理，支持 Java / Kotlin / res / assets / Manifest / native.so 和更多。</p>
    </header>
    <div class="jugg-video-shell">
      <div class="jugg-video-toolbar">
        <span><i></i> Jugg demo · 大工程源码编译/资源编译演示</span>
        <a href="https://www.bilibili.com/video/BV1W3411C7PU" target="_blank" rel="noopener noreferrer">在 B 站观看 ↗</a>
      </div>
      <div class="jugg-video-frame">
        <iframe src="https://player.bilibili.com/player.html?bvid=BV1W3411C7PU&amp;page=1&amp;high_quality=1&amp;autoplay=0" title="Jugg 增量编译与热重载演示" loading="lazy" allow="fullscreen; picture-in-picture" allowfullscreen></iframe>
      </div>
    </div>
  </section>

  <section class="jugg-compat" aria-labelledby="jugg-compat-title">
    <header class="jugg-compat-head">
      <p class="jugg-eyebrow">Compatibility</p>
      <h2 id="jugg-compat-title">一套插件，覆盖新旧 Android 工程</h2>
      <p>开源前接入的工程均使用同一套通用实现，没有业务定制逻辑。</p>
    </header>
    <dl class="jugg-compat-list">
      <div><dt>Android Studio</dt><dd>2021–至今</dd></div>
      <div><dt>AGP</dt><dd>3.4–9.1</dd></div>
      <div><dt>Kotlin</dt><dd>1.3–2.2</dd></div>
      <div><dt>Android</dt><dd>8–16</dd></div>
    </dl>
    <a class="jugg-compat-link" href="./reference/compatibility">完整兼容范围 <span aria-hidden="true">→</span></a>
  </section>

  <section class="jugg-stack jugg-band">
    <header class="jugg-section-head">
      <p class="jugg-eyebrow">Coverage</p>
      <h2>广泛适配常用技术栈</h2>
      <p>覆盖源码、资源、Compose、部署、Android Test 与自动化工具，具体支持边界可进入兼容性页面确认。</p>
    </header>
    <div class="jugg-stack-grid">
      <a href="./capabilities/compile/">
        <span>编译</span>
        <strong>Java / Kotlin · Compose / KMP</strong>
        <small>res / assets / Manifest / native .so · DataBinding / ViewBinding · 注解器 · 自定义编译器</small>
      </a>
      <a href="./capabilities/deploy/">
        <span>部署</span>
        <strong>多 APK · 多设备</strong>
        <small>覆盖复杂 APK 归属与多设备部署场景</small>
      </a>
      <a href="./capabilities/test/">
        <span>Android Test</span>
        <strong>Application / Library Android Test</strong>
        <small>Test Results UI · Logcat 归因</small>
      </a>
      <a href="./capabilities/tools/">
        <span>自动化</span>
        <strong>Jugg CLI · MCP · Agent Skills</strong>
        <small>构建部署 · Android Test · UI 自动化 · 远端诊断</small>
      </a>
    </div>
    <a class="jugg-text-link" href="./reference/compatibility">查看完整能力支持范围 <span aria-hidden="true">→</span></a>
  </section>

  <section class="jugg-start jugg-band">
    <header class="jugg-section-head">
      <p class="jugg-eyebrow">First run</p>
      <h2>三步完成第一次增量 Run</h2>
      <p>先建立可信基线，再把日常小改动交给 Jugg。</p>
    </header>
    <ol class="jugg-step-list">
      <li>
        <span>01</span>
        <div>
          <strong>安装插件</strong>
          <p>在 Android Studio 中安装 Jugg，并打开现有 Android 工程。</p>
        </div>
      </li>
      <li>
        <span>02</span>
        <div>
          <strong>建立 Gradle 基线</strong>
          <p>首次运行完成完整构建，收集后续增量所需的可信产物。</p>
        </div>
      </li>
      <li>
        <span>03</span>
        <div>
          <strong>继续日常开发</strong>
          <p>修改源码或资源，再次点击 Run 查看本轮增量决策。</p>
        </div>
      </li>
    </ol>
    <a class="jugg-text-link" href="./onboarding/">打开完整接入指南 <span aria-hidden="true">→</span></a>
  </section>

  <section class="jugg-paths jugg-band">
    <header class="jugg-section-head">
      <p class="jugg-eyebrow">Explore</p>
      <h2>从你现在要做的事继续</h2>
    </header>
    <nav class="jugg-path-grid" aria-label="文档入口">
      <a href="./onboarding/"><strong>开始接入</strong><span>安装插件并完成第一次增量 Run</span></a>
      <a href="./guide/run"><strong>运行 App</strong><span>日常 Run、取消与可见结果</span></a>
      <a href="./concepts/how-jugg-works"><strong>理解工作原理</strong><span>基线、影响分析与部署决策</span></a>
      <a href="./capabilities/"><strong>检查能力范围</strong><span>源码、资源、部署与测试支持</span></a>
      <a href="./troubleshooting/"><strong>定位异常</strong><span>从可见现象和恢复动作开始</span></a>
      <a href="./capabilities/tools/agent-skills"><strong>CLI 与 Agent Skills</strong><span>把构建、部署与验证接入自动化流程</span></a>
    </nav>
  </section>

  <footer class="jugg-home-footer">
    <p>Jugg 不替代 Gradle。它把可信基线之上的日常修改压缩到几秒内。</p>
    <a href="../">English</a>
  </footer>
</main>
