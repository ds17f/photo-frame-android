# Photo Frame — dev shortcuts. Run `make help` for the list.
# The Fire HD is the acceptance target (PLAN.md); these wrap the common adb/gradle loops.

GRADLE      := ./gradlew
ADB         := adb

# Debug build carries the `.debug` applicationId suffix; the Activity class keeps the
# base namespace, so the launch component is <appId>/com.photoframe.SlideshowActivity.
PKG_DEBUG   := com.photoframe.debug
PKG_RELEASE := com.photoframe
ACTIVITY    := com.photoframe.SlideshowActivity

DEBUG_APK   := app/build/outputs/apk/debug/app-debug.apk
RELEASE_APK := app/build/outputs/apk/release/app-release.apk

# `make fixtures DIR=~/photos` pushes images into the debug folder for the ~200-photo test.
DIR         ?=

# Fire tablets have multiple user profiles (e.g. a second family member). `adb install` can
# silently land the app on the WRONG profile — it reports "Success" but `am start` (which runs
# as the active user) then fails with "Activity does not exist". So every install/launch targets
# the *active* user explicitly. `-d` allows reinstalling over an equal/older versionCode.
# CURR_USER resolves at recipe time (needs a device); falls back to 0.
CURR_USER    = $$(u=$$($(ADB) shell am get-current-user 2>/dev/null | tr -d '\r'); [ -n "$$u" ] && echo $$u || echo 0)

.DEFAULT_GOAL := help

.PHONY: help
help: ## Show this help
	@grep -hE '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}'

## ---- Build ---------------------------------------------------------------------------

.PHONY: build
build: ## Assemble the debug APK
	$(GRADLE) :app:assembleDebug

.PHONY: release
release: ## Assemble the signed release APK
	$(GRADLE) :app:assembleRelease

.PHONY: clean
clean: ## Gradle clean
	$(GRADLE) clean

## ---- Install / run (debug) -----------------------------------------------------------

.PHONY: install
install: build ## Build + install the debug APK on the active user profile
	$(ADB) install -r -d --user $(CURR_USER) $(DEBUG_APK)

.PHONY: run
run: install ## Install the debug APK and launch the slideshow
	$(ADB) shell am start --user $(CURR_USER) -n $(PKG_DEBUG)/$(ACTIVITY)

.PHONY: restart
restart: ## Force-stop and relaunch the debug app (re-runs the fixture importer)
	$(ADB) shell am force-stop $(PKG_DEBUG)
	$(ADB) shell am start --user $(CURR_USER) -n $(PKG_DEBUG)/$(ACTIVITY)

.PHONY: uninstall
uninstall: ## Uninstall the debug app (all profiles)
	$(ADB) uninstall $(PKG_DEBUG)

.PHONY: logcat
logcat: ## Tail app logs (playback + sync/cache tags)
	$(ADB) logcat -v time Slideshow:V PhotoPrefetcher:V MetadataSyncer:V SyncManager:V SyncWorker:V \
		CacheEvictor:V CacheMaintainer:V PhotoCache:V ImmichImport:V FixtureImporter:V \
		GPhotosPickerSession:V CrashRestartHandler:V AndroidRuntime:E '*:S'

## ---- Install / run (release) ---------------------------------------------------------

.PHONY: install-release
install-release: release ## Build + install the signed release APK on the active user profile
	$(ADB) install -r -d --user $(CURR_USER) $(RELEASE_APK)

.PHONY: run-release
run-release: install-release ## Install the release APK and launch the slideshow
	$(ADB) shell am start --user $(CURR_USER) -n $(PKG_RELEASE)/$(ACTIVITY)

.PHONY: uninstall-release
uninstall-release: ## Uninstall the release app (all profiles; clean fresh-install reset)
	$(ADB) uninstall $(PKG_RELEASE)

.PHONY: wipe
wipe: ## Remove BOTH debug + release apps from all profiles (full reset)
	-$(ADB) uninstall $(PKG_DEBUG)
	-$(ADB) uninstall $(PKG_RELEASE)
	@echo "Wiped. Use 'make run-release' for the frame or 'make run' for the debug/fixtures build."

## ---- Fixtures (Phase 1 acceptance) ---------------------------------------------------

.PHONY: fixtures
fixtures: ## Push photos into the debug app's folder: make fixtures DIR=~/photos
	@test -n "$(DIR)" || { echo "Usage: make fixtures DIR=/path/to/photos"; exit 1; }
	$(ADB) shell mkdir -p /sdcard/Android/data/$(PKG_DEBUG)/files/debug_photos
	$(ADB) push $(DIR)/. /sdcard/Android/data/$(PKG_DEBUG)/files/debug_photos/
	@$(MAKE) --no-print-directory restart

## ---- Immich (Phase 2) ----------------------------------------------------------------

.PHONY: immich-config
immich-config: ## Push Immich URL+key from .env to the device (no typing). PKG=com.photoframe for release.
	@test -f .env || { echo "No .env — copy .env.example to .env and fill IMMICH_API_KEY"; exit 1; }
	@set -a; . ./.env; set +a; \
	 test -n "$$IMMICH_API_KEY" || { echo "IMMICH_API_KEY is empty in .env"; exit 1; }; \
	 pkg="$${PKG:-$(PKG_DEBUG)}"; \
	 url="$${IMMICH_BASE_URL:-https://immich.example.com}"; \
	 if [ -n "$$IMMICH_PARTNER_API_KEY" ]; then \
	   printf '{"name":"Immich","baseUrl":"%s","apiKey":"%s","partnerApiKey":"%s"}' "$$url" "$$IMMICH_API_KEY" "$$IMMICH_PARTNER_API_KEY" > /tmp/immich_import.json; \
	 else \
	   printf '{"name":"Immich","baseUrl":"%s","apiKey":"%s"}' "$$url" "$$IMMICH_API_KEY" > /tmp/immich_import.json; \
	 fi; \
	 $(ADB) shell mkdir -p /sdcard/Android/data/$$pkg/files; \
	 $(ADB) push /tmp/immich_import.json /sdcard/Android/data/$$pkg/files/immich_import.json >/dev/null; \
	 rm -f /tmp/immich_import.json; \
	 echo "Pushed to $$pkg. Launch the app — it opens Immich setup pre-filled; pick albums and Save."

## ---- Quality gates -------------------------------------------------------------------

.PHONY: test
test: ## Run JVM unit tests
	$(GRADLE) :app:testDebugUnitTest

.PHONY: lint
lint: ## Run Android Lint (debug)
	$(GRADLE) :app:lintDebug

.PHONY: gms-check
gms-check: ## Fail if any GMS/Firebase/play-services dep is on the release classpath
	@$(GRADLE) -q :app:dependencies --configuration releaseRuntimeClasspath > /tmp/pf-deps.txt; \
	if grep -Eiq 'com\.google\.android\.gms|com\.google\.firebase|play-services' /tmp/pf-deps.txt; then \
		echo "FAIL: GMS/Firebase/play-services dependency found:"; \
		grep -Ein 'com\.google\.android\.gms|com\.google\.firebase|play-services' /tmp/pf-deps.txt; exit 1; \
	else echo "OK: no GMS/Firebase/play-services on the release classpath"; fi

.PHONY: check
check: test lint gms-check ## Run all quality gates
