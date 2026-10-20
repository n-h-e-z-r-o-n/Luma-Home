import type { Metadata } from "next";
import Link from "next/link";
import { site } from "../lib/site";
import "./globals.css";

export const metadata: Metadata = {
  metadataBase: new URL(site.url),
  title: { default: "Luma Home — A little more you. A lot less noise.", template: "%s | Luma Home" },
  description: "A personal home screen for Android TV and Google TV. Your apps, wallpaper colors and optional recommendations, on your terms.",
  openGraph: {
    type: "website",
    siteName: site.name,
    title: "Luma Home — Make yourself at home.",
    description: "A personal home screen for Android TV and Google TV.",
    images: [{ url: "/social-banner.png", width: 1672, height: 941, alt: "Luma Home house and sun mark" }],
  },
  twitter: { card: "summary_large_image" },
  icons: { icon: "/luma-icon.png", apple: "/luma-icon.png" },
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return <html lang="en"><body>
    <a className="skip-link" href="#main">Skip to content</a>
    <header className="site-header wrap">
      <Link className="brand" href="/" aria-label="Luma Home home"><img src="/luma-icon.png" width="40" height="40" alt="" /><span>Luma Home</span></Link>
      <nav aria-label="Main navigation"><Link href="/#experience">The experience</Link><Link href="/privacy">Privacy</Link><Link className="nav-contact" href="/support">Get in touch <span aria-hidden="true">↗</span></Link></nav>
    </header>
    {children}
    <footer className="site-footer wrap">
      <div><Link className="brand" href="/"><img src="/luma-icon.png" width="32" height="32" alt="" /><span>Luma Home</span></Link><p>A little more you. A lot less noise.</p></div>
      <div className="footer-links"><Link href="/privacy">Privacy policy</Link><Link href="/support">Support</Link><a href={site.companyUrl}>A {site.publisher} project <span aria-hidden="true">↗</span></a><small>© 2026 {site.publisher}</small></div>
    </footer>
  </body></html>;
}
