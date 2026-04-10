package burp;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.persistence.PersistedObject;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;

import com.google.gson.*;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class BurpExtender implements BurpExtension {
    private MontoyaApi api;
    private volatile ServerSocket serverSocket;
    private volatile boolean serverRunning;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    private final Map<String, byte[]> specs = new ConcurrentHashMap<>();
    private volatile byte[] indexHtml = new byte[0];

    private SpecTableModel tableModel;

    private static final String NAMES_KEY = "specNames";
    private static final String NAMES_SEP = "\n";

    @Override
    public void initialize(MontoyaApi api) {
        this.api = api;
        api.extension().setName("Burp2Swagger");

        restoreFromProject();

        // Start HTTP server
        try {
            serverSocket = new ServerSocket(8090);
            serverRunning = true;
            Thread t = new Thread(this::runServer, "Burp2Swagger-HTTP");
            t.setDaemon(true);
            t.start();
            api.logging().logToOutput("Burp2Swagger: server started on http://localhost:8090");
        } catch (Exception e) {
            api.logging().logToError("Burp2Swagger: port 8090 in use - " + e.getMessage());
        }

        // Register tab
        api.userInterface().registerSuiteTab("Burp2Swagger", buildTab());

        // Context menu
        api.userInterface().registerContextMenuItemsProvider(new ContextMenuItemsProvider() {
            @Override
            public List<Component> provideMenuItems(ContextMenuEvent event) {
                List<HttpRequestResponse> selected = event.selectedRequestResponses();
                if (selected == null || selected.isEmpty()) return Collections.emptyList();

                boolean hasResponse = false;
                for (HttpRequestResponse rr : selected) {
                    if (rr.response() != null) { hasResponse = true; break; }
                }
                if (!hasResponse) return Collections.emptyList();

                JMenuItem sendItem = new JMenuItem("Send to Swagger UI");
                sendItem.addActionListener(e -> {
                    for (HttpRequestResponse rr : selected) {
                        addSpec(rr);
                    }
                    rebuildIndex();
                    tableModel.refresh();
                    api.logging().logToOutput("Burp2Swagger: open http://localhost:8090");
                });

                return List.of(sendItem);
            }
        });

        // Unload
        api.extension().registerUnloadingHandler(() -> {
            serverRunning = false;
            try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        });

        api.logging().logToOutput("Burp2Swagger: loaded. Right-click any request with an OpenAPI response -> 'Send to Swagger UI'");
        if (!specs.isEmpty()) {
            api.logging().logToOutput("Burp2Swagger: restored " + specs.size() + " spec(s) from project");
        }
    }

    private void addSpec(HttpRequestResponse rr) {
        if (rr.response() == null) return;
        byte[] bodyBytes = rr.response().body().getBytes();
        if (bodyBytes.length == 0) return;

        String bodyStr = new String(bodyBytes, StandardCharsets.UTF_8);
        try {
            if (!JsonParser.parseString(bodyStr).isJsonObject()) {
                api.logging().logToError("Burp2Swagger: response is not a JSON object, skipping");
                return;
            }
        } catch (JsonSyntaxException ex) {
            api.logging().logToError("Burp2Swagger: response is not valid JSON, skipping");
            return;
        }

        String specName = deriveSpecName(rr);
        specs.put(specName, bodyBytes);
        persistToProject(specName, bodyStr);
        api.logging().logToOutput("Burp2Swagger: added spec '" + specName + "'");
    }

    // --- Tab UI ---

    private JComponent buildTab() {
        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Top bar with link and buttons
        JPanel topBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        JLabel linkLabel = new JLabel("<html>Swagger UI: <a href=''>http://localhost:8090</a></html>");
        linkLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        linkLabel.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                try { Desktop.getDesktop().browse(java.net.URI.create("http://localhost:8090")); } catch (Exception ignored) {}
            }
        });
        topBar.add(linkLabel);
        panel.add(topBar, BorderLayout.NORTH);

        // Table
        tableModel = new SpecTableModel();
        JTable table = new JTable(tableModel);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.getColumnModel().getColumn(0).setPreferredWidth(250); // Name
        table.getColumnModel().getColumn(1).setPreferredWidth(400); // Title
        table.getColumnModel().getColumn(2).setPreferredWidth(100); // Size
        JScrollPane scrollPane = new JScrollPane(table);
        panel.add(scrollPane, BorderLayout.CENTER);

        // Buttons
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));

        JButton editBtn = new JButton("Edit JSON");
        editBtn.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row < 0) return;
            String name = tableModel.getSpecNameAt(row);
            byte[] data = specs.get(name);
            if (data == null) return;

            JTextArea textArea = new JTextArea(new String(data, StandardCharsets.UTF_8));
            textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
            textArea.setLineWrap(true);
            JScrollPane sp = new JScrollPane(textArea);
            sp.setPreferredSize(new Dimension(800, 600));

            int result = JOptionPane.showConfirmDialog(panel, sp,
                    "Edit: " + name, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (result == JOptionPane.OK_OPTION) {
                String newJson = textArea.getText();
                try {
                    if (!JsonParser.parseString(newJson).isJsonObject()) {
                        JOptionPane.showMessageDialog(panel, "Not a valid JSON object", "Error", JOptionPane.ERROR_MESSAGE);
                        return;
                    }
                } catch (JsonSyntaxException ex) {
                    JOptionPane.showMessageDialog(panel, "Invalid JSON: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                byte[] newBytes = newJson.getBytes(StandardCharsets.UTF_8);
                specs.put(name, newBytes);
                persistToProject(name, newJson);
                rebuildIndex();
                tableModel.refresh();
            }
        });

        JButton deleteBtn = new JButton("Delete");
        deleteBtn.addActionListener(e -> {
            int[] rows = table.getSelectedRows();
            if (rows.length == 0) return;
            int confirm = JOptionPane.showConfirmDialog(panel,
                    "Delete " + rows.length + " spec(s)?", "Confirm", JOptionPane.YES_NO_OPTION);
            if (confirm != JOptionPane.YES_OPTION) return;
            // Collect names first (indices shift after removal)
            List<String> toRemove = new ArrayList<>();
            for (int row : rows) {
                toRemove.add(tableModel.getSpecNameAt(row));
            }
            for (String name : toRemove) {
                specs.remove(name);
                removeFromProject(name);
                api.logging().logToOutput("Burp2Swagger: removed spec '" + name + "'");
            }
            rebuildIndex();
            tableModel.refresh();
        });

        JButton deleteAllBtn = new JButton("Delete All");
        deleteAllBtn.addActionListener(e -> {
            if (specs.isEmpty()) return;
            int confirm = JOptionPane.showConfirmDialog(panel,
                    "Delete all " + specs.size() + " spec(s)?", "Confirm", JOptionPane.YES_NO_OPTION);
            if (confirm != JOptionPane.YES_OPTION) return;
            for (String name : new ArrayList<>(specs.keySet())) {
                specs.remove(name);
                removeFromProject(name);
            }
            rebuildIndex();
            tableModel.refresh();
            api.logging().logToOutput("Burp2Swagger: cleared all specs");
        });

        buttons.add(editBtn);
        buttons.add(deleteBtn);
        buttons.add(deleteAllBtn);
        panel.add(buttons, BorderLayout.SOUTH);

        return panel;
    }

    // --- Table model ---

    private class SpecTableModel extends AbstractTableModel {
        private List<String> names = new ArrayList<>(specs.keySet());
        private final String[] COLUMNS = {"Name", "Title", "Size"};

        public void refresh() {
            names = new ArrayList<>(specs.keySet());
            fireTableDataChanged();
        }

        public String getSpecNameAt(int row) {
            return names.get(row);
        }

        @Override public int getRowCount() { return names.size(); }
        @Override public int getColumnCount() { return COLUMNS.length; }
        @Override public String getColumnName(int col) { return COLUMNS[col]; }

        @Override
        public Object getValueAt(int row, int col) {
            String name = names.get(row);
            byte[] data = specs.get(name);
            if (data == null) return "";
            switch (col) {
                case 0: return name;
                case 1:
                    try {
                        String json = new String(data, StandardCharsets.UTF_8);
                        JsonObject spec = JsonParser.parseString(json).getAsJsonObject();
                        if (spec.has("info") && spec.getAsJsonObject("info").has("title")) {
                            return spec.getAsJsonObject("info").get("title").getAsString();
                        }
                    } catch (Exception ignored) {}
                    return "-";
                case 2:
                    if (data.length < 1024) return data.length + " B";
                    return (data.length / 1024) + " KB";
                default: return "";
            }
        }
    }

    // --- Persistence ---

    private void persistToProject(String specName, String json) {
        try {
            PersistedObject data = api.persistence().extensionData();
            data.setString("spec:" + specName, json);
            Set<String> names = getSpecNames(data);
            names.add(specName);
            data.setString(NAMES_KEY, String.join(NAMES_SEP, names));
        } catch (Exception e) {
            api.logging().logToError("Burp2Swagger: persist error: " + e.getMessage());
        }
    }

    private void removeFromProject(String specName) {
        try {
            PersistedObject data = api.persistence().extensionData();
            data.deleteString("spec:" + specName);
            Set<String> names = getSpecNames(data);
            names.remove(specName);
            data.setString(NAMES_KEY, String.join(NAMES_SEP, names));
        } catch (Exception e) {
            api.logging().logToError("Burp2Swagger: remove error: " + e.getMessage());
        }
    }

    private void restoreFromProject() {
        try {
            PersistedObject data = api.persistence().extensionData();
            Set<String> names = getSpecNames(data);
            for (String name : names) {
                String json = data.getString("spec:" + name);
                if (json != null && !json.isEmpty()) {
                    specs.put(name, json.getBytes(StandardCharsets.UTF_8));
                }
            }
            if (!specs.isEmpty()) {
                rebuildIndex();
            }
        } catch (Exception e) {
            api.logging().logToError("Burp2Swagger: restore error: " + e.getMessage());
        }
    }

    private Set<String> getSpecNames(PersistedObject data) {
        String raw = data.getString(NAMES_KEY);
        if (raw == null || raw.isEmpty()) return new LinkedHashSet<>();
        return new LinkedHashSet<>(Arrays.asList(raw.split(NAMES_SEP)));
    }

    // --- Helpers ---

    private String deriveSpecName(HttpRequestResponse rr) {
        var req = rr.request();
        var svc = req.httpService();
        String host = svc.host();
        String path = req.pathWithoutQuery();
        String cleanPath = path.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (cleanPath.startsWith("_")) cleanPath = cleanPath.substring(1);
        if (cleanPath.isEmpty()) cleanPath = "spec";
        return host + "_" + cleanPath;
    }

    private void rebuildIndex() {
        JsonArray urls = new JsonArray();
        for (var entry : specs.entrySet()) {
            JsonObject u = new JsonObject();
            u.addProperty("url", "http://localhost:8090/spec/" + entry.getKey());
            String title = entry.getKey();
            try {
                String json = new String(entry.getValue(), StandardCharsets.UTF_8);
                JsonObject spec = JsonParser.parseString(json).getAsJsonObject();
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

        indexHtml = html.getBytes(StandardCharsets.UTF_8);
    }

    // --- HTTP server ---

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
            while (true) {
                String line = in.readLine();
                if (line == null || line.isEmpty()) break;
            }

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String path = parts[1];
            int q = path.indexOf('?');
            if (q != -1) path = path.substring(0, q);

            if (path.equals("/") || path.equals("/index.html")) {
                respond(out, 200, "text/html; charset=utf-8", indexHtml);
            } else if (path.startsWith("/spec/")) {
                String specName = path.substring("/spec/".length());
                byte[] data = specs.get(specName);
                if (data != null) {
                    respond(out, 200, "application/json; charset=utf-8", data);
                } else {
                    respond(out, 404, "text/plain", "404 Not Found".getBytes());
                }
            } else {
                respond(out, 404, "text/plain", "404 Not Found".getBytes());
            }
        } catch (Exception ignored) {}
    }

    private void respond(OutputStream out, int status, String contentType, byte[] body) throws IOException {
        String statusText = status == 200 ? "OK" : "Not Found";
        String header = "HTTP/1.1 " + status + " " + statusText + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Connection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
    }
}
