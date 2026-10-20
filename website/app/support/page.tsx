import type { Metadata } from "next";
import Link from "next/link";
import { contactHref, site } from "../../lib/site";
export const metadata: Metadata = { title: "Support", alternates: { canonical: "/support" } };
export default function Support() {
  return <main id="main" className="support-page wrap"><span className="eyebrow">A LITTLE HELP</span><h1>Let’s make it<br /><em>feel right.</em></h1><p className="support-lead">Questions, feedback or something that isn’t working?<br />Reach the team behind Luma Home.</p><a className="button primary" href={contactHref}>{site.supportEmail ? "Email support" : `Contact ${site.publisher}`} <span aria-hidden="true">↗</span></a>{site.supportEmail && <p className="contact-address">{site.supportEmail}</p>}<div className="support-notes"><h2>A few useful details</h2><p>For a technical issue, include your TV model, Android version, Luma Home version and what happened. Please don’t send passwords, account tokens or other sensitive information.</p><h2>Recommendations depend on the source</h2><p>Some apps do not share recommendations or video previews with third-party launchers. A subscription alone does not guarantee compatibility.</p><h2>Your privacy choices</h2><p>Recommendations, recent app shortcuts and the featured area are optional. <Link href="/privacy">Read how information is handled.</Link></p></div></main>;
}
