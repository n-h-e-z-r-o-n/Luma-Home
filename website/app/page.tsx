import Link from "next/link";
import HomePreview from "../components/HomePreview";

export default function Home() {
  return <main id="main">
    <section className="hero wrap">
      <div className="eyebrow"><span className="status-dot" /> MADE FOR ANDROID TV · IN DEVELOPMENT</div>
      <h1>Make yourself<br /><em>at home.</em></h1>
      <div className="hero-bottom"><p>Your favorite screen, with a little less noise.<br className="desktop-break" /> A personal home for the apps and moments you choose.</p><a className="button primary" href="#experience">Meet Luma Home <span aria-hidden="true">↓</span></a></div>
      <HomePreview />
    </section>
    <section id="experience" className="experience wrap">
      <div className="section-intro"><span className="eyebrow">LESS IN THE WAY. MORE YOUR WAY.</span><h2>A home screen that<br />feels like <em>your space.</em></h2><p>Keep the everyday things close. Let everything else take a step back.</p></div>
      <div className="feature-list">
        <article><span className="feature-number">01 / YOUR APPS</span><div><h3>The things you reach for.<br />Right where you want them.</h3><p>Choose your Home apps and arrange them in one simple row. All Apps keeps the rest searchable, with TV utilities in their own section.</p></div><div className="feature-mark" aria-hidden="true">▦</div></article>
        <article><span className="feature-number">02 / YOUR ATMOSPHERE</span><div><h3>A wallpaper.<br />A whole different feeling.</h3><p>Pick a photo, gradient or solid background. Let soft colors flow into your menus and focus accents, or stay with a warm or cool theme. You set the intensity.</p></div><div className="color-orbit" aria-hidden="true"><span /><span /><span /></div></article>
        <article><span className="feature-number">03 / YOUR CHOICE</span><div><h3>Recommendations,<br />by invitation.</h3><p>Turn content on for the apps you choose. Explore a cinematic featured area when a source shares content, or keep the calm of a clock. Nothing needs to be on all the time.</p><small>Shared content, artwork and video previews depend on the app and TV.</small></div><div className="feature-mark quiet-mark" aria-hidden="true">◷</div></article>
      </div>
    </section>
    <section className="privacy-callout wrap"><div className="eyebrow">A QUIETER KIND OF HOME</div><h2>Personal doesn’t have<br />to mean <em>an account.</em></h2><div className="privacy-bottom"><p>No Luma sign-in. No built-in advertising or analytics SDK.<br />Your preferences and imported wallpapers stay on your TV. Optional content connects to its providers.</p><Link className="text-link" href="/privacy">Read our privacy policy <span aria-hidden="true">↗</span></Link></div></section>
    <section className="closing wrap"><span className="eyebrow">WHAT COMES NEXT</span><h2>A better home, taking shape.</h2><p>Luma Home is in development for Android TV and Google TV.<br />A Google Play release is being prepared.</p><Link className="button secondary" href="/support">Questions or feedback? <span aria-hidden="true">↗</span></Link></section>
  </main>;
}
