import type { Metadata, Viewport } from "next";
import type { ReactNode } from "react";
import "./globals.css";

export const metadata: Metadata = {
  title: "squaremap-pro map",
  description: "Buildings, roads, railways and stations on squaremap",
};

export const viewport: Viewport = {
  width: "device-width",
  initialScale: 1,
  // The map is edge-to-edge; matching the browser/OS chrome to the map canvas keeps it seamless.
  themeColor: "#17171a",
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
