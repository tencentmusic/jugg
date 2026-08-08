---
title: Jugg Wiki
description: 面向 Android Studio 的增量编译与部署文档入口。
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
      <p class="jugg-hero-statement">让日常 Android Run<br>只处理本轮变化</p>
      <p class="jugg-hero-detail">
        复用 Gradle 可信产物，增量编译并按设备状态部署。条件不满足时，自动回到 Gradle 重建基线。
      </p>
      <div class="jugg-actions">
        <a class="jugg-button primary" href="/zh/onboarding/">开始接入 <span aria-hidden="true">→</span></a>
        <a class="jugg-button secondary" href="/zh/reference/compatibility">查看兼容范围</a>
      </div>
    </div>
    <div class="jugg-run-scene" aria-label="一次典型的 Jugg Run 决策记录">
      <div class="jugg-run-toolbar">
        <span class="jugg-run-title">Jugg Run · example</span>
        <span class="jugg-run-target">app · Pixel 8</span>
        <span class="jugg-run-live"><i></i> running</span>
      </div>
      <div class="jugg-run-baseline">
        <span>BASELINE</span>
        <strong>Gradle output verified</strong>
        <small>project model · APK · device history</small>
      </div>
      <ol class="jugg-run-log">
        <li>
          <time>0.00s</time>
          <span class="jugg-log-state blue">CHECK</span>
          <p><strong>6 files changed</strong><small>Kotlin 4 · resources 2</small></p>
        </li>
        <li>
          <time>0.18s</time>
          <span class="jugg-log-state green">BUILD</span>
          <p><strong>Compile affected sources</strong><small>Reuse trusted Gradle classpath</small></p>
        </li>
        <li>
          <time>1.42s</time>
          <span class="jugg-log-state green">DEPLOY</span>
          <p><strong>Code swap + restart</strong><small>Selected from artifact and device state</small></p>
        </li>
        <li class="complete">
          <time>2.31s</time>
          <span class="jugg-log-state done">DONE</span>
          <p><strong>App launched</strong><small>Incremental state committed</small></p>
        </li>
      </ol>
      <div class="jugg-run-foot">
        <span>不确定时不会冒险继续增量</span>
        <a href="/zh/concepts/full-gradle-build">查看完整构建条件 →</a>
      </div>
    </div>
  </section>

  <section class="jugg-trust" aria-label="Jugg 可靠性原则">
    <div>
      <span>可信起点</span>
      <strong>Gradle 构建产物</strong>
      <p>不另造一套完整构建结果。</p>
    </div>
    <div>
      <span>每轮决策</span>
      <strong>改动与设备状态</strong>
      <p>编译和部署策略都有当前依据。</p>
    </div>
    <div>
      <span>安全收口</span>
      <strong>回退、重启或重装</strong>
      <p>状态不可信时先恢复一致性。</p>
    </div>
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
    <a class="jugg-text-link" href="/zh/onboarding/">打开完整接入指南 <span aria-hidden="true">→</span></a>
  </section>

  <section class="jugg-safety jugg-band">
    <header class="jugg-section-head">
      <p class="jugg-eyebrow">Safety model</p>
      <h2>快，不建立在跳过正确性上</h2>
      <p>Jugg 只在现有状态能够解释本轮变化时使用增量路径。</p>
    </header>
    <div class="jugg-decision-list">
      <a href="/zh/capabilities/compile/source-compile">
        <span class="jugg-decision-signal green">状态可信</span>
        <strong>只编译变化与受影响部分</strong>
        <small>源码、资源、Manifest 与依赖影响共同决定编译范围</small>
      </a>
      <a href="/zh/capabilities/deploy/hot-reload">
        <span class="jugg-decision-signal blue">产物可部署</span>
        <strong>按结果选择热替换、重启或 overlay</strong>
        <small>部署方式由本轮产物和设备历史决定</small>
      </a>
      <a href="/zh/capabilities/compile/gradle-fallback">
        <span class="jugg-decision-signal amber">状态不确定</span>
        <strong>回到 Gradle，重新建立可信基线</strong>
        <small>构建配置、依赖或关键状态变化时优先保证一致性</small>
      </a>
    </div>
  </section>

  <section class="jugg-paths jugg-band">
    <header class="jugg-section-head">
      <p class="jugg-eyebrow">Explore</p>
      <h2>从你现在要做的事继续</h2>
    </header>
    <nav class="jugg-path-grid" aria-label="文档入口">
      <a href="/zh/guide/run"><strong>运行 App</strong><span>日常 Run、取消与可见结果</span></a>
      <a href="/zh/concepts/how-jugg-works"><strong>理解工作原理</strong><span>基线、影响分析与部署决策</span></a>
      <a href="/zh/capabilities/"><strong>检查能力范围</strong><span>源码、资源、部署与测试支持</span></a>
      <a href="/zh/troubleshooting/"><strong>定位异常</strong><span>从现象、日志和运行结果开始</span></a>
      <a href="/zh/guide/remote-gradle"><strong>配置远端 Gradle</strong><span>降低本机完整构建等待</span></a>
      <a href="/zh/reference/"><strong>查阅参考</strong><span>兼容性、配置、命令与限制</span></a>
    </nav>
  </section>

  <footer class="jugg-home-footer">
    <p>Jugg 不替代 Gradle。它让可信基线之上的日常迭代更短。</p>
    <a href="/">English</a>
  </footer>
</main>
