import "./globals.css";

export const metadata = {
  title: "KV Store Dashboard",
  description: "Live status of the distributed key-value store cluster",
};

export default function RootLayout({ children }) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
