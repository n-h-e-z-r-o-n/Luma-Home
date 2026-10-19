# Luma Home

A personal Android TV home screen with warm translucent controls and a quiet first run.

## Home and apps

- Home is the landing page. Choose and arrange the apps you want there; every launchable app remains accessible in All Apps.
- Home apps form one horizontal scrolling row, with circular app icons by default. Small end insets and focus-aware scrolling keep enlarged items clear of the screen edges. All Apps retains its banner grid and separate utilities section.
- All Apps places TV utilities such as Clock and Hotel Menu in their own section below the regular apps. Search covers both sections.
- Hold OK and choose Section to change an app's group. Appearance can turn the separate utilities section off.
- Both Home and All Apps scroll as complete pages, including their header, app section and input/settings footer. Home's app and content rows scroll horizontally across the physical screen width.
- Reaching the footer reveals the space below its controls. No fixed middle scrolling area remains.
- Horizontal app and recommendation rows use the full screen width, so cards pass through the physical screen edge instead of an inset clipping boundary. Their scrolling content includes breathing room at the ends, and focus reveals extra room around the selected tile.
- Hold OK on an app to add it to Home, move or replace it, remove its shortcut, rename it, choose artwork, manage recommendations or select a featured source.
- Rounded banner tiles retain the simple focus lift. Plain icon tiles have their name inside. Banner names appear on focus by default.
- Optional recent app shortcuts appear in a separate wrapping block above the bottom controls, without a heading or horizontal carousel. Their columns align with those controls. History records only apps launched through Luma Home and can be cleared.

## Appearance

Settings → Appearance offers Warm glass, Cool slate or From wallpaper colors, tile sizes, clock sizes, high contrast and always-visible banner names.

From wallpaper extracts a prominent usable hue from a small wallpaper sample and derives restrained dark surface tints and pale focus accents. Color intensity ranges from 0% (neutral) to 100%; its dialog previews changes before Save, while Cancel preserves the current setting. Palette extraction runs off the UI thread and is cached for the imported image. Built-in colored backgrounds supply their own seed; missing or neutral imagery falls back to Warm glass. Text remains neutral/readable, high-contrast focus takes priority, and recommendation artwork stays black. This is translucent tinting, without live background blur.

Backgrounds include gradients, solid colors and an imported still photo. A dimming control keeps text readable. Images are resized and copied to private app storage; the picker supplies access without broad storage permission.

## Optional content

Recommendations are off initially. A master control pauses all content while remembering each app's preference. Individual app controls show availability honestly.

Settings → Recommendations → Card size offers Small, Standard, Large and Extra large. Left/Right previews the artwork and title size before saving. Save applies the size to every recommendation row; Cancel leaves the current size unchanged. The size selects artwork height; each card expands horizontally to match its image proportions. Very wide artwork is bounded to a screen-safe width. Artwork proportions and the focus-safe edges are retained at every size.

Every app with enabled recommendations and an available feed gets its own persistent row on Home. Rows stay visible as focus moves between icons; recommendation visibility is controlled by the saved switches, not the focused app. Rows follow Home app order first, then other enabled apps. Sections have generous vertical spacing rather than compressing content to fit the screen.

The featured area is an edge-to-edge immersive carousel with a user-selected source. Up from Home enters it; its default Automatic height follows the image proportions within a screen-safe range. Left/Right browses shared titles, the Play action follows the source app's supplied title link, and Down or Back returns to Home. Text sits over dark gradients without an outer card frame. The artwork scales uniformly to screen width, with a soft transparency fade into the user's Home background; viewport height never stretches the bitmap. It scrolls with the rest of the page.

Settings > Featured banner offers Automatic expanded height or explicit heights of 60%, 75% or 85%, optional 10-second title rotation, video previews and preview sound. Rotation is off initially and pauses while using the hero. Preview sound is off initially. A Watch preview action appears only for supplied HTTPS, content or Android resource video URIs. Down from Open reaches that action when available; Down again returns to Home. Playback is manual, stops on leaving the hero or pausing the app, and falls back to artwork on failure. Source-app-rendered preview services and unavailable/private trailer catalogs are not integrated.

Disabled content, missing data or failed initial artwork leave the clock visible. Selecting an app or having a subscription does not guarantee an accessible feed, backdrop-quality artwork or video. No accounts are collected and no trailers autoplay.

Recommendation artwork uses the selected height and a width derived from the image aspect ratio, with the title below. Images remain fully visible against pure black, including in the size preview. Focus draws a complete rounded ring above the artwork so the image cannot cover it. Black borders already included in source artwork remain part of that image.

TV-listings access is requested only when the user chooses its permission control. Some TVs still restrict access. Luma Home never attempts privileged permission grants or silently changes another app.

## Build

Requires Python 3.10+, a JDK, Android SDK build-tools and an SDK platform. Installed tools are discovered automatically.

~~~powershell
python build.py --debug
~~~

~~~sh
sh build.sh
~~~

Output: out/luma-home.apk. Application ID: com.lumahome.launcher.
Keep key.jks and .signing-password for future updates. Both are excluded from Git.
CI may supply KEYSTORE and KEYSTORE_PASS instead.

## Install and preview

Approve each TV operation first. Each invocation performs one explicit step:

~~~sh
LUMA_HOME_STEP=install sh install.sh <TV_IP>
LUMA_HOME_STEP=apps sh install.sh <TV_IP> "package.one,package.two"
LUMA_HOME_STEP=show sh install.sh <TV_IP>
~~~

The app-list argument chooses the apps on Home. All Apps still shows every launchable app.
You can also launch Luma Home directly to preview its initial favorites.

Selecting it as the default Home is a separate step:

~~~sh
LUMA_HOME_STEP=home sh install.sh <TV_IP>
~~~

This records the previous Home locally and never disables competing launchers.
TVs that override the choice need separately approved investigation.

Running undo.sh restores the recorded previous Home, verifies that restoration,
then disables Luma Home. It never uninstalls an app. Without a previous record it stops.

## Checks

The HomePolicyTest Java test exhaustively checks recommendation gates and clock fallback.
The smoke test runs only on a dedicated emulator and checks Home, All Apps and Back without changing the default.
Real-TV results and remaining limitations are recorded in docs/validation.md.

## License

MIT. The required notice is preserved in LICENSE and included in APK assets.

Home icons fill their circular frames using proportional center-cropping without inset padding. Transparent outer margins in native app icons are trimmed before fitting; All Apps artwork is unchanged.

`ThemePaletteTest` checks representative wallpaper hues, neutral/transparent-image fallback, neutral intensity, alpha preservation and contrast of generated text/accent colors against the base dark surfaces.

Clock and date use medium weight and the same foreground color. Recommendation section headings use the Home heading typography and show only the source app name.
