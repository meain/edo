# Build and install Edo on the dev phone.
#   make install-debug
#   make install-release
# Override the device with: make install-debug DEVICE=adb-<serial>._adb-tls-connect._tcp

DEVICE ?= $(shell adb devices | awk '/_adb-tls-connect/ {print $$1; exit}')
ADB := adb -s $(DEVICE)
GRADLE := nix develop --command bash -c

SDK ?= $(HOME)/Android/sdk-edo
BUILD_TOOLS ?= $(lastword $(sort $(wildcard $(SDK)/build-tools/*)))
KEYSTORE ?= $(HOME)/.android/debug.keystore

DEBUG_APK := app/build/outputs/apk/debug/app-debug.apk
RELEASE_UNSIGNED := app/build/outputs/apk/release/app-release-unsigned.apk
RELEASE_APK := app/build/outputs/apk/release/app-release-signed.apk

.DEFAULT_GOAL := help
.PHONY: help debug release test install-debug install-release

help: ## Show this help
	@awk -F':.*## ' '/^[a-z-]+:.*## / {printf "  make %-16s %s\n", $$1, $$2}' $(MAKEFILE_LIST)

test: ## Run unit tests
	$(GRADLE) './gradlew --no-daemon :app:testDebugUnitTest'

debug: ## Build the debug APK
	$(GRADLE) './gradlew --no-daemon :app:assembleDebug'

release: ## Build the release APK (unsigned)
	$(GRADLE) './gradlew --no-daemon :app:assembleRelease'

install-debug: debug ## Build and install the debug APK on the phone
	$(ADB) install -r $(DEBUG_APK)

# The release build is unsigned; zipalign and sign it with the debug key so it
# installs over a debug build without wiping data.
install-release: release ## Build, sign and install the release APK on the phone
	rm -f $(RELEASE_APK).tmp $(RELEASE_APK)
	$(BUILD_TOOLS)/zipalign -f -p 4 $(RELEASE_UNSIGNED) $(RELEASE_APK).tmp
	$(BUILD_TOOLS)/apksigner sign --ks $(KEYSTORE) --ks-pass pass:android \
		--out $(RELEASE_APK) $(RELEASE_APK).tmp
	rm -f $(RELEASE_APK).tmp $(RELEASE_APK).idsig
	$(ADB) install -r $(RELEASE_APK)
