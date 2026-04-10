package burp;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;

import com.google.gson.*;

import javax.swing.*;
import java.awt.Component;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class BurpExtender implements BurpExtension {
    private MontoyaApi api;
    private volatile ServerSocket serverSocket;
    private volatile boolean serverRunning;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    // specName -> json string
    private final Map<String, String> specs = new ConcurrentHashMap<>();
    private final Path outDir = Paths.get(System.getProperty("user.dir"), "burp2swagger_out");

    @Override
    public void initialize(MontoyaApi api) {
        this.api = api;
        api.extension().setName("Burp2Swagger");

        try { Files.createDirectories(outDir); } catch (IOException ignored) {}

        // Start file server
        try {
            serverSocket = new ServerSocket(8090);
            serverRunning = true;
            Thread t = new Thread(() -> runServer(), "Burp2Swagger-HTTP");
            t.setDaemon(true);
            t.start();
            api.logging().logToOutput("Burp2Swagger: server started on http://localhost:8090");
        } catch (Exception e) {
            api.logging().logToError("Burp2Swagger: port 8090 in use - " + e.getMessage());
        }

        // Context menu: send response body (OpenAPI spec) to Swagger UI
        api.userInterface().registerContextMenuItemsProvider(new ContextMenuItemsProvider() {
            @Override
            public List<Component> provideMenuItems(ContextMenuEvent event) {
                List<HttpRequestResponse> selected = event.selectedRequestResponses();
                if (selected == null || selected.isEmpty()) return Collections.emptyList();

                // Check at least one has a response
                boolean hasResponse = false;
                for (HttpRequestResponse rr : selected) {
                    if (rr.response() != null) { hasResponse = true; break; }
                }
                if (!hasResponse) return Collections.emptyList();

                JMenuItem sendItem = new JMenuItem("Send to Swagger UI");
                sendItem.addActionListener(e -> {
                    for (HttpRequestResponse rr : selected) {
                        if (rr.response() == null) continue;
                        String body = rr.response().bodyToString();
                        if (body == null || body.isBlank()) continue;

                        // Try to parse as JSON to validate it's a spec
                        try {
                            JsonElement parsed = JsonParser.parseString(body);
                            if (!parsed.isJsonObject()) {
                                api.logging().logToError("Burp2Swagger: response is not a JSON object, skipping");
                                continue;
                            }
                        } catch (JsonSyntaxException ex) {
                            api.logging().logToError("Burp2Swagger: response is not valid JSON, skipping");
                            continue;
                        }

                        // Derive a name from the URL
                        String specName = deriveSpecName(rr);
                        specs.put(specName, body);
                        saveSpec(specName, body);
                        api.logging().logToOutput("Burp2Swagger: added spec '" + specName + "'");
                    }
                    rebuildIndex();
                    api.logging().logToOutput("Burp2Swagger: open http://localhost:8090");
                });

                return List.of(sendItem);
            }
        });

        // Unload handler
        api.extension().registerUnloadingHandler(() -> {
            serverRunning = false;
            try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        });

        api.logging().logToOutput("Burp2Swagger: loaded. Right-click any request with an OpenAPI response → 'Send to Swagger UI'");
    }

    private String deriveSpecName(HttpRequestResponse rr) {
        var req = rr.request();
        var svc = req.httpService();
        String host = svc.host();
        String path = req.pathWithoutQuery();
        // Clean up path for filename
        String cleanPath = path.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (cleanPath.startsWith("_")) cleanPath = cleanPath.substring(1);
        if (cleanPath.isEmpty()) cleanPath = "spec";
        return host + "_" + cleanPath;
    }

    private void saveSpec(String name, String json) {
        try {
            Path file = outDir.resolve(name + ".json");
            Files.writeString(file, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            api.logging().logToError("Burp2Swagger: failed to write spec: " + e.getMessage());
        }
    }

    private void rebuildIndex() {
        JsonArray urls = new JsonArray();
        for (var entry : specs.entrySet()) {
            JsonObject u = new JsonObject();
            u.addProperty("url", "http://localhost:8090/" + entry.getKey() + ".json");
            // Try to extract title from the spec
            String title = entry.getKey();
            try {
                JsonObject spec = JsonParser.parseString(entry.getValue()).getAsJsonObject();
                if (spec.has("info") && spec.getAsJsonObject("info").has("title")) {
                    title = spec.getAsJsonObject("info").get("title").getAsString();
                }
            } catch (Exception ignored) {}
            u.addProperty("name", title);
            urls.add(u);
        }

        String html = "<!DOCTYPE html>\n"
                + "<html lang=\"en\">\n"
                + "<head>\n"
                + "  <meta charset=\"UTF-8\">\n"
                + "  <title>Swagger UI - Burp2Swagger</title>\n"
                + "  <link rel=\"stylesheet\" href=\"https://cdnjs.cloudflare.com/ajax/libs/swagger-ui/5.17.14/swagger-ui.css\" />\n"
                + "  <style>html{box-sizing:border-box;overflow-y:scroll}*,*:before,*:after{box-sizing:inherit}body{margin:0;background:#fafafa}</style>\n"
                + "</head>\n"
                + "<body>\n"
                + "  <div id=\"swagger-ui\"></div>\n"
                + "  <script src=\"https://cdnjs.cloudflare.com/ajax/libs/swagger-ui/5.17.14/swagger-ui-bundle.js\"></script>\n"
                + "  <script src=\"https://cdnjs.cloudflare.com/ajax/libs/swagger-ui/5.17.14/swagger-ui-standalone-preset.js\"></script>\n"
                + "  <script>\n"
                + "    window.onload = function() {\n"
                + "      SwaggerUIBundle({\n"
                + "        urls: " + gson.toJson(urls) + ",\n"
                + "        dom_id: '#swagger-ui',\n"
                + "        deepLinking: true,\n"
                + "        presets: [SwaggerUIBundle.presets.apis, SwaggerUIStandalonePreset],\n"
                + "        plugins: [SwaggerUIBundle.plugins.DownloadUrl],\n"
                + "        layout: 'StandaloneLayout'\n"
                + "      });\n"
                + "    };\n"
                + "  </script>\n"
                + "</body>\n"
                + "</html>";

        try {
            Files.writeString(outDir.resolve("index.html"), html, StandardCharsets.UTF_8);
        } catch (IOException e) {
            api.logging().logToError("Burp2Swagger: failed to write index.html: " + e.getMessage());
        }
    }

    // --- Minimal HTTP server using plain sockets ---

    private void runServer() {
        while (serverRunning) {
            try {
                Socket client = serverSocket.accept();
                Thread.ofVirtual().start(() -> handleClient(client));
            } catch (Exception e) {
                if (serverRunning) {
                    api.logging().logToError("Burp2Swagger: server error: " + e.getMessage());
                }
            }
        }
    }

    private void handleClient(Socket client) {
        try (client;
             BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
             OutputStream out = client.getOutputStream()) {

            String requestLine = in.readLine();
            if (requestLine == null) return;
            // Consume headers
            while (true) {
                String line = in.readLine();
                if (line == null || line.isEmpty()) break;
            }

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String path = parts[1];
            if (path.equals("/")) path = "/index.html";
            int q = path.indexOf('?');
            if (q != -1) path = path.substring(0, q);

            Path filePath = outDir.resolve(path.substring(1)).normalize();
            if (!filePath.startsWith(outDir) || !Files.exists(filePath) || Files.isDirectory(filePath)) {
                String resp = "HTTP/1.1 404 Not Found\r\nContent-Length: 13\r\nConnection: close\r\n\r\n404 Not Found";
                out.write(resp.getBytes());
                return;
            }

            byte[] data = Files.readAllBytes(filePath);
            String ct = "application/octet-stream";
            String name = filePath.getFileName().toString();
            if (name.endsWith(".html")) ct = "text/html; charset=utf-8";
            else if (name.endsWith(".json")) ct = "application/json; charset=utf-8";

            String header = "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: " + ct + "\r\n"
                    + "Content-Length: " + data.length + "\r\n"
                    + "Access-Control-Allow-Origin: *\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(header.getBytes());
            out.write(data);
        } catch (Exception ignored) {}
    }
}
