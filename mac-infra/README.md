# Optional: run replies on a Claude subscription (Mac-side proxy)

The app talks to any OpenAI-compatible endpoint. If you have a Claude Pro/Max
subscription, this proxy routes replies through the `claude` CLI so they count
against your included quota instead of pay-per-use API billing.

- `boox-diary-proxy.py` -> `~/bin/`. Keeps one persistent `claude` stream-json
  session warm (per-turn latency ~4-8s), decodes the app's base64 page images
  and feeds them to Claude inline. Set a shared secret via `DIARY_PROXY_TOKEN`.
- `com.example.boox-diary-proxy.plist` -> `~/Library/LaunchAgents/` (edit the
  script path first), then `launchctl bootstrap gui/$(id -u) <plist>`.
- `diary-backend` -> `~/bin/`. Switches the on-device app config between the
  proxy (route B) and a direct API (route A) over adb:
  `BOOX_ADDR=<ip>:5555 diary-backend a <api-key>` / `diary-backend b`.

App settings for the proxy route: backend = OpenAI-compatible,
base URL = `http://<mac-lan-ip>:7272/v1`, API key = your `DIARY_PROXY_TOKEN`.
