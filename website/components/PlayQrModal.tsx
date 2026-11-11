"use client";

import { useState, useEffect, useMemo } from "react";
import { encodeQrMatrix } from "../lib/qr";
import { site } from "../lib/site";

export default function PlayQrModal({
  open,
  onClose,
}: {
  open: boolean;
  onClose: () => void;
}) {
  const [copied, setCopied] = useState(false);
  const matrix = useMemo(() => encodeQrMatrix(site.playStoreUrl), []);

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open, onClose]);

  if (!open) return null;

  const size = matrix.length;
  const quiet = 2;
  const total = size + quiet * 2;

  const copyLink = async () => {
    try {
      await navigator.clipboard.writeText(site.playStoreUrl);
      setCopied(true);
      setTimeout(() => setCopied(false), 2200);
    } catch {
      // Fallback ignored
    }
  };

  return (
    <div
      className="qr-modal-backdrop"
      role="dialog"
      aria-modal="true"
      aria-labelledby="qr-modal-title"
      onClick={onClose}
    >
      <div className="qr-modal-card" onClick={(e) => e.stopPropagation()}>
        <div className="qr-modal-top">
          <span className="eyebrow">ANDROID TV · GOOGLE TV</span>
          <button
            type="button"
            className="qr-modal-close"
            onClick={onClose}
            aria-label="Close Play Store QR dialog"
          >
            ✕
          </button>
        </div>
        <h3 id="qr-modal-title">
          Send Luma Home to <em>your TV.</em>
        </h3>
        <p className="qr-modal-sub">
          Scan with your phone camera to open Google Play and remotely install Luma Home directly to your Android TV or Google TV device.
        </p>
        <div className="qr-frame-wrap">
          <svg
            viewBox={`0 0 ${total} ${total}`}
            className="qr-svg"
            role="img"
            aria-label="QR Code for Luma Home on Google Play"
            shapeRendering="crispEdges"
          >
            <rect width={total} height={total} fill="#f0eee5" rx="1.5" />
            {matrix.map((row, r) =>
              row.map((dark, c) =>
                dark ? (
                  <rect
                    key={`${r}-${c}`}
                    x={c + quiet}
                    y={r + quiet}
                    width={1.03}
                    height={1.03}
                    fill="#111310"
                  />
                ) : null
              )
            )}
          </svg>
        </div>
        <div className="qr-modal-actions">
          <a
            className="button primary"
            href={site.playStoreUrl}
            target="_blank"
            rel="noopener noreferrer"
          >
            Open Google Play <span aria-hidden="true">↗</span>
          </a>
          <button type="button" className="button secondary" onClick={copyLink}>
            {copied ? "Link copied ✓" : "Copy Play link"}
          </button>
        </div>
      </div>
    </div>
  );
}
