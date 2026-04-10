# Burp2Swagger

Burp Suite extension that takes OpenAPI/Swagger JSON responses from proxy history or site map and serves them in a local Swagger UI.

## How it works

1. Browse a target through Burp — its `swagger.json` / `openapi.json` endpoint gets captured
2. Right-click the request in site map, proxy history, or any Burp tab → **"Send to Swagger UI"**
3. The response body (raw OpenAPI spec) is saved and served via a built-in HTTP server
4. Open `http://localhost:8090` — your spec(s) are rendered in Swagger UI
5. Multiple specs are supported — they appear in a dropdown selector

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
- Specs are stored in `burp2swagger_out/` in Burp's working directory
- The server stops automatically when the extension is unloaded
