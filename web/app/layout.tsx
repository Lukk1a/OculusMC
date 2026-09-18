import type { Metadata } from 'next';
import { Geist, Geist_Mono } from 'next/font/google';
import './globals.css';
import { cn } from "@/lib/utils";

const geist = Geist({subsets:['latin'],variable:'--font-sans'});

const geistMono = Geist_Mono({
  subsets: ['latin'],
  variable: '--font-mono',
  display: 'swap',
});

export const metadata: Metadata = {
  title: 'Oculus — Server Operations Console',
  description: 'Minecraft server operations dashboard',
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en" className={cn(geistMono.variable, "font-sans", geist.variable, "dark")}>
      <body className="bg-vbg-surface-primary text-vbg-text-primary antialiased">
        {children}
      </body>
    </html>
  );
}
