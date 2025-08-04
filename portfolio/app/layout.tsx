// app/layout.tsx
import '@/lib/fontawesome'; // Adjust path as needed
import '@fortawesome/fontawesome-svg-core/styles.css';
import { config } from '@fortawesome/fontawesome-svg-core';
config.autoAddCss = false; // Prevent fontawesome from adding <style> automatically


export default function RootLayout({children }) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}