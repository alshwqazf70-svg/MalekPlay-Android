# MalekPlayer Wyzie proxy

This Cloudflare Worker keeps the Wyzie API key out of the Android APK and Git repository. The Android app stores only the Worker's public HTTPS URL.

## Deploy

1. Create a Cloudflare account and install Wrangler on a trusted computer or Codespace.
2. From this directory run `npx wrangler login`.
3. Add the Wyzie key as a Worker secret (never place it in this repository):
   ```sh
   npx wrangler secret put WYZIE_API_KEY
   ```
   Paste the key only into Wrangler's hidden prompt.
4. Deploy:
   ```sh
   npx wrangler deploy
   ```
5. Open the printed `https://malekplayer-wyzie-proxy.<account>.workers.dev/health` URL. It should return `{"ok":true,"service":"malekplayer-wyzie-proxy"}`.
6. In MalekPlayer > Online subtitle search > Optional API settings, enter only the Worker base URL (for example `https://malekplayer-wyzie-proxy.<account>.workers.dev`). Do not enter the Wyzie API key in the app.

## Security controls

- The upstream Wyzie key is read only from the Cloudflare Worker secret `WYZIE_API_KEY`.
- Only GET `/search` is proxied; all other paths are rejected except `/health`.
- The Worker allowlists query parameters, validates IMDb/TMDB IDs, paired season/episode values, and Arabic/English language filters.
- A Cloudflare Rate Limiting binding caps each source IP at 30 requests per minute. Requests fail closed if the binding or secret is missing.
- The app validates every returned subtitle URL as HTTPS on the exact `sub.wyzie.io` host before downloading.
- The Worker never returns or logs the upstream API key.

The Worker URL is not a secret. The Wyzie API key must never be copied into Android settings, Gradle properties, source files, screenshots, or GitHub.
