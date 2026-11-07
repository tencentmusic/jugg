# Global directory migration

Purpose: Allow users to relocate Jugg's global storage.
Decision: Resolve ~/.juggdir centrally, use process leases to reject multiple Studio instances, copy and validate before publishing, stop standalone runtimes and restart, then automatically remove the old directory on startup.
Impact: Changes global path discovery, bootstrap packaging, CLI/hooks installation, and IDE lifecycle; project compile/deploy behavior stays unchanged.

## Approved scope

- `.juggdir` contains `juggdir: <absolute path>`; unreadable/malformed content falls back to the default path. Preserve explicit `jugg.root.dir` overrides.
- JVM root resolution and derived paths belong to JuggGlobalPathManager; Bootstrap shares the same source implementation. Python uses one shared resolver.
- An independent JuggProcessLease owns process lifetime locks; multiple Studio processes using the root reject migration.
- A dedicated migration class copies and validates before writing the marker. No admission barrier or global shutdown before copying. Restart stops standalone runtimes; next startup cleans the old directory without revalidating copied content.
- IDE settings expose directory selection and restart. Repair managed CLI, hooks, skills, and generated launcher references before deleting old storage.
- Add focused behavior tests and real-process lease verification; compile affected modules and inspect generated distribution. Update English maintainer documentation and mirrored user documentation.
- No live root switching, new MCP/CLI migration command, or broad process coordination framework.

## Verification and completion

Implemented the approved scope. The locator and lease registry stay under user home; migration state stays in the destination. Cleanup runs during IDEA Runtime initialization with the registered Jugg logger. Generated launcher repair also supplies the explicit root argument for pre-feature Bootstrap versions.

Verification: JDK 17 targeted main migration/path/real-process lease tests, Bootstrap tests, six standalone installer tests, nine Hook installer tests, and six IDEA Bundle service tests passed. IDEA and CLI Kotlin compilation and Bundle generation passed. Bundle Bootstrap dependency hashes were checked. Python CLI (24 tests), hook suite (81 tests), locator test, and Wiki validator passed. Python 3.7 compatibility checker passed its static checks; the 3.7 runtime is unavailable. Real Android Studio UI/restart and native Windows execution remain manual checks.

Environment: JDK 21 hit the repository's Gradle 7.3.3/toolchain incompatibilities. Verification used installed JDK 17, excluded the unrelated JVMTI agent Bundle task, and used a temporary Gradle init script to remove the existing obsolete MaxPermSize test JVM argument; repository build policy was not changed.

Read-only review identified and fixed non-following deletion, Windows lease byte-range metadata, and lexical/canonical old-root reference repair. Regression includes an old-root symlink and an external sentinel directory.

Required cases: default/malformed locator fallback, valid relocated root, process lease exclusion and exit release, failed copy leaves locator unchanged, copied settings/database consistency, successful restart cleanup, launcher and hook use after old-root removal.

Additional final verification: migrated launchers and real agent hook commands execute after two successive moves, including a destination containing spaces and a literal dollar sign. An isolated HOME smoke installed the generated Bundle via `.juggdir` into such a directory, verified Standalone runtime JARs, and ran installed CLI help. Old-root lexical aliases, external-directory symlinks, and cleanup of the old root symlink are covered. Reviewer follow-up also covered script quoting and repeated migration.

Final review: all reported findings are resolved; private Hook JSON relocation now meets method length/nesting rules. Final main and IDEA Hook targeted regression passed after these changes. Across selected suites, 38 JVM tests passed; Python coverage comprised 24 CLI tests, 81 Hook tests, and one locator test. No user storage was migrated during development; installation smoke used a temporary HOME.
