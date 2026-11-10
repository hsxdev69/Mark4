# Implementation Plan: MovieVortex 1.0 (CloudStream Fork)

## Objectives
1. Configure pre-built repositories: **Phisher Repo** (Hindi/Movies/Series) and **Gogoanime/Anime providers**.
2. Automate first-run plugin activation so users get content immediately.
3. Re-brand application identity to **MovieVortex** (App name, strings, applicationId).
4. Create a robust, zero-secret GitHub Actions CI workflow to build and export the debug APK directly as an artifact/release.

---

## Tasks

### Task 1: Pre-configure Repositories in `RepositoryManager.kt`
- Add Phisher Repo and Anime/Storm Repo to `PREBUILT_REPOSITORIES` default list.
- URLs:
  - Phisher Repo: `https://raw.githubusercontent.com/phisher98/cloudstream-extensions-phisher/refs/heads/builds/repo.json`
  - Anime/English (Stormunblessed): `https://codeberg.org/Stormunblessed/storm-ext/raw/branch/main/storm.json`

### Task 2: Re-branding to MovieVortex
- Update `app/src/main/res/values/strings.xml`:
  - `app_name`: "MovieVortex"
- Update `app/build.gradle.kts`:
  - `defaultConfig.applicationId = "com.movievortex"` (or `com.movievortex.stream`)
  - Set version name and labels.

### Task 3: Create GitHub Actions Workflow for Building APK
- Create `.github/workflows/build-apk.yml`.
- Add steps:
  - `actions/checkout@v4`
  - `actions/setup-java@v4` with Java 17 (temurin)
  - `gradle/actions/setup-gradle@v4` (cache gradle build)
  - Run `./gradlew assembleDebug --no-daemon --stacktrace`
  - Upload artifact using `actions/upload-artifact@v4` pointing to `app/build/outputs/apk/debug/*.apk`.

### Task 4: Local verification
- Check Gradle syntax and git diff to ensure all files are cleanly modified.
- Commit all changes cleanly to Git branch so it's ready to push to GitHub.
