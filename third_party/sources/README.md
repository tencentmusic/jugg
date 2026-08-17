# Corresponding Source Archives

These archives are tracked in the public Jugg source revision for redistributed components whose selected licenses require source availability or stronger traceability. Plugin distributions identify the exact revision and checksums through `third_party/SOURCE.md` and `third_party/SOURCE_SHA256SUMS`.

| Archive | Component | Reason |
|---|---|---|
| `rsync-3.4.1.tar.gz` | rsync 3.4.1 | Corresponding source for the redistributed unmodified GPL-3.0-or-later executable |
| `sshpass-1.10.tar.gz` | sshpass 1.10 | Corresponding source for the redistributed unmodified GPL-2.0-or-later executable |
| `trove4j-1.0.20200330-sources.jar` | Trove4J 1.0.20200330 | Source availability for the redistributed LGPL-2.1-or-later library |
| `juniversalchardet-1.0.3-sources.jar` | juniversalchardet 1.0.3 | Source availability for the selected MPL-1.1 terms |
| `javax.activation-1.2.0-sources.jar` | JavaBeans Activation Framework 1.2.0 | Source availability for the selected CDDL-1.1 terms |
| `openjdk-jvmti-header/jvmti.h` | OpenJDK JVMTI header | Modified source distributed by Jugg |
| `openjdk-jvmti-header/UPSTREAM_jvmti.h` | OpenJDK JVMTI header | Content-equivalent upstream baseline from `jdk8u202-b08` |
| `openjdk-jvmti-header/jvmti.h.patch` | OpenJDK JVMTI header | One-line Jugg modification against the upstream baseline |

All five archives are unmodified upstream source distributions. Their SHA-256 checksums are verified by `:idea:verifyThirdPartyCompliance`; CI release packaging also requires this directory to match the source revision.
