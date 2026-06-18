---
title: Jugg Wiki
description: 面向 Android Studio 的增量编译与部署文档入口。
layout: page
---

<main class="jugg-home">
  <section class="jugg-hero">
    <div class="jugg-hero-copy">
      <p class="jugg-kicker">Android Studio plugin</p>
      <h1>Jugg</h1>
      <p class="jugg-lead">
        <span>大规模 Android 工程的增量编译与部署。</span>
        <span>基于 Gradle 产物，只处理本轮变化；</span>
        <span>状态不可信时回到 Gradle。</span>
      </p>
      <div class="jugg-actions">
        <a class="jugg-button primary" href="/zh/onboarding/">快速开始</a>
        <a class="jugg-button" href="/zh/concepts/how-jugg-works">了解工作原理</a>
      </div>
    </div>
    <div class="jugg-pipeline" aria-label="Jugg run pipeline">
      <div class="jugg-pipeline-head">
        <span>RUN</span>
        <span>trusted baseline</span>
      </div>
      <ol>
        <li>
          <strong>Detect changes</strong>
          <span>读取源码、资源、Manifest 与设备状态变化</span>
        </li>
        <li>
          <strong>Compile delta</strong>
          <span>复用 Gradle 基线，只编译变化与受影响部分</span>
        </li>
        <li>
          <strong>Stage artifacts</strong>
          <span>生成 DEX、resource overlay、assets 等局部产物</span>
        </li>
        <li>
          <strong>Deploy safely</strong>
          <span>按产物和设备状态选择热替换、重启或重装</span>
        </li>
      </ol>
    </div>
  </section>

  <section class="jugg-evidence" aria-label="Usage evidence">
    <div>
      <strong>&lt; 3s</strong>
      <span>平均编译耗时</span>
    </div>
    <div>
      <strong>40k+</strong>
      <span>月编译次数</span>
    </div>
    <div>
      <strong>800k+</strong>
      <span>累计编译次数</span>
    </div>
    <div>
      <strong>36k+ h</strong>
      <span>累计节省等待</span>
    </div>
  </section>

  <section class="jugg-section">
    <div class="jugg-section-head">
      <p class="jugg-kicker">Start here</p>
      <h2>按你的当前任务进入</h2>
    </div>
    <div class="jugg-entry-grid">
      <a href="/zh/onboarding/" class="jugg-entry">
        <span>01</span>
        <strong>第一次接入</strong>
        <p>安装插件、生成运行配置，并完成第一次可信 Gradle 基线。</p>
      </a>
      <a href="/zh/guide/run" class="jugg-entry">
        <span>02</span>
        <strong>日常运行 App</strong>
        <p>了解点击运行后，增量编译、部署和取消操作如何表现。</p>
      </a>
      <a href="/zh/concepts/how-jugg-works" class="jugg-entry">
        <span>03</span>
        <strong>理解机制边界</strong>
        <p>从 Gradle 基线、资源 link、扩散编译到混合部署看完整链路。</p>
      </a>
      <a href="/zh/troubleshooting/" class="jugg-entry">
        <span>04</span>
        <strong>定位异常现象</strong>
        <p>按编译、部署、运行时、日志和性能现象找到第一跳入口。</p>
      </a>
    </div>
  </section>

  <section class="jugg-section jugg-capabilities">
    <div class="jugg-section-head">
      <p class="jugg-kicker">What Jugg optimizes</p>
      <h2>减少日常 Run 中与小改动不成比例的固定耗时</h2>
    </div>
    <div class="jugg-capability-grid">
      <a href="/zh/capabilities/compile/source-compile">
        <strong>源码增量编译</strong>
        <span>Java / Kotlin 变化源码与受影响源码</span>
      </a>
      <a href="/zh/capabilities/compile/resource-compile">
        <strong>资源增量编译</strong>
        <span>定制 aapt2 inclink，复用资源表上下文</span>
      </a>
      <a href="/zh/capabilities/deploy/hot-reload">
        <strong>混合部署</strong>
        <span>热替换、重启、overlay、重装按状态选择</span>
      </a>
      <a href="/zh/capabilities/compile/gradle-fallback">
        <strong>Gradle 回退</strong>
        <span>工程或设备状态不可信时重建基线</span>
      </a>
      <a href="/zh/guide/remote-gradle">
        <strong>远端 Gradle</strong>
        <span>复用远端构建机资源，降低本机完整构建等待</span>
      </a>
      <a href="/zh/capabilities/test/application-android-test">
        <strong>Android Test</strong>
        <span>覆盖测试 APK、运行结果和 logcat 归因链路</span>
      </a>
    </div>
  </section>

  <section class="jugg-section jugg-boundary">
    <div>
      <p class="jugg-kicker">Safety model</p>
      <h2>Jugg 不替代 Gradle</h2>
    </div>
    <p>
      Jugg 把 Gradle 构建结果作为可信起点。首次运行、修改构建文件、依赖或编译参数变化、注解处理器或插桩结果无法确认、设备部署历史不一致时，会回到 Gradle 并重新收集基线产物。
    </p>
  </section>

  <section class="jugg-section jugg-directory">
    <div class="jugg-section-head">
      <p class="jugg-kicker">Directory</p>
      <h2>完整文档目录</h2>
    </div>
    <div class="jugg-directory-grid">
      <a href="/zh/onboarding/">快速开始</a>
      <a href="/zh/guide/">使用指南</a>
      <a href="/zh/concepts/">实现原理</a>
      <a href="/zh/capabilities/">能力</a>
      <a href="/zh/troubleshooting/">问题排查</a>
      <a href="/zh/reference/">参考</a>
      <a href="/">English</a>
    </div>
  </section>
</main>

<style>
.jugg-home {
  --home-line: color-mix(in srgb, var(--vp-c-divider) 88%, transparent);
  --home-panel: color-mix(in srgb, var(--vp-c-bg-elv) 92%, var(--vp-c-bg));
  --home-muted: var(--vp-c-text-2);
  box-sizing: border-box;
  width: min(1120px, calc(100vw - 40px));
  margin: 0 auto;
  padding: 72px 0 88px;
}

.jugg-home * {
  box-sizing: border-box;
}

.jugg-home a {
  text-decoration: none;
}

.jugg-hero {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(360px, 440px);
  gap: 48px;
  align-items: center;
}

.jugg-kicker {
  margin: 0 0 12px;
  color: var(--jugg-run);
  font-family: var(--vp-font-family-mono);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0;
  text-transform: uppercase;
}

.jugg-hero h1 {
  margin: 0;
  color: var(--vp-c-text-1);
  font-size: clamp(56px, 8vw, 112px);
  line-height: 0.92;
  font-weight: 780;
  letter-spacing: 0;
}

.jugg-lead {
  max-width: 680px;
  margin: 28px 0 0;
  color: var(--vp-c-text-1);
  font-size: clamp(18px, 2.2vw, 24px);
  line-height: 1.58;
  font-weight: 560;
  letter-spacing: 0;
  overflow-wrap: anywhere;
}

.jugg-lead span {
  display: block;
}

.jugg-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 32px;
}

.jugg-button {
  display: inline-flex;
  align-items: center;
  min-height: 40px;
  border: 1px solid var(--home-line);
  border-radius: 6px;
  padding: 0 16px;
  color: var(--vp-c-text-1);
  background: var(--home-panel);
  font-size: 14px;
  font-weight: 700;
}

.jugg-button.primary {
  border-color: var(--jugg-run);
  color: white;
  background: var(--jugg-run);
}

.jugg-pipeline {
  max-width: 100%;
  border: 1px solid var(--home-line);
  border-radius: 8px;
  background: var(--home-panel);
  overflow: hidden;
}

.jugg-pipeline-head {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  border-bottom: 1px solid var(--home-line);
  padding: 12px 14px;
  color: var(--vp-c-text-3);
  font-family: var(--vp-font-family-mono);
  font-size: 12px;
}

.jugg-pipeline ol {
  display: grid;
  gap: 0;
  margin: 0;
  padding: 0;
  list-style: none;
}

.jugg-pipeline li {
  position: relative;
  padding: 18px 18px 18px 44px;
}

.jugg-pipeline li + li {
  border-top: 1px solid var(--home-line);
}

.jugg-pipeline li::before {
  content: "";
  position: absolute;
  top: 24px;
  left: 18px;
  width: 8px;
  height: 8px;
  border-radius: 999px;
  background: var(--jugg-run);
}

.jugg-pipeline strong {
  display: block;
  color: var(--vp-c-text-1);
  font-family: var(--vp-font-family-mono);
  font-size: 13px;
}

.jugg-pipeline span {
  display: block;
  margin-top: 6px;
  color: var(--home-muted);
  font-size: 14px;
  line-height: 1.6;
}

.jugg-evidence {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  margin-top: 56px;
  border-top: 1px solid var(--home-line);
  border-bottom: 1px solid var(--home-line);
}

.jugg-evidence div {
  padding: 22px 20px;
}

.jugg-evidence div + div {
  border-left: 1px solid var(--home-line);
}

.jugg-evidence strong {
  display: block;
  color: var(--vp-c-text-1);
  font-family: var(--vp-font-family-mono);
  font-size: 25px;
  line-height: 1.1;
}

.jugg-evidence span {
  display: block;
  margin-top: 8px;
  color: var(--home-muted);
  font-size: 14px;
}

.jugg-section {
  margin-top: 72px;
}

.jugg-section-head {
  max-width: 760px;
}

.jugg-section h2 {
  margin: 0;
  color: var(--vp-c-text-1);
  font-size: clamp(26px, 3vw, 36px);
  line-height: 1.24;
  font-weight: 750;
  letter-spacing: 0;
}

.jugg-entry-grid,
.jugg-capability-grid,
.jugg-directory-grid {
  display: grid;
  gap: 12px;
  margin-top: 28px;
}

.jugg-entry-grid {
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.jugg-entry,
.jugg-capability-grid a {
  border: 1px solid var(--home-line);
  border-radius: 8px;
  padding: 18px;
  background: var(--home-panel);
}

.jugg-entry span {
  color: var(--vp-c-text-3);
  font-family: var(--vp-font-family-mono);
  font-size: 12px;
}

.jugg-entry strong,
.jugg-capability-grid strong {
  display: block;
  margin-top: 20px;
  color: var(--vp-c-text-1);
  font-size: 16px;
}

.jugg-entry p,
.jugg-capability-grid span,
.jugg-boundary p {
  margin: 10px 0 0;
  color: var(--home-muted);
  font-size: 14px;
  line-height: 1.72;
}

.jugg-capability-grid {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.jugg-capability-grid strong {
  margin-top: 0;
}

.jugg-boundary {
  display: grid;
  grid-template-columns: 0.7fr 1fr;
  gap: 40px;
  border-top: 1px solid var(--home-line);
  border-bottom: 1px solid var(--home-line);
  padding: 32px 0;
}

.jugg-boundary p {
  margin: 0;
  font-size: 15px;
}

.jugg-directory-grid {
  grid-template-columns: repeat(7, minmax(0, 1fr));
}

.jugg-directory-grid a {
  border: 1px solid var(--home-line);
  border-radius: 6px;
  padding: 14px 12px;
  color: var(--vp-c-text-1);
  background: var(--home-panel);
  font-size: 14px;
  font-weight: 700;
  text-align: center;
}

@media (max-width: 920px) {
  .jugg-hero,
  .jugg-boundary {
    grid-template-columns: 1fr;
  }

  .jugg-entry-grid,
  .jugg-capability-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .jugg-directory-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 640px) {
  .jugg-home {
    width: calc(100vw - 28px);
    max-width: calc(100vw - 28px);
    margin-right: 14px;
    margin-left: 14px;
    padding: 44px 0 64px;
  }

  .jugg-hero {
    gap: 32px;
  }

  .jugg-lead {
    max-width: calc(100vw - 28px);
    font-size: 18px;
    line-height: 1.56;
    word-break: break-all;
  }

  .jugg-pipeline-head {
    display: grid;
    grid-template-columns: 1fr;
    gap: 4px;
  }

  .jugg-pipeline-head span {
    min-width: 0;
  }

  .jugg-evidence,
  .jugg-entry-grid,
  .jugg-capability-grid,
  .jugg-directory-grid {
    grid-template-columns: 1fr;
  }

  .jugg-evidence div + div {
    border-top: 1px solid var(--home-line);
    border-left: 0;
  }
}
</style>
