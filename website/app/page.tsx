"use client";

import { useState } from "react";
import Link from "next/link";
import HomePreview, {
  type PresetKey,
  type HeaderMode,
  type ActiveBlade,
} from "../components/HomePreview";
import PlayQrModal from "../components/PlayQrModal";
import { site } from "../lib/site";

export default function Home() {
  const [qrOpen, setQrOpen] = useState(false);
  const [simCommand, setSimCommand] = useState<{
    preset?: PresetKey;
    headerMode?: HeaderMode;
    blade?: ActiveBlade;
    seq: number;
  } | null>(null);

  const triggerSimulator = (opts: {
    preset?: PresetKey;
    headerMode?: HeaderMode;
    blade?: ActiveBlade;
  }) => {
    setSimCommand({ ...opts, seq: Date.now() });
    const el = document.getElementById("simulator");
    if (el) {
      el.scrollIntoView({ behavior: "smooth", block: "center" });
    }
  };

  return (
    <main id="main">
      <section className="hero wrap">
        <div className="eyebrow">
          <span className="status-dot" /> AVAILABLE ON GOOGLE PLAY · ANDROID TV &amp; GOOGLE TV
        </div>
        <h1>
          Make yourself
          <br />
          <em>at home.</em>
        </h1>
        <div className="hero-bottom">
          <p>
            Your favorite screen, with a little less noise.
            <br className="desktop-break" /> A personal home for the apps, atmosphere and moments you choose.
          </p>
          <div className="hero-cta-group">
            <a
              className="button primary"
              href={site.playStoreUrl}
              target="_blank"
              rel="noopener noreferrer"
            >
              Get it on Google Play <span aria-hidden="true">↗</span>
            </a>
            <button
              type="button"
              className="button secondary qr-trigger-btn"
              onClick={() => setQrOpen(true)}
            >
              <span aria-hidden="true">⊞</span> Send to TV via QR
            </button>
          </div>
        </div>

        <HomePreview externalPresetRequest={simCommand} />
      </section>

      <section id="experience" className="experience wrap">
        <div className="section-intro">
          <span className="eyebrow">LESS IN THE WAY. MORE YOUR WAY.</span>
          <h2>
            A home screen that
            <br />
            feels like <em>your space.</em>
          </h2>
          <p>
            Keep the everyday things close. Let everything else take a step back. Click any pillar below to test it live in the TV simulator.
          </p>
        </div>

        <div className="feature-list">
          <article>
            <span className="feature-number">01 / YOUR APPS &amp; FINISHING</span>
            <div>
              <h3>
                The things you reach for.
                <br />
                Right where you want them.
              </h3>
              <p>
                Choose your Home apps and arrange them in one calm row. Customize icon geometry (circle, squircle, rounded) and material finishes (glass, warm paper, monochrome). All Apps keeps the rest searchable, with TV utilities neatly separated.
              </p>
              <button
                type="button"
                className="feature-try-link"
                onClick={() => triggerSimulator({ blade: "appearance" })}
              >
                Try icon shapes &amp; materials in simulator <span aria-hidden="true">↑</span>
              </button>
            </div>
            <div className="feature-mark" aria-hidden="true">
              ▦
            </div>
          </article>

          <article>
            <span className="feature-number">02 / YOUR ATMOSPHERE &amp; QR DROP</span>
            <div>
              <h3>
                A wallpaper from your phone.
                <br />
                A whole different feeling.
              </h3>
              <p>
                Scan a local Wi-Fi QR code to beam photos straight from your phone camera roll—zero cloud, zero login. Luma’s on-device HSV palette engine extracts dominant hues to softly tint your menus and focus accents at the exact intensity you set.
              </p>
              <button
                type="button"
                className="feature-try-link"
                onClick={() => triggerSimulator({ blade: "phone-drop" })}
              >
                Test QR Wallpaper Drop in simulator <span aria-hidden="true">↑</span>
              </button>
            </div>
            <div className="color-orbit" aria-hidden="true">
              <span />
              <span />
              <span />
            </div>
          </article>

          <article>
            <span className="feature-number">03 / YOUR CHOICE OF CALM</span>
            <div>
              <h3>
                Recommendations,
                <br />
                by invitation only.
              </h3>
              <p>
                Turn content on only for the apps you choose. Explore an edge-to-edge cinematic featured showcase when a source shares content, or keep the quiet calm of a clock and local weather. Nothing needs to be on all the time.
              </p>
              <small>
                Shared content, artwork and video previews depend on the app and TV.
              </small>
              <button
                type="button"
                className="feature-try-link"
                onClick={() =>
                  triggerSimulator({ headerMode: "cinema", blade: "none" })
                }
              >
                Preview Invited Cinema mode above <span aria-hidden="true">↑</span>
              </button>
            </div>
            <div className="feature-mark quiet-mark" aria-hidden="true">
              ◷
            </div>
          </article>
        </div>
      </section>

      <section className="privacy-callout wrap">
        <div className="eyebrow">A QUIETER KIND OF HOME</div>
        <h2>
          Personal doesn’t have
          <br />
          to mean <em>an account.</em>
        </h2>
        <div className="privacy-bottom">
          <p>
            No Luma sign-in. No built-in advertising or analytics SDK.
            <br />
            Your preferences and imported wallpapers stay on your TV. Optional content connects directly to its providers.
          </p>
          <Link className="text-link" href="/privacy">
            Read our privacy policy <span aria-hidden="true">↗</span>
          </Link>
        </div>
      </section>

      <section className="closing wrap">
        <span className="eyebrow">READY FOR YOUR LIVING ROOM</span>
        <h2>Bring Luma Home to your TV.</h2>
        <p>
          Available now on Google Play for Android TV and Google TV devices.
          <br />
          Install remotely from your browser or scan the QR code with your phone.
        </p>
        <div className="closing-cta-group">
          <a
            className="button primary"
            href={site.playStoreUrl}
            target="_blank"
            rel="noopener noreferrer"
          >
            Get it on Google Play <span aria-hidden="true">↗</span>
          </a>
          <button
            type="button"
            className="button secondary"
            onClick={() => setQrOpen(true)}
          >
            Scan QR for TV <span aria-hidden="true">⊞</span>
          </button>
          <Link className="button secondary" href="/support">
            Questions or feedback? <span aria-hidden="true">↗</span>
          </Link>
        </div>
      </section>

      <PlayQrModal open={qrOpen} onClose={() => setQrOpen(false)} />
    </main>
  );
}
