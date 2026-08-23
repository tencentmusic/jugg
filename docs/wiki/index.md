---
title: Jugg Wiki
description: See everyday changes in large Android projects take effect in 3 seconds.
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
      <p class="jugg-hero-statement">Large Android projects.<br><strong>See changes in 3 seconds.</strong></p>
      <p class="jugg-hero-detail">
        Jugg reuses trusted Gradle output, compiles only the current change and its impact, then makes code and resources take effect fast.
      </p>
      <p class="jugg-hero-facts">IDE plugin only · no Gradle script changes · no project file changes</p>
      <div class="jugg-actions">
        <a class="jugg-button primary" href="./onboarding/">Get started <span aria-hidden="true">→</span></a>
        <a class="jugg-button secondary" href="./reference/compatibility">Check compatibility</a>
      </div>
    </div>
    <div class="jugg-run-scene" aria-label="A simulated log for a typical Jugg Run">
      <div class="jugg-run-toolbar">
        <span class="jugg-run-title">Jugg Run · typical</span>
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
        <span>Uncertain state never continues incrementally</span>
        <a href="./concepts/fallback-and-limits">Fallback rules →</a>
      </div>
    </div>
  </section>

  <section class="jugg-proof" aria-label="Jugg validation before its open-source release">
    <div class="jugg-proof-item"><strong>8+</strong><span>large Android projects</span></div>
    <div class="jugg-proof-item"><strong>800K+</strong><span>incremental compiles</span></div>
    <div class="jugg-proof-item"><strong>36K+</strong><span>hours of build waiting saved</span></div>
    <p class="jugg-proof-note">Accumulated before Jugg's open-source release. Usage statistics are no longer collected after open sourcing.</p>
  </section>

  <section class="jugg-demo jugg-band">
    <header class="jugg-section-head"><p class="jugg-eyebrow">Demo</p><h2>Watch one change compile and take effect</h2><p>The video shows source and resource changes. In everyday use, Java / Kotlin, res / assets, Manifest, and native .so changes all enter through the same Run action.</p></header>
    <div class="jugg-video-shell">
      <div class="jugg-video-toolbar"><span><i></i> Jugg demo · source + resources</span><a href="https://www.bilibili.com/video/BV1W3411C7PU" target="_blank" rel="noopener noreferrer">Watch on Bilibili ↗</a></div>
      <div class="jugg-video-frame"><iframe src="https://player.bilibili.com/player.html?bvid=BV1W3411C7PU&amp;page=1&amp;high_quality=1&amp;autoplay=0" title="Jugg incremental compile and hot reload demo" loading="lazy" allow="fullscreen; picture-in-picture" allowfullscreen></iframe></div>
      <p class="jugg-video-caption">Recorded with an earlier UI. The incremental compile and hot reload behavior remains representative.</p>
    </div>
  </section>

  <section class="jugg-compat" aria-labelledby="jugg-compat-title">
    <header class="jugg-compat-head"><p class="jugg-eyebrow">Compatibility</p><h2 id="jugg-compat-title">One plugin across old and new Android stacks</h2><p>All projects integrated before open sourcing used the same general implementation, without business-specific logic.</p></header>
    <dl class="jugg-compat-list">
      <div><dt>Android Studio</dt><dd>2021–Current</dd></div>
      <div><dt>AGP</dt><dd>3.4–9.1</dd></div>
      <div><dt>Kotlin</dt><dd>1.3–2.2</dd></div>
      <div><dt>Android</dt><dd>8–16</dd></div>
    </dl>
    <a class="jugg-compat-link" href="./reference/compatibility">Full compatibility <span aria-hidden="true">→</span></a>
  </section>

  <section class="jugg-stack jugg-band">
    <header class="jugg-section-head"><p class="jugg-eyebrow">Coverage</p><h2>Broad support for common Android stacks</h2><p>Support spans source, resources, Compose, deployment, Android Test, and automation tools. Check compatibility for the complete boundaries.</p></header>
    <div class="jugg-stack-grid">
      <a href="./capabilities/compile/"><span>Compile</span><strong>Java / Kotlin · Compose / KMP</strong><small>res / assets / Manifest / native .so · DataBinding / ViewBinding · annotation processors · custom compilers</small></a>
      <a href="./capabilities/deploy/"><span>Deploy</span><strong>Multi-APK · multi-device</strong><small>Support for complex APK ownership and multi-device deployment</small></a>
      <a href="./capabilities/test/"><span>Android Test</span><strong>Application / Library Android Test</strong><small>Test Results UI · Logcat attribution</small></a>
      <a href="./capabilities/tools/"><span>Automation</span><strong>Jugg CLI · MCP · Agent Skills</strong><small>Build and deploy · Android Test · UI automation · remote diagnosis</small></a>
    </div>
    <a class="jugg-text-link" href="./reference/compatibility">View complete capability coverage <span aria-hidden="true">→</span></a>
  </section>

  <section class="jugg-start jugg-band">
    <header class="jugg-section-head"><p class="jugg-eyebrow">First run</p><h2>Your first incremental Run in three steps</h2><p>Establish a trusted baseline, then hand everyday changes to Jugg.</p></header>
    <ol class="jugg-step-list">
      <li><span>01</span><div><strong>Install the plugin</strong><p>Install Jugg in Android Studio and open an existing Android project.</p></div></li>
      <li><span>02</span><div><strong>Establish the Gradle baseline</strong><p>The first Run performs a full build and collects trusted output for later deltas.</p></div></li>
      <li><span>03</span><div><strong>Continue everyday development</strong><p>Change source or resources, then Run again to see the current decision.</p></div></li>
    </ol>
    <a class="jugg-text-link" href="./onboarding/">Open the complete onboarding guide <span aria-hidden="true">→</span></a>
  </section>

  <section class="jugg-paths jugg-band">
    <header class="jugg-section-head"><p class="jugg-eyebrow">Explore</p><h2>Continue from the task in front of you</h2></header>
    <nav class="jugg-path-grid" aria-label="Documentation entry points">
      <a href="./onboarding/"><strong>Get started</strong><span>Install the plugin and complete the first incremental Run</span></a>
      <a href="./guide/run"><strong>Run an app</strong><span>Everyday Run, cancellation, and visible results</span></a>
      <a href="./concepts/how-jugg-works"><strong>Understand Jugg</strong><span>Baselines, impact analysis, and deploy decisions</span></a>
      <a href="./capabilities/"><strong>Check capabilities</strong><span>Source, resources, deploy, and test support</span></a>
      <a href="./troubleshooting/"><strong>Diagnose a problem</strong><span>Start from symptoms, logs, and run results</span></a>
      <a href="./capabilities/tools/agent-skills"><strong>CLI and Agent Skills</strong><span>Connect build, deploy, and verification to automation</span></a>
    </nav>
  </section>

  <footer class="jugg-home-footer"><p>Jugg does not replace Gradle. It compresses everyday changes above a trusted baseline into seconds.</p><a href="./zh/">中文</a></footer>
</main>
