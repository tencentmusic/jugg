---
title: Jugg Wiki
description: Documentation for Jugg incremental compile and deploy.
layout: page
---

<main class="jugg-home">
  <section class="jugg-hero">
    <div class="jugg-hero-copy">
      <p class="jugg-kicker">Android Studio plugin</p>
      <h1>Jugg</h1>
      <p class="jugg-lead">
        <span>Incremental compile and deploy for large Android projects.</span>
        <span>Reuse Gradle output, handle the current delta,</span>
        <span>and fall back when state cannot be trusted.</span>
      </p>
      <div class="jugg-actions">
        <a class="jugg-button primary" href="/onboarding/">Get started</a>
        <a class="jugg-button" href="/concepts/how-jugg-works">How Jugg works</a>
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
          <span>Read source, resource, Manifest, and device state changes.</span>
        </li>
        <li>
          <strong>Compile delta</strong>
          <span>Reuse the Gradle baseline and compile changed or impacted sources.</span>
        </li>
        <li>
          <strong>Stage artifacts</strong>
          <span>Prepare DEX, resource overlays, assets, and other local outputs.</span>
        </li>
        <li>
          <strong>Deploy safely</strong>
          <span>Choose hot reload, restart, overlay, reinstall, or Gradle fallback by state.</span>
        </li>
      </ol>
    </div>
  </section>

  <section class="jugg-evidence" aria-label="Usage evidence">
    <div>
      <strong>&lt; 3s</strong>
      <span>average compile time</span>
    </div>
    <div>
      <strong>40k+</strong>
      <span>monthly compiles</span>
    </div>
    <div>
      <strong>800k+</strong>
      <span>total compiles</span>
    </div>
    <div>
      <strong>36k+ h</strong>
      <span>waiting time saved</span>
    </div>
  </section>

  <section class="jugg-section">
    <div class="jugg-section-head">
      <p class="jugg-kicker">Start here</p>
      <h2>Choose by the task in front of you</h2>
    </div>
    <div class="jugg-entry-grid">
      <a href="/onboarding/" class="jugg-entry">
        <span>01</span>
        <strong>First setup</strong>
        <p>Install the plugin, create a run configuration, and produce the first trusted Gradle baseline.</p>
      </a>
      <a href="/guide/run" class="jugg-entry">
        <span>02</span>
        <strong>Run your app</strong>
        <p>Understand how incremental compile, deploy, and cancellation behave after pressing Run.</p>
      </a>
      <a href="/concepts/how-jugg-works" class="jugg-entry">
        <span>03</span>
        <strong>Learn the boundaries</strong>
        <p>Follow the path from Gradle baseline to resource link, impact analysis, and mixed deploy.</p>
      </a>
      <a href="/troubleshooting/" class="jugg-entry">
        <span>04</span>
        <strong>Diagnose a problem</strong>
        <p>Start from compile, deploy, runtime, logs, or performance symptoms.</p>
      </a>
    </div>
  </section>

  <section class="jugg-section jugg-capabilities">
    <div class="jugg-section-head">
      <p class="jugg-kicker">What Jugg optimizes</p>
      <h2>Reduce fixed Run costs that are larger than the current edit</h2>
    </div>
    <div class="jugg-capability-grid">
      <a href="/capabilities/compile/incremental-compile">
        <strong>Source compile</strong>
        <span>Changed Java / Kotlin sources and impacted source files.</span>
      </a>
      <a href="/capabilities/compile/resource-compile">
        <strong>Resource compile</strong>
        <span>Custom aapt2 inclink with cached resource table context.</span>
      </a>
      <a href="/capabilities/deploy/hot-reload">
        <strong>Mixed deploy</strong>
        <span>Hot reload, restart, overlay, or reinstall based on output and device state.</span>
      </a>
      <a href="/capabilities/compile/gradle-fallback">
        <strong>Gradle fallback</strong>
        <span>Rebuild the baseline when project or device state cannot be trusted.</span>
      </a>
      <a href="/guide/remote-gradle">
        <strong>Remote Gradle</strong>
        <span>Use remote build machines to reduce local full-build waiting time.</span>
      </a>
      <a href="/capabilities/test/android-test">
        <strong>Android Test</strong>
        <span>Cover test APK discovery, execution results, and logcat attribution.</span>
      </a>
    </div>
  </section>

  <section class="jugg-section jugg-boundary">
    <div>
      <p class="jugg-kicker">Safety model</p>
      <h2>Jugg does not replace Gradle</h2>
    </div>
    <p>
      Jugg treats Gradle output as the trusted starting point. First runs, build file changes, dependency or compiler option changes, uncertain annotation or instrumentation results, and inconsistent deploy history all return to Gradle before the next incremental run.
    </p>
  </section>

  <section class="jugg-section jugg-directory">
    <div class="jugg-section-head">
      <p class="jugg-kicker">Directory</p>
      <h2>Complete documentation</h2>
    </div>
    <div class="jugg-directory-grid">
      <a href="/onboarding/">Onboarding</a>
      <a href="/guide/">Guide</a>
      <a href="/concepts/">How It Works</a>
      <a href="/capabilities/">Capabilities</a>
      <a href="/troubleshooting/">Troubleshooting</a>
      <a href="/reference/">Reference</a>
      <a href="/zh/">中文</a>
    </div>
  </section>
</main>

<style>
.jugg-home {
  --home-line: rgba(13, 50, 37, 0.12);
  --home-panel: color-mix(in srgb, var(--vp-c-bg-elv) 94%, #f7faf9);
  --home-panel-soft: #f7faf9;
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

.jugg-button:hover {
  border-color: color-mix(in srgb, var(--jugg-apply) 56%, var(--home-line));
  color: var(--jugg-run);
}

.jugg-button.primary:hover {
  color: white;
  background: color-mix(in srgb, var(--jugg-run) 88%, #0a1310);
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
  background: var(--jugg-apply);
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
  color: var(--jugg-run);
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

.jugg-entry:hover,
.jugg-capability-grid a:hover,
.jugg-directory-grid a:hover {
  border-color: color-mix(in srgb, var(--jugg-apply) 58%, var(--home-line));
  background: var(--home-panel-soft);
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

.dark .jugg-home {
  --home-line: rgba(172, 194, 185, 0.17);
  --home-panel: color-mix(in srgb, var(--jugg-panel) 92%, var(--vp-c-bg));
  --home-panel-soft: color-mix(in srgb, var(--jugg-panel-soft) 88%, var(--jugg-apply) 5%);
}

.dark .jugg-button.primary {
  border-color: #10b981;
  color: #f2fff9;
  background: #10b981;
}

.dark .jugg-button.primary:hover {
  border-color: #059669;
  color: #f2fff9;
  background: #059669;
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
