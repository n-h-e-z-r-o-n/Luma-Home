# Google Play release preparation

Status on 19 October 2026: local internal-test candidate only. No Play Console app, upload or rollout has been created by this task. The owner confirmed having a developer account; publisher details are still pending.

## Prepared
- Package: com.lumahome.launcher. Display name: Luma Home, pending confirmation of Luma/Luna spelling. Version 0.5.2, code 15; min SDK 21, target SDK 34.
- Original supplied icon preserved in branding/icon-source.png and used by the APK. Legacy and adaptive icon resources plus a 320x180 xhdpi TV banner are declared in the manifest.
- Android TV feature is required; touchscreen is not required. The leanback launcher entry remains available.
- Signed non-debug AAB: out/luma-home.aab. Signed release APK: out/luma-home-release.apk. Debug TV preview: out/luma-home.apk. Release output does not overwrite the debug APK.
- Bundletool validation and universal-APK generation are performed locally. There are no native .so libraries in this build; there is no app-owned native ABI or ELF page-alignment issue to resolve. This is not evidence of testing on every 64-bit/16 KB device.
- Store artwork drafts: branding/play-icon-512.png, play-tv-banner-1280x720.png and play-feature-graphic-1024x500.png. The square icon is an upscale of the supplied 136x136 original; a higher-resolution original would improve store presentation. The matching banner is AI-assisted and should receive final brand review.
- Listing and privacy-policy drafts: release/store-listing-draft.md and release/privacy-policy-draft.md.

## Build
Run `python build.py --bundle` from the project directory. It refuses --debug with --bundle, compiles resources with AAPT2, builds DEX, packages protobuf resources, creates the bundle, signs it with jarsigner and validates it with bundletool. Signing secrets are read from the existing local key/password file or environment and are not included in the bundle.

Google's bundletool 1.18.3 is downloaded from https://github.com/google/bundletool/releases/tag/1.18.3. Set BUNDLETOOL to its all-JAR path if tools/bundletool-all-1.18.3.jar is absent. Set AAPT2 to the Maven-distributed executable when preparing a bundle on another machine. The local tools/aapt2.exe came from the existing official Maven cache, version 8.13.2-14304508. Tool binaries are ignored by source control.

The current private key is also used for TV preview updates. Do not delete or rotate it casually. Decide Play App Signing/upload-key ownership and make a secure owner-controlled backup before the first upload; signing key enrollment affects future sideload/Play update compatibility. No signing credential was uploaded by this task.

## Remaining before submission
1. Confirm the final app name, permanent package ID and owner/publisher identity. Supply a public support email.
2. Replace privacy placeholders, review optional provider requests and the Data safety form, then host a public privacy-policy page and add its URL/in-app access. The current in-app local-preferences explanation is not a hosted policy.
3. Prepare clean TV screenshots from a test profile. Existing audit screenshots show personal wallpaper/app choices and third-party recommendation artwork; they have not been selected for store use.
4. Review banner/icon fidelity; obtain a high-resolution source for the original logo if available. Verify all listing assets in Play Console.
5. Test the release/bundle-generated APK on additional Android TV versions and review Android TV quality criteria, remote navigation, accessibility, default-home setup/recovery and error handling. Current real-TV evidence is TCL Android 11. Video preview playback remains unverified with a provider that supplies a usable link.
6. In Play Console, create the app, enable Android TV distribution, complete app access, ads, audience, content rating and Data safety declarations, configure Play App Signing and upload first to internal testing. Do not select declarations or target audiences without the owner's actual business/content decisions.
7. If this is a personal developer account created after 13 November 2023, check its applicable production-access testing requirements; current guidance describes 12 continuously opted-in testers for 14 days. Internal testing alone does not satisfy that closed-test requirement.

## Official references checked
- [TV distribution and AAB requirements](https://developer.android.com/training/tv/publishing/distribute)
- [TV icons and banner resources](https://developer.android.com/design/ui/tv/guides/system/tv-app-icon-guidelines)
- [Target API requirements](https://developer.android.com/google/play/requirements/target-sdk) ? current Android TV exception requires target 34 or higher.
- [Bundle construction and signing](https://developer.android.com/build/building-cmdline)
- [Store preview assets](https://support.google.com/googleplay/android-developer/answer/9866151)
- [User data/privacy policy](https://support.google.com/googleplay/android-developer/answer/10144311)
- [Personal-account testing](https://support.google.com/googleplay/android-developer/answer/14151465)
