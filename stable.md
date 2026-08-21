# 1.0.8

- **Bugfixes:**
  - Fixed profile navbar expanding to fill screen (vertical/horizontal)
  - Fixed crash when app returned from background on ProfileActivity (windowRecomposer)
  - Updated Discord/GitHub/Telegram links to Nahiro18 fork
  - Fixed strings typos and FAQ invites

- **Security & Build:**
  - Restricted user CA trust to debug builds only
  - Added backup/data extraction rules (exclude tokens)
  - OkHttp 5.0.0-alpha.14 → 4.12.0, Compose BOM 2024.02 → 2024.10, gson/jsoup patches
  - Toolchain Kotlin 2.2.20, AGP 8.13.0, Gradle 8.13, compileSdk/targetSdk 36 (minSdk 23 kept)

# 1.0.5

- **Bugfixes:**
  - Fix a crash after watching a video
