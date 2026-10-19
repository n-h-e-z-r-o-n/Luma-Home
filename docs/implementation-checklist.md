# Luma Home feature agreement

Implement and install for a real-TV preview, as requested. Preserve the existing TV services, animation and sleep settings. Do not remove any app. Any later extra device permission or package change must be explained before execution.

## Home and navigation

- Home is the landing page, populated with the user's chosen apps. Its app section is one horizontal row of circular app icons. Per the subsequent focus correction, small scrolling end insets and focus-aware reveal keep enlarged items inside the screen. Existing app selections are retained.
- Home and All Apps each form one full-screen vertically scrolling page: clock, heading, app section, recommendation content and footer move together. The earlier fixed home dock is superseded.
- All Apps preserves the baseline home layout and tile treatment, adding Search and Back to favorites. Padding is part of the scrolling content, with no inset clipping viewport or added frame around banner artwork.
- TV utilities such as Clock, Hotel Menu, first-time setup and device settings appear in a separate section below ordinary apps. Search includes both groups; the app menu can change a tile's group and Appearance can disable grouping.
- Remote-first navigation, clear focus, understandable Back behavior, persisted preferences.
- Add, move left/right, replace or remove a Home app without changing installed apps.
- App menu supports custom names, artwork and restoring the original tile.
- Optional recent app shortcuts, tracked locally only for apps launched through Luma Home; off by default. Show them as a separate wrapping block above the controls with matching column alignment, without a visible heading or horizontal carousel.

## Recommendations and header

- Recommendations off by default; per-app switch in hold-OK menu plus a master setting.
- Master switch hides content while retaining per-app choices. Show clear status if a feed is unavailable or access is restricted.
- Each app with recommendations enabled and a usable feed has its own persistent row on Home. Moving focus between Home icons does not hide or replace any row. The master switch and per-app switches remain authoritative.
- Recommendations settings offers a persistent card-size control: Small, Standard, Large and Extra large, with an in-dialog live preview and explicit Save/Cancel. The selected size sets artwork height and title text scale; width follows each image aspect ratio, bounded to the available screen. Home icon and All Apps sizes remain separate.
- Allow 72 reference pixels between recommendation sections, with a taller heading-to-artwork interval. The page grows vertically instead of compressing rows to fit.
- Down/Up moves between recommendation rows, the Home app row and footer; Left/Right stays within its row. Back from a recommendation returns to its source Home app where present.
- Horizontal rows extend to the physical screen edges. Rounded recommendation artwork uses a consistent selected height and natural image width without cropping, with its title beneath. The focus ring is drawn in the foreground above the image. Footer focus exposes bottom breathing room.
- Featured area is an edge-to-edge immersive carousel, not a floating rounded card. Up from Home expands it; Left/Right browses titles; Down/Back returns to Home. Artwork, supplied description, source, open action and pagination update together.
- Featured settings retain enable/source controls and add Automatic artwork-based height plus explicit heights 60/75/85%, optional title rotation, video enable and sound. Rotation defaults off; manual video previews default muted. Actual playback requires a playable shared URI; source-app preview-service integration is outside this build.
- Featured source selection is remembered when disabled.
- The hero action reads Play and uses the supplied title link. Browsing instructions are omitted; a quiet position count remains. Artwork scales proportionally to screen width with top alignment and a soft transparency fade into Home, avoiding a hard lower seam. Height changes reveal a different viewport rather than stretching the image.
- Without a selected source, enabled recommendations, accessible feed or usable artwork, show the clock/date. Never fabricate recommendations or imply Netflix integration from account presence.
- Content title actions use supplied links, with graceful failure handling. No autoplay or uncontrolled app scanning.

## Appearance

- Background options: solid colors, built-in gradients, custom still image.
- Import user images through a system/file picker where available; bounded decoding and local copies.
- Background dimming and reset controls.
- Tile size, clock size, high contrast and always-visible labels are configurable.
- Per the later visual correction, warm translucent menus and focus-only tile names are the defaults. Cool slate styling and always-visible names remain optional.
- Banner artwork keeps its rounded corners and focus lift; plain-icon tiles retain their icon and name inside the tile.

## Setup and recovery

- Explain how to use Home and retain a usable route to system settings.
- Provide honest setup/recovery guidance; do not claim an ordinary launcher can enable disabled system apps itself.
- Keep the earlier TV change-and-undo log outside the app project.

## Verification

- Build a signed APK with the required MIT notice included.
- Validate navigation, preference persistence, whole-page scrolling, editing, photo import fallback, no-feed banner fallback and footer spacing on the connected TV.
- Verify protected apps and TV inputs remain unchanged.
- Install and open Luma Home for preview under the user's explicit installation request. Do not uninstall the previous launcher.
