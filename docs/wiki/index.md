---
title: Jugg Wiki
description: Documentation for Jugg incremental compile and deploy.
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
      <p class="jugg-hero-statement">Keep everyday Android Runs<br>focused on the current change</p>
      <p class="jugg-hero-detail">
        Reuse trusted Gradle output, compile the delta, and deploy by device state. When the conditions are uncertain, Jugg returns to Gradle and rebuilds the baseline.
      </p>
      <div class="jugg-actions">
        <a class="jugg-button primary" href="/onboarding/">Get started <span aria-hidden="true">→</span></a>
        <a class="jugg-button secondary" href="/reference/compatibility">Check compatibility</a>
      </div>
    </div>
    <div class="jugg-run-scene" aria-label="A typical Jugg Run decision record">
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
        <span>Uncertain state never continues incrementally</span>
        <a href="/concepts/fallback-and-limits">Fallback rules →</a>
      </div>
    </div>
  </section>

  <section class="jugg-trust" aria-label="Jugg reliability principles">
    <div><span>Trusted start</span><strong>Gradle build output</strong><p>No separate full-build result.</p></div>
    <div><span>Every-run decision</span><strong>Change and device state</strong><p>Compile and deploy paths have current evidence.</p></div>
    <div><span>Safe closure</span><strong>Fallback, restart, or reinstall</strong><p>Restore consistency before continuing.</p></div>
  </section>

  <section class="jugg-start jugg-band">
    <header class="jugg-section-head"><p class="jugg-eyebrow">First run</p><h2>Your first incremental Run in three steps</h2><p>Establish a trusted baseline, then hand everyday changes to Jugg.</p></header>
    <ol class="jugg-step-list">
      <li><span>01</span><div><strong>Install the plugin</strong><p>Install Jugg in Android Studio and open an existing Android project.</p></div></li>
      <li><span>02</span><div><strong>Establish the Gradle baseline</strong><p>The first Run performs a full build and collects trusted output for later deltas.</p></div></li>
      <li><span>03</span><div><strong>Continue everyday development</strong><p>Change source or resources, then Run again to see the current decision.</p></div></li>
    </ol>
    <a class="jugg-text-link" href="/onboarding/">Open the complete onboarding guide <span aria-hidden="true">→</span></a>
  </section>

  <section class="jugg-safety jugg-band">
    <header class="jugg-section-head"><p class="jugg-eyebrow">Safety model</p><h2>Speed does not come from skipping correctness</h2><p>Jugg uses an incremental path only when the existing state can explain the current change.</p></header>
    <div class="jugg-decision-list">
      <a href="/capabilities/compile/incremental-compile"><span class="jugg-decision-signal green">Trusted state</span><strong>Compile only changed and affected inputs</strong><small>Source, resources, Manifest, and dependency impact define the scope</small></a>
      <a href="/capabilities/deploy/hot-reload"><span class="jugg-decision-signal blue">Deployable output</span><strong>Choose hot reload, restart, or overlay by result</strong><small>The current output and device history determine the deploy path</small></a>
      <a href="/capabilities/compile/gradle-fallback"><span class="jugg-decision-signal amber">Uncertain state</span><strong>Return to Gradle and rebuild the trusted baseline</strong><small>Build configuration, dependencies, and critical state take priority</small></a>
    </div>
  </section>

  <section class="jugg-paths jugg-band">
    <header class="jugg-section-head"><p class="jugg-eyebrow">Explore</p><h2>Continue from the task in front of you</h2></header>
    <nav class="jugg-path-grid" aria-label="Documentation entry points">
      <a href="/guide/run"><strong>Run an app</strong><span>Everyday Run, cancellation, and visible results</span></a>
      <a href="/concepts/how-jugg-works"><strong>Understand Jugg</strong><span>Baselines, impact analysis, and deploy decisions</span></a>
      <a href="/capabilities/"><strong>Check capabilities</strong><span>Source, resources, deploy, and test support</span></a>
      <a href="/troubleshooting/"><strong>Diagnose a problem</strong><span>Start from symptoms, logs, and run results</span></a>
      <a href="/guide/remote-gradle"><strong>Set up Remote Gradle</strong><span>Reduce local full-build waiting</span></a>
      <a href="/reference/"><strong>Read the reference</strong><span>Compatibility, configuration, commands, and limits</span></a>
    </nav>
  </section>

  <footer class="jugg-home-footer"><p>Jugg does not replace Gradle. It shortens everyday iteration above a trusted baseline.</p><a href="/zh/">中文</a></footer>
</main>
