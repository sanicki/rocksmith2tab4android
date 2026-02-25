gradle-wrapper.jar — how to obtain it
======================================

This file (gradle-wrapper.jar) is NOT included in this repository because it
is a binary file. You need it before you can run ./gradlew locally.

CI/GitHub Actions does NOT need this file — it is bootstrapped automatically.

──────────────────────────────────────────────────────────────────────────────
OPTION A — Download from the Gradle distributions server (recommended)
──────────────────────────────────────────────────────────────────────────────
Gradle publishes wrapper JARs directly for recent releases at:

    https://services.gradle.org/distributions/gradle-{VERSION}-wrapper.jar

This project uses Gradle 8.9:

  Linux / macOS:
    mkdir -p gradle/wrapper
    curl -Lo gradle/wrapper/gradle-wrapper.jar \
      https://services.gradle.org/distributions/gradle-8.9-wrapper.jar

  Windows (PowerShell):
    New-Item -ItemType Directory -Force gradle\wrapper | Out-Null
    Invoke-WebRequest `
      -Uri "https://services.gradle.org/distributions/gradle-8.9-wrapper.jar" `
      -OutFile "gradle\wrapper\gradle-wrapper.jar"

After downloading, verify the SHA-256 checksum matches the official value:

    Gradle 8.9 wrapper JAR SHA-256:
    498495120a03b9a6ab5d155f5de3c8f0d986a449153702fb80fc80e134484f17

  Linux / macOS:
    sha256sum gradle/wrapper/gradle-wrapper.jar

  Windows (PowerShell):
    (Get-FileHash gradle\wrapper\gradle-wrapper.jar -Algorithm SHA256).Hash.ToLower()

All Gradle release checksums are at: https://gradle.org/release-checksums/

NOTE: Not all Gradle versions have a standalone wrapper JAR download. If you
change the Gradle version in gradle-wrapper.properties, check that a
gradle-{VERSION}-wrapper.jar file actually exists on the distributions server
before directing others to download it.

──────────────────────────────────────────────────────────────────────────────
OPTION B — Copy from a GitHub repo
──────────────────────────────────────────────────────────────────────────────
Many public Android projects check in their gradle-wrapper.jar. You can copy
it from any trustworthy repo. The JAR is not version-specific — any Gradle
8.x wrapper JAR will work.

A reliable source is Google's own Android samples:
  https://github.com/android/architecture-samples/raw/main/gradle/wrapper/gradle-wrapper.jar

  Linux / macOS:
    curl -Lo gradle/wrapper/gradle-wrapper.jar \
      https://github.com/android/architecture-samples/raw/main/gradle/wrapper/gradle-wrapper.jar

After copying, verify its SHA-256 matches a known-good value from:
    https://gradle.org/release-checksums/

──────────────────────────────────────────────────────────────────────────────
OPTION C — Extract from the Gradle distribution ZIP
──────────────────────────────────────────────────────────────────────────────
Download the Gradle 8.9 binary ZIP and extract the wrapper JAR from inside it:

  Linux / macOS:
    curl -Lo /tmp/gradle-8.9-bin.zip \
      https://services.gradle.org/distributions/gradle-8.9-bin.zip
    unzip -j /tmp/gradle-8.9-bin.zip \
      "gradle-8.9/lib/gradle-wrapper.jar" \
      -d gradle/wrapper/
    mv gradle/wrapper/gradle-wrapper.jar gradle/wrapper/gradle-wrapper.jar

  The wrapper JAR is inside the ZIP at: gradle-8.9/lib/gradle-wrapper.jar

──────────────────────────────────────────────────────────────────────────────
OPTION D — Install Gradle CLI and generate it
──────────────────────────────────────────────────────────────────────────────
If you have Gradle installed locally (via SDKMAN, Homebrew, Scoop, etc.):

    gradle wrapper --gradle-version 8.9

Install options:
  SDKMAN (Linux/macOS):  sdk install gradle 8.9
  Homebrew (macOS):      brew install gradle
  Scoop (Windows):       scoop install gradle
  Full docs:             https://gradle.org/install/

──────────────────────────────────────────────────────────────────────────────
WHY IS THIS FILE NOT CHECKED IN?
──────────────────────────────────────────────────────────────────────────────
Binary files in Git cannot be reviewed for changes in pull requests. There is
also a security risk: a malicious contributor could swap the JAR for a
backdoored version and it would be difficult to detect. Because the JAR is
freely downloadable and verifiable from Gradle's own servers, there is no
benefit to committing it here.
