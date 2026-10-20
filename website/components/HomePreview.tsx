"use client";

import { useState } from "react";

const themes = [{ key: "forest", label: "Forest" }, { key: "warm", label: "Warm" }, { key: "cool", label: "Cool" }];
export default function HomePreview() {
  const [theme, setTheme] = useState("forest");
  return <div className="preview-section">
    <div className="tv-shell" data-theme={theme}>
      <div className="tv-scene" aria-hidden="true"><div className="landscape-back" /><div className="landscape-front" /><div className="sun" /></div>
      <div className="tv-ui">
        <div className="tv-date">Monday, 19 October</div><div className="tv-time">20:48</div>
        <div className="tv-home-heading">Home</div>
        <div className="tv-apps" aria-hidden="true">
          <div className="mock-app active"><span>▶</span><small>Cinema</small></div>
          <div className="mock-app music"><span>♫</span></div>
          <div className="mock-app library"><span>▤</span></div>
          <div className="mock-app luma"><img src="/luma-icon.png" width="62" height="62" alt="" /></div>
          <div className="mock-app games"><span>✦</span></div>
        </div>
        <div className="mock-controls" aria-hidden="true"><span>▦ <i>All Apps</i></span><span>▱ <i>Inputs</i></span><span>☼ <i>Settings</i></span></div>
      </div>
      <span className="tv-led" aria-hidden="true" />
    </div>
    <div className="preview-caption"><span>One home. Your atmosphere.</span><div className="palette-picker" role="group" aria-label="Preview a color palette">{themes.map(item => <button key={item.key} type="button" data-palette={item.key} aria-pressed={theme === item.key} onClick={() => setTheme(item.key)}><span aria-hidden="true" />{item.label}</button>)}</div></div>
    <p className="illustration-note">Illustrative interface. Available apps and content depend on your TV.</p>
  </div>;
}
