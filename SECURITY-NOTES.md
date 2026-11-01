# Security notes

- Never enable cleartext traffic for production. `usesCleartextTraffic=false` and the network security config intentionally reject HTTP.
- The biometric key is stored in Android Keystore, requires strong biometric authentication for each cryptographic operation, and is invalidated when biometrics are re-enrolled.
- Only the encrypted session token and session metadata are stored. Passwords are not persisted.
- The JavaScript bridge is injected only into the configured HTTPS origin. External links are opened outside the WebView.
- The app trusts the operating system trust store. It does not bypass certificate errors.
- The server's existing session expiry and role authorization remain authoritative.
- The debug APK is for testing. Create and protect a separate release signing key for production distribution.


## دامنه عملیاتی

نسخه فعلی فقط برای `https://scooljavanrood.ir/app/` ساخته می‌شود مگر اینکه هنگام Build عمداً `WEB_APP_URL` با یک HTTPS معتبر override شود.
