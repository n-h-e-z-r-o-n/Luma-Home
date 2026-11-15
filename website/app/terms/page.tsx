import type { Metadata } from "next";
import Link from "next/link";
import { contactHref, site } from "../../lib/site";

export const metadata: Metadata = {
  title: "Terms of Service",
  description: "Terms and conditions for using Luma Home on Android TV and Google TV devices.",
  alternates: { canonical: "/terms" },
};

export default function Terms() {
  return (
    <main id="main" className="legal-page wrap">
      <div className="legal-heading">
        <span className="eyebrow">LUMA HOME / TERMS</span>
        <h1>Terms of<br /><em>Service.</em></h1>
        <p>Terms and Conditions · Last updated 15 November 2026</p>
      </div>
      <div className="legal-layout">
        <aside>
          <span className="eyebrow">ON THIS PAGE</span>
          <nav aria-label="Terms of Service sections">
            <a href="#acceptance">Acceptance</a>
            <a href="#license">License</a>
            <a href="#functionality">Launcher & Content</a>
            <a href="#purchases">Voluntary Support</a>
            <a href="#disclaimer">Disclaimers</a>
            <a href="#contact">Contact</a>
          </nav>
        </aside>
        <div className="legal-body">
          <section id="acceptance">
            <h2>1. Acceptance of Terms</h2>
            <p>
              By downloading, installing, or using Luma Home (&quot;the App&quot;), published by {site.publisher} (&quot;we,&quot; &quot;our,&quot; or &quot;us&quot;), you agree to be bound by these Terms of Service. If you do not agree to these terms, please do not install or use the App.
            </p>
          </section>

          <section id="license">
            <h2>2. License and Usage</h2>
            <p>
              We grant you a personal, non-exclusive, non-transferable, revocable license to use Luma Home on compatible Android TV and Google TV devices that you own or control, strictly for personal, non-commercial home entertainment purposes.
            </p>
            <p>
              You agree not to modify, reverse-engineer, decompile, or create derivative works from the application binaries, nor bypass any system security features or device management protections.
            </p>
          </section>

          <section id="functionality">
            <h2>3. Launcher Functionality and Third-Party Content</h2>
            <p>
              Luma Home functions as an independent home screen launcher interface. It organizes installed applications, displays television inputs, and surfaces optional recommendations provided by compatible apps installed on your device.
            </p>
            <p>
              Third-party content, channels, streaming media, and application recommendations remain the intellectual property and responsibility of their respective providers. Luma Home does not host, control, or license third-party media streams.
            </p>
          </section>

          <section id="purchases">
            <h2>4. Voluntary Support &amp; In-App Purchases</h2>
            <p>
              Luma Home is provided free of ads and requires no paid subscriptions for core launcher capabilities. Any voluntary tips or supporter contributions made within the App are processed securely via Google Play Billing in accordance with Google Play&apos;s Terms of Service.
            </p>
            <p>
              All voluntary in-app purchases are one-time payments to support ongoing independent development and TV optimizations.
            </p>
          </section>

          <section id="disclaimer">
            <h2>5. Disclaimer of Warranties and Limitation of Liability</h2>
            <p>
              Luma Home is provided &quot;AS IS&quot; and &quot;AS AVAILABLE&quot; without warranties of any kind, either express or implied. While we strive to ensure optimal performance across Android TV hardware, we do not guarantee uninterrupted operation or compatibility with all third-party firmware modifications.
            </p>
            <p>
              To the maximum extent permitted by applicable law, {site.publisher} shall not be liable for any indirect, incidental, or consequential damages resulting from the use or inability to use the App.
            </p>
          </section>

          <section id="contact">
            <h2>6. Contact and Inquiries</h2>
            <p>
              If you have any questions regarding these Terms of Service, please contact {site.publisher}
              {site.supportEmail ? (
                <> at <a href={contactHref}>{site.supportEmail}</a></>
              ) : (
                <> through <a href={site.companyUrl}>our official website</a></>
              )}.
            </p>
            <div style={{ marginTop: "1.5rem" }}>
              <Link className="text-link" href="/privacy">
                Read our Privacy Policy <span aria-hidden="true">→</span>
              </Link>
            </div>
          </section>
        </div>
      </div>
    </main>
  );
}
