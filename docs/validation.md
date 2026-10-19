# TCL preview validation

Development preview: Luma Home 0.5.1, installed and checked on the connected TCL Android 11 TV on 19 October 2026. This records observed results, not a certification for other TVs.

## Clock and section-heading typography

- Version 0.5.1, code 14, changes both Home fallback and All Apps clock/date to medium weight with matching foreground color. Their sizes and date/time formats remain user-selected/as before.
- Recommendation section labels now show only the source app name, using the same 34-pixel reference size, medium weight and foreground color as Home. Heading height grows to 52 reference pixels to accommodate that text without clipping.
- Built and signature-verified; installed on the TCL. Home and All Apps screenshots were visually inspected: date/time and the YouTube heading render without clipping. Saved preferences matched before/after. Evidence: `tv-audit/luma-preview/typography-update/`.

## Wallpaper-derived theme

- Version 0.5.0, code 13, adds From wallpaper and a previewable color-intensity control. Extraction uses at most a 64-pixel-long-edge bitmap, runs on a worker, and saves the source identity and seed. No additional library or device permission was needed.
- Native TV preview: the user's forest wallpaper supplied a green hue, producing subtle dark green menu surfaces and pale green focus accents. Warm/Cool remain available; high-contrast text/focus priorities and black recommendation artwork remain.
- 73 standalone palette checks passed: transparent and neutral inputs, dominant-color selection, representative hues and intensities, alpha retention, neutral 0% accents, and generated accent/secondary-text contrast against opaque base surfaces. These checks do not guarantee contrast over every transparent wallpaper region; high contrast remains available.
- The remote-focused intensity slider changed 45% to 55%. Cancel retained 45%; subsequent Save applied 55%, then Save restored 45% for the preview. The selected From wallpaper theme is intentional. Evidence: `tv-audit/luma-preview/wallpaper-theme-update/`.
- Cold startup retained From wallpaper and 45%. Final screenshots were visually inspected. Only theme, intensity and palette-cache keys changed; Home resolution and disabled packages were unchanged.
- Imported-image changes and file picking were not retested in this update. Live blur is not implemented; the effect uses translucent color tints.

## Hero blending and proportional artwork refinement

- Version 0.4.1, code 12, changes the action label to Play while retaining the source-supplied title link. Removed Left/Right instructions and preview-navigation hints; retained a compact position count.
- A transparency mask fades the artwork and its readability gradient into the actual user wallpaper over the lower 320 reference pixels. The former opaque lower edge is gone. Text/actions remain readable above that blend.
- Artwork now scales uniformly to screen width with top alignment; changing hero height does not rescale or squeeze it. Automatic expanded height is derived from source proportions and bounded to 60?85% of screen height for navigation. Explicit 60/75/85% choices remain; the visible viewport can still omit lower artwork, rather than distort it.
- Built, signature-verified and installed on the TCL. The expanded TV screenshot was inspected for the seamless fade and proportional artwork. Evidence: `tv-audit/luma-preview/hero-blend-update/`. Video playback and its visual fade remain unverified because the current feed supplies no preview links.

## Immersive featured carousel update

- Version 0.4.0, code 11, replaces the inset rounded header card with a screen-width hero. Its focused height defaults to 810 pixels on this 1080-pixel display; Home icons remain below it. Artwork and gradient layers extend to both screen edges.
- Onyx supplied eight titles. Remote Right changed VisionQuest to Foundation and updated pagination to 2/8 while retaining the Open action focus. Down returned to the first Home app and reduced the hero height. Settings displayed source, expanded height, rotation, video and sound controls.
- Video capability now reads optional preview_video_uri fields without requiring them. Manual playback uses a supplied playable URI, with muted default, stop-on-leave and error-to-artwork fallback. No preview action appeared for the checked Onyx titles; successful video playback is not verified on this feed. App-rendered preview services are not supported in this build.
- Final checks passed for Back both during artwork loading and after loading, title browsing with retained focus, clock/date fallback with the feature disabled, and restored feature state. The final expanded screenshot was visually inspected. Saved preferences, disabled packages and Home resolution were unchanged.
- Evidence: `tv-audit/luma-preview/immersive-hero-update/`, including TV hierarchy snapshots, screenshots, package details and preference/system comparisons.

## Home icon fill update

- Version 0.3.6, code 10, removes native Home icon padding and uses proportional center-cropping within the existing circular outline. Transparent outer margins are trimmed from native icon artwork before fitting. All Apps and recommendation rendering are unchanged.
- Built and signature-verified, then installed on the TCL. Saved preferences were retained. Evidence: `tv-audit/luma-preview/home-icon-fill-update/`.

## Dynamic artwork and foreground focus update

- Signed version 0.3.5, code 9, was built, signature-verified and installed on the TCL. Card height follows the saved size; decoded image proportions determine width, with a screen-safe maximum. The size picker uses the same sizing rule.
- Loaded YouTube artwork used 312-pixel-wide frames at the selected 234-pixel height; Onyx artwork used 416-pixel-wide frames. Existing source-image letterboxing remains visible where embedded in the bitmap.
- The focus stroke now uses the artwork frame foreground, above the image, rather than a background stroke behind it. The focused full-bleed Onyx screenshot was visually inspected: the complete rounded ring is visible, including the first card edge.
- Saved preferences (including Extra large), disabled-package state and Home resolution matched before and after installation. No default-home or system-setting change was made.
- Evidence: `tv-audit/luma-preview/dynamic-artwork-update/`, including the focused screenshot, card bounds and verification result. The earlier fixed-width measurements below describe previous builds; current presets select heights 126, 144, 189 and 234, with widths derived from artwork.

## Recommendation background update

- The artwork background is now pure black across themes, both focused and unfocused. The size preview uses the same black background.
- Image fitting remains proportional and preserves the complete artwork. This avoids introducing crops while blending unused image space with black source borders.
- The signed 0.3.4 build, code 8, was installed. Saved preferences, including the selected Extra large size, were unchanged. Evidence is in `tv-audit/luma-preview/black-background-update/`.

## Recommendation size checks

- Recommendations settings now includes Card size, with Small, Standard, Large and Extra large. An in-dialog preview updates artwork dimensions and title text before Save. Cancel leaves the saved size unchanged.
- Reference artwork dimensions are 224x126, 256x144, 336x189 and 416x234. Title text scales with the card, and Standard preserves the previous dimensions. The setting applies to every recommendation row independently of Home icons and All Apps tiles.
- The slider was verified focused immediately on opening, including after touch activation. Remote arrows updated the Small preview. The saved Extra large selection survived force-stopping and reopening Luma Home, and actual recommendation artwork measured 416 pixels wide on this TV.
- Cancel was checked after previewing Small and retained the saved Extra large value. Extra large remains selected, and the picker was left open for the user.
- Existing preference values, disabled-package states and Home resolution were retained. The only additional preference is the recommendation size. The signed 0.3.3 build, code 7, was confirmed installed.
- The final picker screenshot was visually inspected. Evidence is in `tv-audit/luma-preview/recommendation-size-update/`, including `size-picker.png` and `verification-result.json`.

## Focus edge checks

- Home and recommendation content now have small scrolling end insets. A focus reveal request includes extra room around the unscaled tile so its enlargement remains clear of the screen edge.
- The horizontal scrolling viewports still span the full screen. The extra room moves with the content rather than introducing an inset clipping boundary.
- First and last Home icons and the first and last cards in an overflowing recommendation row were checked on the TV. All reported artwork bounds stayed inside the screen, including a right edge at x=1896 on the 1920-pixel display.
- The signed 0.3.2 build, code 6, was installed. Evidence and a last-item focus screenshot are in `tv-audit/luma-preview/focus-update/`.
- The screenshot was visually inspected. Saved preferences, disabled-package states and Home resolution were unchanged.

## Spacing and footer update checks

- Recommendation sections now have 72 reference pixels of surrounding section margins, with additional heading-to-artwork space. At Standard card size, the live YouTube and Onyx heading interval measured 324 pixels on the 1920-pixel TV; larger card settings expand the page further.
- Recent app shortcuts moved out of the horizontal content rails into a separate wrapping block immediately above the controls, without a visible heading. No history data or preference was reset.
- The shortcut block uses the same column count and cell margins as the controls. Its first shortcut and the All Apps control both started at x=113 in the TV hierarchy.
- Up from the controls reached the recent shortcuts; Down returned to the controls. The captured footer screenshot was inspected for spacing and alignment.
- All saved Luma preferences, disabled-package states and Home resolution matched before and after installation. Version 0.3.1, code 5 was confirmed installed.
- Evidence is in `tv-audit/luma-preview/spacing-update/`, including `footer-preview.png` and `verification-result.json`.

## Home update checks

- Favorites is now Home, including the app menu and return navigation labels. Existing chosen apps remain in their original order.
- Home contains one full-width horizontal app row, using circular app icons by default. The subsequent focus correction adds small scrolling end insets while preserving the full-width viewport; spacing remains between items.
- All visible Home app tiles shared one vertical position in the TV hierarchy. The six currently chosen apps fit within the screen; overflow with a larger personal list was not exercised during this check.
- YouTube and Onyx each displayed a recommendation row simultaneously. Both rows remained present when focus moved to other Home icons.
- Down moved from the YouTube recommendation row into Onyx recommendations, and Back returned to the source Home icon. Home app menus showed Move app and Replace app.
- D-pad navigation reached the Home footer, opened All Apps with the Back to Home label, and returned to Home. These checks used remote key events; touch-coordinate checks were discarded when the foreground changed during the run.
- The existing photo background, featured source, selected apps, artwork and all recommendation/appearance preferences matched the pre-update snapshot exactly.
- Package states, Home resolution, input IDs, animation scales, sleep/screensaver settings and keyboard settings were unchanged.
- The APK compiled and passed signature verification. Version 0.3.0, code 4 was verified as installed. Evidence and the inspected screenshot are in `tv-audit/luma-preview/home-update/`.

The 0.2.1 update adds the separate TV utilities section, per-app group overrides and navigation between partial rows. Installation succeeded after identifying the TV at its new Wi-Fi address by its matching serial number. Compilation, APK signature verification and real-TV grouping/navigation checks passed.

## Utilities update checks

- Clock, Hotel Menu, the two vendor setup entries, TCL Home Passive, TV settings and User Center appear below regular apps under TV utilities.
- D-pad Down reaches Clock in the lower section. Up returns to the same column in the preceding regular-app row, including its partial final row. Moving from the footer upward returns to the last utilities row.
- Searching for Clock returns that tile in TV utilities. The footer retains visible bottom spacing.
- Every saved Luma preference matched before and after the update, including favorites, the custom photo background, banner source, recommendation choices and appearance settings.
- Installed and disabled package sets, Home resolution, input IDs, animation scales, sleep/screensaver settings and keyboard settings matched the state immediately before this update. No default-home switch or other app change was performed.
- Evidence, before/after snapshots and the final screenshot are in `tv-audit/luma-preview/utility-update/`. The per-app Section control and Appearance grouping switch are implemented; those switches were left unchanged during this check.

## Build and local checks

- Java source compilation, Android manifest/resource linking, DEX generation, alignment and APK signature verification succeeded.
- The original required MIT notice is included as `assets/LICENSE` in the APK.
- The pure Java policy test passed 66 recommendation and clock-fallback checks.
- Python build and emulator-smoke-test scripts compile. Shell entry points pass syntax checks. The emulator smoke test was not run on this physical TV.
- The preview is a signed debug build. Keep the local signing key for compatible future updates.

## Observed on the TV

- The earlier Favorites implementation and All Apps opened, with the first All Apps tile selected after a fresh launch. The Home update above supersedes the former Favorites grid and focus-triggered recommendation behavior.
- All Apps uses the baseline warm background, rounded artwork without an extra frame, and names on focus for banner tiles. Plain icons retain their names inside the tile.
- All Apps scrolls as one full-screen page. The clock and Search leave the screen when navigating down to the footer; padding scrolls with the content.
- Home has one full-screen vertical scrolling container, with the header, horizontal app row, recommendations and controls in normal page flow. The previous fixed dock and separately scrolling middle section were removed at the user's request.
- Recommendation rows span the physical screen width. A captured Onyx row measured from the left screen edge to the right screen edge; rounded 16:9 artwork remains complete with its title below.
- Remote navigation into the home footer scrolls the whole page and leaves visible space beneath the controls.
- The settings panel uses warm translucent surfaces. Appearance reports Warm glass and always-visible names Off by default.
- Changing the background to Ocean survived force-stopping and reopening Luma Home. Ember was restored through the interface.
- Renaming YouTube to a temporary test name survived force-stopping and reopening Luma Home. Restore original returned the name to YouTube.
- The six-favorite list was preserved during those checks.
- An actual Onyx recommendation row with artwork and titles was observed in the live preview when its per-app and master switches were enabled. With no featured source selected, the header remained the clock.

Verified screenshots and UI hierarchy captures are in the adjacent workspace's `tv-audit/luma-preview/` folder: `home-unified-recommendations.png`, `home-unified-footer.png` and `all-apps-final-top.png`. A subsequent All Apps footer navigation check was interrupted when the foreground changed to Android app settings; that capture is not evidence of a passed launcher check.

## Limits of this preview

- A separate automated artwork-menu check was interrupted by changing foreground UI; it is not recorded as a pass. Actual photo import, every editing operation and opening every supplied title link have not been verified end to end on this TV.
- No Netflix integration is claimed. Each app must expose a usable feed; unavailable data or artwork falls back to the clock.
- The latest Home resolution snapshot reports Android's Home chooser, rather than a selected permanent launcher. No default-home command was executed during this preview. Both launchers remain installed.
- The initial development-preview snapshots matched the earlier cleanup. The utilities update was compared with a fresh pre-update snapshot, preserving subsequent user choices, including the now-enabled Play Store and installed Netflix app.
- One current memory sample was approximately 71.5 MiB total PSS for Luma Home while recommendations were visible. This is a running-state snapshot, not evidence of a speed improvement.

## Reversal

Press Home and choose the previous launcher to leave the preview. Before disabling Luma Home, select and verify another working default Home. Disabling rather than uninstalling retains the app and its preferences. Do not run a default-home or disable command without the user's separate approval.
