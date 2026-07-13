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
install: build ## Build + install the debug APK on the connected device
	$(ADB) install -r $(DEBUG_APK)

.PHONY: run
run: install ## Install the debug APK and launch the slideshow
	$(ADB) shell am start -n $(PKG_DEBUG)/$(ACTIVITY)

.PHONY: restart
restart: ## Force-stop and relaunch the debug app (re-runs the fixture importer)
	$(ADB) shell am force-stop $(PKG_DEBUG)
	$(ADB) shell am start -n $(PKG_DEBUG)/$(ACTIVITY)

.PHONY: uninstall
uninstall: ## Uninstall the debug app
	$(ADB) uninstall $(PKG_DEBUG)

.PHONY: logcat
logcat: ## Tail app logs (PhotoFrame tags)
	$(ADB) logcat -v time PhotoFrame:V FixtureImporter:V AndroidRuntime:E '*:S'

## ---- Install / run (release) ---------------------------------------------------------

.PHONY: install-release
install-release: release ## Build + install the signed release APK
	$(ADB) install -r $(RELEASE_APK)

.PHONY: run-release
run-release: install-release ## Install the release APK and launch the slideshow
	$(ADB) shell am start -n $(PKG_RELEASE)/$(ACTIVITY)

## ---- Fixtures (Phase 1 acceptance) ---------------------------------------------------

.PHONY: fixtures
fixtures: ## Push photos into the debug app's folder: make fixtures DIR=~/photos
	@test -n "$(DIR)" || { echo "Usage: make fixtures DIR=/path/to/photos"; exit 1; }
	$(ADB) shell mkdir -p /sdcard/Android/data/$(PKG_DEBUG)/files/debug_photos
	$(ADB) push $(DIR)/. /sdcard/Android/data/$(PKG_DEBUG)/files/debug_photos/
	@$(MAKE) --no-print-directory restart

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
