# Burp2Swagger

Burp Suite extension that takes OpenAPI/Swagger JSON responses and serves them in a local Swagger UI.

## How it works

1. Browse a target through Burp — its `swagger.json` / `openapi.json` endpoint gets captured
2. Right-click the request in site map, proxy history, or any Burp tab → **"Send to Swagger UI"**
3. Open `http://localhost:8090` — your spec(s) are rendered in Swagger UI
4. Multiple specs supported — they appear in a dropdown selector

## Managing specs

The **Burp2Swagger** tab in Burp Suite shows all loaded specs:

- **Edit JSON** — edit spec content directly, with JSON validation
- **Delete** — remove selected specs
- **Delete All** — clear everything

Specs are stored inside the Burp project file — they persist across sessions and are isolated per project.

## Installation

### From release
Download the jar from [Releases](../../releases) and load it via Extensions → Add in Burp Suite.

### Build from source
```
./gradlew build
```
Jar will be at `build/libs/Burp2Swagger.jar`.

## Requirements

- Burp Suite 2023.1+ (Montoya API)
- Java 21+

## Notes

- The extension starts a lightweight HTTP server on port `8090`
- No files are written to disk — everything is served from memory
- Specs persist in the Burp project file via the Persistence API
- The server stops automatically when the extension is unloaded
