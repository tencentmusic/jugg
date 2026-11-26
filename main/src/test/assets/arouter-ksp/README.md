# ARouter KSP environment fixture

Source: the user-provided `arouter-environment-bundle.zip`, archived through the `aigc-bot-client/arouter-ksp-reproduction` project.

- Preserves the real ARouter KSP provider/collector sources, `Route.app`, and the eight-argument `RouteMeta.build`; comments were translated into English.
- Pins Kotlin 2.1.20, KSP 2.1.20-1.0.32, and KotlinPoet 1.18.1; the same provider runs on KSP1 and KSP2.
- `ARouterKspFixture` compiles annotation/provider JARs in the test JVM. Test configurations in `main/build.gradle` resolve dependencies; no Android assemble, external demo directory, or environment variable is required.
- `test-providers/UnrelatedProvider.kt` fails if executed. It shares SPI/classpath with the real provider to protect selected-ARouter-only execution.
- L1: `KspProcessorRunnerTest` covers generation, arguments, executable routes, failure recovery, and isolation of old outputs.
- L2: `ARouterJuggAptProcessorTest.projectKsp*` covers both real engines, complete commonMain/androidMain aggregation, first-run Gradle baseline comparison, and checkpoint retention.

Fixture artifacts live in `main/build/ksp-fixture`; each test retains generated files in its temporary directory. This processor is not packaged in the Jugg plugin.
