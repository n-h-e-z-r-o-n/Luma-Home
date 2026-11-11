"use client";

import { useState, useRef, useEffect, useMemo, useCallback } from "react";
import { soundEngine, SOUND_PACKS, type SoundPackId } from "../lib/soundEngine";
import { derivePalette, extractSeedFromImageData } from "../lib/themePalette";
import { encodeQrMatrix } from "../lib/qr";
import { site } from "../lib/site";

export type PresetKey = "forest" | "warm" | "cool" | "kyoto" | "custom";
export type IconShape = "circle" | "squircle" | "rounded";
export type IconFinish = "brand" | "glass" | "paper" | "monochrome";
export type HeaderMode = "quiet" | "cinema";
export type ActiveBlade = "none" | "all-apps" | "appearance" | "phone-drop";

interface PresetTheme {
  key: PresetKey;
  label: string;
  seedRgb: number;
  swatch: string;
}

const PRESETS: PresetTheme[] = [
  { key: "forest", label: "Forest Dusk", seedRgb: 0x91aa7b, swatch: "#91aa7b" },
  { key: "warm", label: "Desert Amber", seedRgb: 0xd2ac74, swatch: "#d2ac74" },
  { key: "cool", label: "Midnight Slate", seedRgb: 0x8ea4bd, swatch: "#8ea4bd" },
  { key: "kyoto", label: "Kyoto Rose", seedRgb: 0xc98b78, swatch: "#c98b78" },
];

interface AppItem {
  id: string;
  name: string;
  glyph: string;
  brandBg: string;
  brandFg: string;
  category: string;
  featured?: {
    eyebrow: string;
    title: string;
    meta: string;
    synopsis: string;
    backdropGradient: string;
  };
  recommendations?: Array<{
    title: string;
    subtitle: string;
    gradient: string;
    aspect: "wide" | "poster";
  }>;
}

const HOME_APPS: AppItem[] = [
  {
    id: "cinema",
    name: "Cinema",
    glyph: "▶",
    brandBg: "#c36f48",
    brandFg: "#fff6ee",
    category: "Streaming",
    featured: {
      eyebrow: "CINEMA · INVITED FEATURED PREVIEW",
      title: "After the Rain in Kyoto",
      meta: "2026 · 4K HDR10+ · Atmos · 1h 54m",
      synopsis:
        "An architect restores a centuries-old cedar teahouse as autumn mist settles across the northern hills.",
      backdropGradient:
        "radial-gradient(ellipse at 72% 28%, rgba(232,187,118,0.42) 0%, rgba(114,68,46,0.38) 45%, rgba(15,20,18,0.92) 85%)",
    },
    recommendations: [
      {
        title: "After the Rain in Kyoto",
        subtitle: "Continue · 42m left",
        gradient: "linear-gradient(135deg, #6e4534 0%, #2a3833 100%)",
        aspect: "wide",
      },
      {
        title: "Solstice on the Fjord",
        subtitle: "New in 4K HDR",
        gradient: "linear-gradient(135deg, #324b5e 0%, #1c2836 100%)",
        aspect: "wide",
      },
      {
        title: "The Glassmaker's Table",
        subtitle: "Documentary · 58m",
        gradient: "linear-gradient(135deg, #5e533e 0%, #27241d 100%)",
        aspect: "wide",
      },
    ],
  },
  {
    id: "music",
    name: "Hi-Fi Room",
    glyph: "♫",
    brandBg: "#d6b97a",
    brandFg: "#2f2414",
    category: "Audio",
    featured: {
      eyebrow: "HI-FI ROOM · NOW SPINNING",
      title: "Nocturnes for Felt Piano",
      meta: "Lossless 24-bit / 96kHz · Nils & Ensemble",
      synopsis:
        "Intimate close-mic acoustic recordings captured late at night in a timber concert hall in Oslo.",
      backdropGradient:
        "radial-gradient(ellipse at 68% 24%, rgba(214,185,122,0.45) 0%, rgba(72,62,42,0.42) 48%, rgba(15,18,16,0.94) 85%)",
    },
    recommendations: [
      {
        title: "Nocturnes for Felt Piano",
        subtitle: "Track 04 · 3:48",
        gradient: "linear-gradient(135deg, #78643e 0%, #2b251a 100%)",
        aspect: "wide",
      },
      {
        title: "Warm Analog Mornings",
        subtitle: "Curated Playlist",
        gradient: "linear-gradient(135deg, #526352 0%, #1e2820 100%)",
        aspect: "wide",
      },
      {
        title: "Tokyo Jazz Kissa",
        subtitle: "Live Session",
        gradient: "linear-gradient(135deg, #664642 0%, #261b1a 100%)",
        aspect: "wide",
      },
    ],
  },
  {
    id: "library",
    name: "Media Vault",
    glyph: "▤",
    brandBg: "#8aa79e",
    brandFg: "#142c24",
    category: "Local Server",
    featured: {
      eyebrow: "MEDIA VAULT · DIRECT PLAY",
      title: "Architecture of Quiet Spaces",
      meta: "Local NAS · 4K Remux · Zero Buffering",
      synopsis:
        "Streamed directly from your home network with custom banner artwork and zero external tracking.",
      backdropGradient:
        "radial-gradient(ellipse at 70% 25%, rgba(138,167,158,0.44) 0%, rgba(44,74,66,0.42) 48%, rgba(14,20,19,0.94) 85%)",
    },
    recommendations: [
      {
        title: "Architecture of Quiet Spaces",
        subtitle: "Season 1 · Episode 3",
        gradient: "linear-gradient(135deg, #3c5f56 0%, #1b2c28 100%)",
        aspect: "wide",
      },
      {
        title: "Alpine Railway 1974",
        subtitle: "Restored 35mm",
        gradient: "linear-gradient(135deg, #4b5d6b 0%, #202830 100%)",
        aspect: "wide",
      },
      {
        title: "Home Videos · Summer",
        subtitle: "Personal Archive",
        gradient: "linear-gradient(135deg, #6e634b 0%, #29251c 100%)",
        aspect: "wide",
      },
    ],
  },
  {
    id: "luma",
    name: "Luma Studio",
    glyph: "⌂",
    brandBg: "#161d22",
    brandFg: "#e8bb76",
    category: "Personalization",
    featured: {
      eyebrow: "LUMA STUDIO · WALLPAPER ENGINE",
      title: "Your TV, Tinted by Your Art",
      meta: "On-Device HSV Extraction · 0–100% Intensity",
      synopsis:
        "Drop any photo from your phone over local Wi-Fi. Luma extracts dominant hues to softly tint menus and focus rings.",
      backdropGradient:
        "radial-gradient(ellipse at 70% 25%, rgba(145,170,123,0.42) 0%, rgba(45,68,54,0.42) 48%, rgba(14,19,17,0.94) 85%)",
    },
  },
  {
    id: "arcade",
    name: "Couch Co-Op",
    glyph: "✦",
    brandBg: "#9986a6",
    brandFg: "#f5edf8",
    category: "Gaming",
    featured: {
      eyebrow: "COUCH CO-OP · CONTROLLER READY",
      title: "Starlight Cartographer",
      meta: "120Hz Game Mode · 2 Controllers Connected",
      synopsis:
        "Chart constellations across a hand-painted sky with zero launcher background overhead.",
      backdropGradient:
        "radial-gradient(ellipse at 74% 24%, rgba(153,134,166,0.45) 0%, rgba(62,48,78,0.45) 48%, rgba(15,14,22,0.94) 85%)",
    },
  },
];

const ALL_APPS_EXTRA = [
  { name: "Live Channels", glyph: "📡", group: "apps" },
  { name: "Art Gallery", glyph: "🖼", group: "apps" },
  { name: "Podcasts", glyph: "🎙", group: "apps" },
  { name: "Web Browser", glyph: "🌐", group: "apps" },
  { name: "HDMI 1 · Blu-ray", glyph: "▱", group: "utilities" },
  { name: "HDMI 2 · Console", glyph: "▱", group: "utilities" },
  { name: "Sleep Timer", glyph: "☾", group: "utilities" },
  { name: "System Settings", glyph: "⚙", group: "utilities" },
];

export default function HomePreview({
  externalPresetRequest,
}: {
  externalPresetRequest?: {
    preset?: PresetKey;
    headerMode?: HeaderMode;
    blade?: ActiveBlade;
    seq: number;
  } | null;
}) {
  const [preset, setPreset] = useState<PresetKey>("forest");
  const [customImageUrl, setCustomImageUrl] = useState<string | null>(null);
  const [customSeedRgb, setCustomSeedRgb] = useState<number>(0x91aa7b);
  const [intensity, setIntensity] = useState<number>(62);
  const [iconShape, setIconShape] = useState<IconShape>("circle");
  const [iconFinish, setIconFinish] = useState<IconFinish>("brand");
  const [headerMode, setHeaderMode] = useState<HeaderMode>("quiet");
  const [showRecRows, setShowRecRows] = useState<boolean>(false);

  // Navigation focus state
  // focusRow: 0 = Header/Featured, 1 = Home Apps row, 2 = Recs row (when active), 3 = Bottom Controls
  const [focusRow, setFocusRow] = useState<number>(1);
  const [appIndex, setAppIndex] = useState<number>(0);
  const [recIndex, setRecIndex] = useState<number>(0);
  const [controlIndex, setControlIndex] = useState<number>(0); // 0=All Apps, 1=Wallpaper Drop, 2=Customize, 3=Cinema Toggle
  const [activeBlade, setActiveBlade] = useState<ActiveBlade>("none");
  const [bladeItemIndex, setBladeItemIndex] = useState<number>(0);

  // Sound state
  const [soundEnabled, setSoundEnabled] = useState<boolean>(true);
  const [soundPack, setSoundPack] = useState<SoundPackId>("wood");

  // Toast inside TV
  const [tvToast, setTvToast] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const tvContainerRef = useRef<HTMLDivElement | null>(null);

  const showToast = useCallback((msg: string) => {
    setTvToast(msg);
  }, []);

  useEffect(() => {
    if (!tvToast) return;
    const t = setTimeout(() => setTvToast(null), 2600);
    return () => clearTimeout(t);
  }, [tvToast]);

  // Sync with external feature card clicks
  useEffect(() => {
    if (!externalPresetRequest) return;
    if (externalPresetRequest.preset) setPreset(externalPresetRequest.preset);
    if (externalPresetRequest.headerMode) {
      setHeaderMode(externalPresetRequest.headerMode);
      setShowRecRows(externalPresetRequest.headerMode === "cinema");
    }
    if (externalPresetRequest.blade !== undefined) {
      setActiveBlade(externalPresetRequest.blade);
    }
  }, [externalPresetRequest]);

  const activeSeedRgb = useMemo(() => {
    if (preset === "custom") return customSeedRgb;
    return PRESETS.find((p) => p.key === preset)?.seedRgb ?? 0x91aa7b;
  }, [preset, customSeedRgb]);

  const palette = useMemo(
    () => derivePalette(activeSeedRgb, intensity),
    [activeSeedRgb, intensity]
  );

  const focusedApp = HOME_APPS[appIndex] || HOME_APPS[0];
  const activeRecommendations =
    focusedApp.recommendations || HOME_APPS[0].recommendations!;

  const qrMatrix = useMemo(() => encodeQrMatrix(site.playStoreUrl), []);

  // Handle custom wallpaper upload + ThemePalette HSV extraction
  const handleFileUpload = (file: File | undefined) => {
    if (!file) return;
    const reader = new FileReader();
    reader.onload = () => {
      const dataUrl = reader.result as string;
      const img = new Image();
      img.onload = () => {
        const canvas = document.createElement("canvas");
        canvas.width = 64;
        canvas.height = 36;
        const ctx = canvas.getContext("2d");
        if (ctx) {
          ctx.drawImage(img, 0, 0, 64, 36);
          const imgData = ctx.getImageData(0, 0, 64, 36).data;
          const seed = extractSeedFromImageData(imgData);
          setCustomSeedRgb(seed);
        }
        setCustomImageUrl(dataUrl);
        setPreset("custom");
        setActiveBlade("none");
        soundEngine.playSelect(soundPack, soundEnabled, 0.5);
        showToast("Custom photo applied · Palette extracted on-device");
      };
      img.src = dataUrl;
    };
    reader.readAsDataURL(file);
  };

  // D-Pad navigation handler
  const navigate = useCallback(
    (dir: "left" | "right" | "up" | "down" | "ok" | "back" | "home") => {
      if (dir === "home") {
        setActiveBlade("none");
        setFocusRow(1);
        soundEngine.playBack(soundPack, soundEnabled);
        return;
      }

      if (dir === "back") {
        if (activeBlade !== "none") {
          setActiveBlade("none");
          soundEngine.playBack(soundPack, soundEnabled);
          return;
        }
        if (focusRow !== 1) {
          setFocusRow(1);
          soundEngine.playBack(soundPack, soundEnabled);
          return;
        }
        soundEngine.playBack(soundPack, soundEnabled);
        return;
      }

      // If a blade is open, navigate inside the blade
      if (activeBlade !== "none") {
        if (dir === "left" || dir === "up") {
          setBladeItemIndex((prev) => Math.max(0, prev - 1));
          soundEngine.playFocus(soundPack, soundEnabled, 0.75, -1);
        } else if (dir === "right" || dir === "down") {
          setBladeItemIndex((prev) => Math.min(7, prev + 1));
          soundEngine.playFocus(soundPack, soundEnabled, 0.75, 1);
        } else if (dir === "ok") {
          soundEngine.playSelect(soundPack, soundEnabled, 0.75);
          if (activeBlade === "appearance") {
            const shapes: IconShape[] = ["circle", "squircle", "rounded"];
            setIconShape(shapes[(shapes.indexOf(iconShape) + 1) % shapes.length]);
            showToast("Updated icon geometry");
          } else if (activeBlade === "phone-drop") {
            fileInputRef.current?.click();
          } else {
            setActiveBlade("none");
            showToast("Launched selected shortcut");
          }
        }
        return;
      }

      // Normal Home Screen D-Pad Navigation
      if (dir === "left") {
        if (focusRow === 1) {
          const next = Math.max(0, appIndex - 1);
          setAppIndex(next);
          soundEngine.playFocus(soundPack, soundEnabled, next / (HOME_APPS.length - 1), -1);
        } else if (focusRow === 2) {
          const next = Math.max(0, recIndex - 1);
          setRecIndex(next);
          soundEngine.playFocus(soundPack, soundEnabled, next / 2, -1);
        } else if (focusRow === 3) {
          const next = Math.max(0, controlIndex - 1);
          setControlIndex(next);
          soundEngine.playFocus(soundPack, soundEnabled, next / 3, -1);
        } else if (focusRow === 0) {
          const next = (appIndex - 1 + HOME_APPS.length) % HOME_APPS.length;
          setAppIndex(next);
          soundEngine.playFocus(soundPack, soundEnabled, 0.35, -1);
        }
      } else if (dir === "right") {
        if (focusRow === 1) {
          const next = Math.min(HOME_APPS.length - 1, appIndex + 1);
          setAppIndex(next);
          soundEngine.playFocus(soundPack, soundEnabled, next / (HOME_APPS.length - 1), 1);
        } else if (focusRow === 2) {
          const next = Math.min(activeRecommendations.length - 1, recIndex + 1);
          setRecIndex(next);
          soundEngine.playFocus(soundPack, soundEnabled, next / 2, 1);
        } else if (focusRow === 3) {
          const next = Math.min(3, controlIndex + 1);
          setControlIndex(next);
          soundEngine.playFocus(soundPack, soundEnabled, next / 3, 1);
        } else if (focusRow === 0) {
          const next = (appIndex + 1) % HOME_APPS.length;
          setAppIndex(next);
          soundEngine.playFocus(soundPack, soundEnabled, 0.65, 1);
        }
      } else if (dir === "up") {
        if (focusRow === 3) {
          const nextRow = showRecRows ? 2 : 1;
          setFocusRow(nextRow);
          soundEngine.playFocus(soundPack, soundEnabled, 0.5, 1);
        } else if (focusRow === 2) {
          setFocusRow(1);
          soundEngine.playFocus(soundPack, soundEnabled, appIndex / (HOME_APPS.length - 1), 1);
        } else if (focusRow === 1) {
          setFocusRow(0);
          setHeaderMode("cinema");
          soundEngine.playFocus(soundPack, soundEnabled, 0.5, 1);
        }
      } else if (dir === "down") {
        if (focusRow === 0) {
          setFocusRow(1);
          soundEngine.playFocus(soundPack, soundEnabled, appIndex / (HOME_APPS.length - 1), -1);
        } else if (focusRow === 1) {
          const nextRow = showRecRows ? 2 : 3;
          setFocusRow(nextRow);
          soundEngine.playFocus(soundPack, soundEnabled, 0.5, -1);
        } else if (focusRow === 2) {
          setFocusRow(3);
          soundEngine.playFocus(soundPack, soundEnabled, controlIndex / 3, -1);
        }
      } else if (dir === "ok") {
        if (focusRow === 0) {
          soundEngine.playSelect(soundPack, soundEnabled, 0.4);
          showToast(`Playing "${focusedApp.featured?.title}" in ${focusedApp.name}`);
        } else if (focusRow === 1) {
          const app = HOME_APPS[appIndex];
          soundEngine.playSelect(soundPack, soundEnabled, appIndex / (HOME_APPS.length - 1));
          if (app.id === "luma") {
            setActiveBlade("appearance");
            soundEngine.playBlade(soundPack, soundEnabled);
          } else {
            showToast(`Opening ${app.name}…`);
          }
        } else if (focusRow === 2) {
          const rec = activeRecommendations[recIndex];
          soundEngine.playSelect(soundPack, soundEnabled, recIndex / 2);
          if (rec) showToast(`Resuming "${rec.title}"`);
        } else if (focusRow === 3) {
          if (controlIndex === 0) {
            setActiveBlade("all-apps");
            setBladeItemIndex(0);
            soundEngine.playBlade(soundPack, soundEnabled);
          } else if (controlIndex === 1) {
            setActiveBlade("phone-drop");
            setBladeItemIndex(0);
            soundEngine.playBlade(soundPack, soundEnabled);
          } else if (controlIndex === 2) {
            setActiveBlade("appearance");
            setBladeItemIndex(0);
            soundEngine.playBlade(soundPack, soundEnabled);
          } else if (controlIndex === 3) {
            const nextMode = headerMode === "quiet" ? "cinema" : "quiet";
            setHeaderMode(nextMode);
            setShowRecRows(nextMode === "cinema");
            soundEngine.playSelect(soundPack, soundEnabled, 0.75);
            showToast(
              nextMode === "cinema"
                ? "Invited recommendations & cinema header enabled"
                : "Quiet clock & weather restored"
            );
          }
        }
      }
    },
    [
      activeBlade,
      focusRow,
      appIndex,
      recIndex,
      controlIndex,
      showRecRows,
      headerMode,
      iconShape,
      soundPack,
      soundEnabled,
      focusedApp,
      activeRecommendations,
      showToast,
    ]
  );

  // Keyboard navigation when TV simulator is focused or hovered
  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (["ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown", "Enter", "Backspace", "Escape"].includes(e.key)) {
      e.preventDefault();
      if (e.key === "ArrowLeft") navigate("left");
      else if (e.key === "ArrowRight") navigate("right");
      else if (e.key === "ArrowUp") navigate("up");
      else if (e.key === "ArrowDown") navigate("down");
      else if (e.key === "Enter") navigate("ok");
      else if (e.key === "Backspace" || e.key === "Escape") navigate("back");
    }
  };

  return (
    <div className="preview-section" id="simulator">
      <input
        ref={fileInputRef}
        type="file"
        accept="image/*"
        hidden
        onChange={(e) => handleFileUpload(e.target.files?.[0])}
      />

      {/* Top Interactive Status Bar above the TV */}
      <div className="sim-toolbar">
        <div className="sim-toolbar-left">
          <span className="sim-live-pill">
            <span className="sim-live-dot" /> INTERACTIVE TV SIMULATOR
          </span>
          <span className="sim-hint-text">
            Click inside the TV, use the remote, or press <kbd>←</kbd> <kbd>→</kbd> <kbd>↑</kbd> <kbd>↓</kbd> <kbd>Enter</kbd>
          </span>
        </div>

        <div className="sim-toolbar-right">
          <button
            type="button"
            className={`sim-Chip ${soundEnabled ? "on" : ""}`}
            onClick={() => {
              const next = !soundEnabled;
              setSoundEnabled(next);
              if (next) soundEngine.playSelect(soundPack, true, 0.5);
            }}
            aria-pressed={soundEnabled}
            title="Toggle Luma's synthesized UI micro-sounds"
          >
            <span aria-hidden="true">{soundEnabled ? "🔊" : "🔇"}</span>
            <span>{soundEnabled ? `Haptics: ${SOUND_PACKS.find((s) => s.id === soundPack)?.label}` : "Sound Muted"}</span>
          </button>

          <button
            type="button"
            className="sim-Chip upload-chip"
            onClick={() => fileInputRef.current?.click()}
          >
            <span aria-hidden="true">🖼</span>
            <span>Try Your Own Wallpaper</span>
          </button>
        </div>
      </div>

      {/* Main Stage: TV Frame + Tactile Remote Side-by-Side on Desktop */}
      <div className="sim-stage">
        {/* Ambient Living-Room Wall Backlight (Ambilight) */}
        <div
          className="tv-ambilight"
          aria-hidden="true"
          style={{
            background: `radial-gradient(ellipse at 50% 48%, ${palette.glowRgba} 0%, transparent 70%)`,
          }}
        />

        <div
          ref={tvContainerRef}
          tabIndex={0}
          role="region"
          aria-label="Interactive Luma Home Android TV Preview. Use arrow keys and Enter to navigate."
          onKeyDown={handleKeyDown}
          className={`tv-shell shape-${iconShape} finish-${iconFinish}`}
          style={
            {
              "--scene": palette.sceneTopHex,
              "--scene-light": palette.accentHex,
              "--scene-mid": palette.sceneMidHex,
              "--scene-near": palette.sceneNearHex,
              "--tint": palette.accentHex,
              "--surface": palette.surfaceRgba,
              "--muted-tone": palette.mutedHex,
            } as React.CSSProperties
          }
        >
          {/* Wallpaper / Scene Background */}
          <div className="tv-scene" aria-hidden="true">
            {preset === "custom" && customImageUrl ? (
              <div
                className="tv-custom-wallpaper"
                style={{ backgroundImage: `url(${customImageUrl})` }}
              />
            ) : (
              <>
                <div className="landscape-back" />
                <div className="landscape-front" />
                <div className="sun" />
              </>
            )}
            {headerMode === "cinema" && focusedApp.featured && (
              <div
                className="tv-cinema-backdrop"
                style={{ background: focusedApp.featured.backdropGradient }}
              />
            )}
          </div>

          {/* TV User Interface */}
          <div className="tv-ui">
            {/* Top Status Row: Date + Weather Widget */}
            <div className="tv-top-bar">
              <div className="tv-date">Monday, 19 October</div>
              <button
                type="button"
                className="tv-weather-pill"
                onClick={() => {
                  const nextMode = headerMode === "quiet" ? "cinema" : "quiet";
                  setHeaderMode(nextMode);
                  setShowRecRows(nextMode === "cinema");
                  soundEngine.playSelect(soundPack, soundEnabled, 0.85);
                }}
                title="Click to toggle Quiet Clock vs. Invited Featured Cinema"
              >
                <span aria-hidden="true">☀️</span>
                <span>18°C · Clear Sky</span>
                <span className="tv-mode-tag">
                  {headerMode === "quiet" ? "Quiet Mode" : "Cinema Mode"}
                </span>
              </button>
            </div>

            {/* Header Area: Quiet Clock OR Invited Featured Cinema */}
            {headerMode === "quiet" ? (
              <div
                className={`tv-quiet-header ${focusRow === 0 ? "focused" : ""}`}
                onClick={() => {
                  setFocusRow(0);
                  setHeaderMode("cinema");
                  soundEngine.playSelect(soundPack, soundEnabled, 0.3);
                }}
              >
                <div className="tv-time">20:48</div>
                <div className="tv-quiet-sub">
                  Press <kbd>↑</kbd> or click to expand invited Featured Cinema
                </div>
              </div>
            ) : (
              <div
                className={`tv-featured-banner ${focusRow === 0 ? "focused" : ""}`}
                onClick={() => {
                  setFocusRow(0);
                  soundEngine.playSelect(soundPack, soundEnabled, 0.4);
                  showToast(`Playing "${focusedApp.featured?.title}"`);
                }}
              >
                <div className="tv-featured-eyebrow">
                  {focusedApp.featured?.eyebrow}
                </div>
                <div className="tv-featured-title">
                  {focusedApp.featured?.title}
                </div>
                <div className="tv-featured-meta">
                  {focusedApp.featured?.meta}
                </div>
                <p className="tv-featured-desc">
                  {focusedApp.featured?.synopsis}
                </p>
                <div className="tv-featured-actions">
                  <span className="tv-play-pill">
                    ▶ Play in {focusedApp.name}
                  </span>
                  <button
                    type="button"
                    className="tv-quiet-return"
                    onClick={(e) => {
                      e.stopPropagation();
                      setHeaderMode("quiet");
                      setShowRecRows(false);
                      setFocusRow(1);
                      soundEngine.playBack(soundPack, soundEnabled);
                    }}
                  >
                    Return to Quiet Clock
                  </button>
                </div>
              </div>
            )}

            {/* Home Apps Row */}
            <div className="tv-home-section">
              <div className="tv-section-head">
                <div className="tv-home-heading">Home</div>
                <span className="tv-app-category">{focusedApp.category}</span>
              </div>

              <div className="tv-apps" role="menubar" aria-label="Favorite TV Apps">
                {HOME_APPS.map((app, idx) => {
                  const isFocused = focusRow === 1 && appIndex === idx;
                  const isSelectedApp = appIndex === idx;
                  return (
                    <button
                      key={app.id}
                      type="button"
                      role="menuitem"
                      aria-label={`${app.name} (${app.category})`}
                      className={`mock-app ${isFocused ? "active" : ""} ${
                        isSelectedApp && focusRow !== 1 ? "selected-context" : ""
                      }`}
                      style={
                        {
                          "--app-bg": app.brandBg,
                          "--app-fg": app.brandFg,
                        } as React.CSSProperties
                      }
                      onMouseEnter={() => {
                        if (activeBlade === "none") {
                          setFocusRow(1);
                          if (appIndex !== idx) {
                            setAppIndex(idx);
                            soundEngine.playFocus(
                              soundPack,
                              soundEnabled,
                              idx / (HOME_APPS.length - 1),
                              idx > appIndex ? 1 : -1
                            );
                          }
                        }
                      }}
                      onClick={() => {
                        setFocusRow(1);
                        setAppIndex(idx);
                        soundEngine.playSelect(
                          soundPack,
                          soundEnabled,
                          idx / (HOME_APPS.length - 1)
                        );
                        if (app.id === "luma") {
                          setActiveBlade("appearance");
                          soundEngine.playBlade(soundPack, soundEnabled);
                        } else {
                          showToast(`Launched ${app.name}`);
                        }
                      }}
                    >
                      {app.id === "luma" ? (
                        <img src="/luma-icon.png" width="62" height="62" alt="" />
                      ) : (
                        <span>{app.glyph}</span>
                      )}
                      <small>{app.name}</small>
                    </button>
                  );
                })}
              </div>
            </div>

            {/* Optional Invited Recommendation Row */}
            {showRecRows && (
              <div className="tv-recs-section">
                <div className="tv-recs-heading">
                  {focusedApp.name} · Invited Channel
                </div>
                <div className="tv-recs-row">
                  {activeRecommendations.map((rec, rIdx) => {
                    const isFocused = focusRow === 2 && recIndex === rIdx;
                    return (
                      <button
                        key={rec.title}
                        type="button"
                        className={`tv-rec-card ${isFocused ? "focused" : ""}`}
                        style={{ background: rec.gradient }}
                        onMouseEnter={() => {
                          if (activeBlade === "none") {
                            setFocusRow(2);
                            setRecIndex(rIdx);
                            soundEngine.playFocus(soundPack, soundEnabled, rIdx / 2, 1);
                          }
                        }}
                        onClick={() => {
                          setFocusRow(2);
                          setRecIndex(rIdx);
                          soundEngine.playSelect(soundPack, soundEnabled, rIdx / 2);
                          showToast(`Resuming "${rec.title}"`);
                        }}
                      >
                        <span className="tv-rec-title">{rec.title}</span>
                        <span className="tv-rec-sub">{rec.subtitle}</span>
                      </button>
                    );
                  })}
                </div>
              </div>
            )}

            {/* Bottom Controls Dock */}
            <div className="mock-controls" role="toolbar" aria-label="Launcher Controls">
              {[
                { label: "All Apps", icon: "▦", blade: "all-apps" as ActiveBlade },
                { label: "Phone Drop", icon: "📱", blade: "phone-drop" as ActiveBlade },
                { label: "Appearance", icon: "☼", blade: "appearance" as ActiveBlade },
                {
                  label: showRecRows ? "Hide Channels" : "Invite Channels",
                  icon: "◷",
                  blade: "none" as ActiveBlade,
                },
              ].map((ctrl, cIdx) => {
                const isFocused = focusRow === 3 && controlIndex === cIdx;
                const isOpen =
                  ctrl.blade !== "none" && activeBlade === ctrl.blade;
                return (
                  <button
                    key={ctrl.label}
                    type="button"
                    className={`tv-dock-btn ${isFocused ? "focused" : ""} ${
                      isOpen ? "open" : ""
                    }`}
                    onMouseEnter={() => {
                      if (activeBlade === "none") {
                        setFocusRow(3);
                        setControlIndex(cIdx);
                        soundEngine.playFocus(soundPack, soundEnabled, cIdx / 3, 1);
                      }
                    }}
                    onClick={() => {
                      setFocusRow(3);
                      setControlIndex(cIdx);
                      if (cIdx === 3) {
                        const next = !showRecRows;
                        setShowRecRows(next);
                        setHeaderMode(next ? "cinema" : "quiet");
                        soundEngine.playSelect(soundPack, soundEnabled, 0.85);
                      } else {
                        setActiveBlade(
                          activeBlade === ctrl.blade ? "none" : ctrl.blade
                        );
                        soundEngine.playBlade(soundPack, soundEnabled);
                      }
                    }}
                  >
                    <span aria-hidden="true">{ctrl.icon}</span>
                    <i>{ctrl.label}</i>
                  </button>
                );
              })}
            </div>

            {/* Interactive Side Blades (All Apps / Phone Wallpaper Drop / Appearance) */}
            {activeBlade !== "none" && (
              <div
                className="tv-blade-overlay"
                onClick={() => {
                  setActiveBlade("none");
                  soundEngine.playBack(soundPack, soundEnabled);
                }}
              >
                <aside
                  className="tv-blade"
                  onClick={(e) => e.stopPropagation()}
                  aria-label="TV Side Drawer"
                >
                  <div className="tv-blade-header">
                    <div>
                      <span className="tv-blade-eyebrow">
                        {activeBlade === "appearance"
                          ? "APPEARANCE & AUDIO"
                          : activeBlade === "phone-drop"
                          ? "LOCAL WI-FI · ZERO LOGIN"
                          : "INSTALLED & TV UTILITIES"}
                      </span>
                      <h4>
                        {activeBlade === "appearance"
                          ? "Customize Luma"
                          : activeBlade === "phone-drop"
                          ? "QR Wallpaper Drop"
                          : "All Apps"}
                      </h4>
                    </div>
                    <button
                      type="button"
                      className="tv-blade-close"
                      onClick={() => {
                        setActiveBlade("none");
                        soundEngine.playBack(soundPack, soundEnabled);
                      }}
                    >
                      Back ↩
                    </button>
                  </div>

                  {activeBlade === "appearance" && (
                    <div className="tv-blade-body">
                      <div className="blade-group">
                        <div className="blade-label">
                          <span>Wallpaper Color Intensity</span>
                          <strong>{intensity}%</strong>
                        </div>
                        <input
                          type="range"
                          min={0}
                          max={100}
                          value={intensity}
                          onChange={(e) => {
                            const v = Number(e.target.value);
                            setIntensity(v);
                            soundEngine.playDetent(soundPack, soundEnabled, v);
                          }}
                          className="tv-slider"
                        />
                      </div>

                      <div className="blade-group">
                        <div className="blade-label">
                          <span>Icon Shape</span>
                        </div>
                        <div className="blade-pills">
                          {(["circle", "squircle", "rounded"] as IconShape[]).map(
                            (sh) => (
                              <button
                                key={sh}
                                type="button"
                                className={`blade-pill ${
                                  iconShape === sh ? "on" : ""
                                }`}
                                onClick={() => {
                                  setIconShape(sh);
                                  soundEngine.playSelect(soundPack, soundEnabled, 0.75);
                                }}
                              >
                                {sh.charAt(0).toUpperCase() + sh.slice(1)}
                              </button>
                            )
                          )}
                        </div>
                      </div>

                      <div className="blade-group">
                        <div className="blade-label">
                          <span>Icon Material Finish</span>
                        </div>
                        <div className="blade-pills">
                          {(
                            [
                              { id: "brand", label: "Brand" },
                              { id: "glass", label: "Translucent Glass" },
                              { id: "paper", label: "Warm Paper" },
                              { id: "monochrome", label: "Monochrome" },
                            ] as Array<{ id: IconFinish; label: string }>
                          ).map((fin) => (
                            <button
                              key={fin.id}
                              type="button"
                              className={`blade-pill ${
                                iconFinish === fin.id ? "on" : ""
                              }`}
                              onClick={() => {
                                setIconFinish(fin.id);
                                soundEngine.playSelect(soundPack, soundEnabled, 0.75);
                              }}
                            >
                              {fin.label}
                            </button>
                          ))}
                        </div>
                      </div>

                      <div className="blade-group">
                        <div className="blade-label">
                          <span>Acoustic Micro-Sounds ("Haptics-for-Ears")</span>
                        </div>
                        <div className="blade-pills">
                          {SOUND_PACKS.map((pk) => (
                            <button
                              key={pk.id}
                              type="button"
                              className={`blade-pill ${
                                soundPack === pk.id && soundEnabled ? "on" : ""
                              }`}
                              onClick={() => {
                                setSoundEnabled(true);
                                setSoundPack(pk.id);
                                soundEngine.playSelect(pk.id, true, 0.75);
                              }}
                            >
                              {pk.label}
                            </button>
                          ))}
                        </div>
                      </div>
                    </div>
                  )}

                  {activeBlade === "phone-drop" && (
                    <div className="tv-blade-body phone-drop-body">
                      <div className="blade-qr-row">
                        <div className="blade-qr-box">
                          <svg
                            viewBox={`0 0 ${qrMatrix.length + 4} ${
                              qrMatrix.length + 4
                            }`}
                            shapeRendering="crispEdges"
                          >
                            <rect
                              width={qrMatrix.length + 4}
                              height={qrMatrix.length + 4}
                              fill="#f0eee5"
                            />
                            {qrMatrix.map((row, r) =>
                              row.map((dark, c) =>
                                dark ? (
                                  <rect
                                    key={`${r}-${c}`}
                                    x={c + 2}
                                    y={r + 2}
                                    width={1.04}
                                    height={1.04}
                                    fill="#111310"
                                  />
                                ) : null
                              )
                            )}
                          </svg>
                        </div>
                        <div className="blade-qr-copy">
                          <strong>Zero-Login Local Wi-Fi Drop</strong>
                          <p>
                            On your real TV, scanning this QR opens a local page hosted directly by your TV (`http://192.168.1.42:8484`). No cloud uploads or accounts.
                          </p>
                          <button
                            type="button"
                            className="blade-upload-btn"
                            onClick={() => fileInputRef.current?.click()}
                          >
                            🖼 Test with a Photo from Your Device
                          </button>
                        </div>
                      </div>
                    </div>
                  )}

                  {activeBlade === "all-apps" && (
                    <div className="tv-blade-body">
                      <div className="blade-label">
                        <span>Apps & Separated TV Utilities</span>
                      </div>
                      <div className="blade-apps-grid">
                        {ALL_APPS_EXTRA.map((item, idx) => (
                          <button
                            key={item.name}
                            type="button"
                            className={`blade-app-tile ${
                              bladeItemIndex === idx ? "focused" : ""
                            }`}
                            onClick={() => {
                              setBladeItemIndex(idx);
                              soundEngine.playSelect(soundPack, soundEnabled, 0.75);
                              showToast(`Opened ${item.name}`);
                            }}
                          >
                            <span className="blade-app-icon">{item.glyph}</span>
                            <span className="blade-app-name">{item.name}</span>
                            <small className="blade-app-tag">
                              {item.group === "utilities" ? "Utility" : "App"}
                            </small>
                          </button>
                        ))}
                      </div>
                    </div>
                  )}
                </aside>
              </div>
            )}

            {/* Toast Notification inside TV */}
            {tvToast && (
              <div className="tv-toast" role="status" aria-live="polite">
                <span className="tv-toast-dot" />
                {tvToast}
              </div>
            )}
          </div>
          <span className="tv-led" aria-hidden="true" />
        </div>

        {/* Tactile Virtual TV Remote Control */}
        <aside className="tactile-remote" aria-label="Virtual Android TV Remote">
          <div className="remote-top">
            <span className="remote-mic-dot" />
            <span className="remote-brand">LUMA REMOTE</span>
          </div>

          {/* Circular D-Pad */}
          <div className="remote-dpad" role="group" aria-label="Directional Pad">
            <button
              type="button"
              className="dpad-btn up"
              aria-label="Navigate Up"
              onClick={() => navigate("up")}
            >
              ▲
            </button>
            <button
              type="button"
              className="dpad-btn left"
              aria-label="Navigate Left"
              onClick={() => navigate("left")}
            >
              ◀
            </button>
            <button
              type="button"
              className="dpad-ok"
              aria-label="Select (OK)"
              onClick={() => navigate("ok")}
            >
              OK
            </button>
            <button
              type="button"
              className="dpad-btn right"
              aria-label="Navigate Right"
              onClick={() => navigate("right")}
            >
              ▶
            </button>
            <button
              type="button"
              className="dpad-btn down"
              aria-label="Navigate Down"
              onClick={() => navigate("down")}
            >
              ▼
            </button>
          </div>

          {/* Action Row: Back & Home */}
          <div className="remote-nav-row">
            <button
              type="button"
              className="remote-pill-btn"
              onClick={() => navigate("back")}
              title="Back"
            >
              ↩ <span>Back</span>
            </button>
            <button
              type="button"
              className="remote-pill-btn"
              onClick={() => navigate("home")}
              title="Home"
            >
              ⌂ <span>Home</span>
            </button>
          </div>

          {/* Quick Studio Shortcut Buttons */}
          <div className="remote-quick-grid">
            <button
              type="button"
              className={`remote-quick-btn ${
                activeBlade === "appearance" ? "active" : ""
              }`}
              onClick={() => {
                setActiveBlade(
                  activeBlade === "appearance" ? "none" : "appearance"
                );
                soundEngine.playBlade(soundPack, soundEnabled);
              }}
            >
              ☼ Style
            </button>
            <button
              type="button"
              className={`remote-quick-btn ${
                headerMode === "cinema" ? "active" : ""
              }`}
              onClick={() => {
                const next = headerMode === "quiet" ? "cinema" : "quiet";
                setHeaderMode(next);
                setShowRecRows(next === "cinema");
                soundEngine.playSelect(soundPack, soundEnabled, 0.5);
              }}
            >
              ◷ {headerMode === "cinema" ? "Cinema" : "Quiet"}
            </button>
          </div>
        </aside>
      </div>

      {/* Bottom Studio Control Bar: Atmosphere Presets + Intensity + Icon Shape */}
      <div className="preview-caption">
        <div className="caption-left">
          <span className="caption-title">One home. Your atmosphere.</span>
          <span className="caption-seed-badge">
            <span
              className="seed-dot"
              style={{ background: palette.accentHex }}
            />
            Extracted Accent <code>{palette.accentHex.toUpperCase()}</code> ·{" "}
            {intensity}% Intensity
          </span>
        </div>

        <div
          className="palette-picker"
          role="group"
          aria-label="Preview a color palette"
        >
          {PRESETS.map((item) => (
            <button
              key={item.key}
              type="button"
              data-palette={item.key}
              aria-pressed={preset === item.key}
              onClick={() => {
                setPreset(item.key);
                soundEngine.playSelect(soundPack, soundEnabled, 0.5);
              }}
            >
              <span style={{ background: item.swatch }} aria-hidden="true" />
              {item.label}
            </button>
          ))}
          {customImageUrl && (
            <button
              type="button"
              aria-pressed={preset === "custom"}
              onClick={() => {
                setPreset("custom");
                soundEngine.playSelect(soundPack, soundEnabled, 0.5);
              }}
            >
              <span
                style={{ background: palette.seedHex }}
                aria-hidden="true"
              />
              Your Photo
            </button>
          )}
        </div>
      </div>

      <p className="illustration-note">
        Interactive simulator powered by Luma Home’s real HSV palette extractor and 44.1kHz acoustic synth engine. Available apps and content depend on your TV.
      </p>
    </div>
  );
}
