import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.swing.*;
import java.awt.*;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

/** A separate HTTP client: no Spring context and no direct database access. */
public class PaymentLab {
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();
    private final JFrame window = new JFrame("Payment Infra - Lab Console");
    private final JTextField base = new JTextField("http://localhost:8081", 26);
    private final JTextField amount = new JTextField("100.00", 8);
    private final JTextField currency = new JTextField("CAD", 4);
    private final JTextField key = new JTextField(UUID.randomUUID().toString(), 32);
    private final JTextField paymentId = new JTextField(36);
    private final JLabel status = new JLabel("Payment: not loaded");
    private final JLabel connection = new JLabel("Backend: unchecked");
    private final JTextArea log = new JTextArea(18, 76);
    private final JCheckBox poll = new JCheckBox("Refresh payment every 2 seconds");
    private final JComboBox<String> mode = new JComboBox<>(new String[]{"SUCCESS", "FAILURE", "TIMEOUT"});
    private final List<JComponent> controls = new ArrayList<>();
    private final Timer timer;
    private HttpRequest lastCreate;
    private boolean busy;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel("javax.swing.plaf.metal.MetalLookAndFeel"); }
            catch (Exception ignored) { /* Fall back to the installed Swing theme. */ }
            new PaymentLab().window.setVisible(true);
        });
    }

    private PaymentLab() {
        window.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        panel.add(row(new JLabel("API:"), base));
        panel.add(row(button("Check health", () -> send(request("GET", "/actuator/health", null, null))), connection));
        panel.add(row(new JLabel("Amount:"), amount, new JLabel("Currency:"), currency));
        panel.add(row(new JLabel("Idempotency key:"), button("New key", () -> key.setText(UUID.randomUUID().toString()))));
        panel.add(row(key));
        panel.add(row(button("Create payment", this::create), button("Repeat last create", () -> {
            if (lastCreate == null) throw new IllegalArgumentException("Create a payment first.");
            send(lastCreate);
        })));
        panel.add(row(new JLabel("Processor:"), mode, button("Apply mode (dev)", () ->
                send(request("POST", "/processor/mode/" + mode.getSelectedItem(), null, null)))));
        panel.add(row(new JLabel("<html>Mode applies globally to subsequent authorizations.<br>Requires the dev profile.</html>")));
        panel.add(row(new JLabel("Payment ID:")));
        panel.add(row(paymentId));
        panel.add(row(button("Look up", () -> paymentAction("")),
                button("Authorize", () -> paymentAction("/authorize")),
                button("Capture", () -> paymentAction("/capture"))));
        panel.add(row(button("Refund", () -> paymentAction("/refund")),
                button("Reconcile", () -> paymentAction("/reconcile"))));
        panel.add(row(button("View ledger", () -> send(request("GET", "/ledger/transactions/payment/" + selectedId(), null, null))),
                button("View outbox", () -> send(request("GET", "/outbox/payment/" + selectedId(), null, null)))));
        panel.add(row(poll));
        panel.add(row(status));
        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        log.setBackground(new Color(24, 30, 24));
        log.setForeground(new Color(175, 235, 165));
        log.setLineWrap(true);
        log.setWrapStyleWord(true);
        JPanel terminal = new JPanel(new BorderLayout(0, 8));
        terminal.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        terminal.add(new JScrollPane(log), BorderLayout.CENTER);
        JButton clear = new JButton("Clear log");
        clear.addActionListener(e -> log.setText(""));
        JPanel terminalHeader = new JPanel(new BorderLayout());
        terminalHeader.add(new JLabel("HTTP RESPONSE TERMINAL"), BorderLayout.WEST);
        terminalHeader.add(clear, BorderLayout.EAST);
        terminal.add(terminalHeader, BorderLayout.NORTH);
        terminal.add(new JLabel("<html>HTTP observations here.<br>Internal backend activity stays in your server terminal.</html>"), BorderLayout.SOUTH);
        JPanel controlContainer = new JPanel(new BorderLayout());
        controlContainer.add(panel, BorderLayout.NORTH);
        JScrollPane controlScroll = new JScrollPane(controlContainer);
        controlScroll.setMinimumSize(new Dimension(280, 0));
        controlScroll.getVerticalScrollBar().setUnitIncrement(16);
        terminal.setMinimumSize(new Dimension(280, 0));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, controlScroll, terminal);
        split.setContinuousLayout(true);
        split.setResizeWeight(0.4);
        split.setDividerLocation(470);
        split.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        controls.addAll(List.of(base, amount, currency, key, paymentId, mode));
        paymentId.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { reset(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { reset(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { reset(); }
            private void reset() { status.setText("Payment: not loaded for this ID"); }
        });
        timer = new Timer(2000, e -> {
            if (poll.isSelected() && !busy && !paymentId.getText().isBlank()) {
                try { paymentAction(""); }
                catch (IllegalArgumentException ex) { poll.setSelected(false); append("CLIENT: " + ex.getMessage()); }
            }
        });
        timer.start();
        window.setContentPane(split);
        window.setSize(1150, 680);
        window.setMinimumSize(new Dimension(650, 420));
        window.setLocationRelativeTo(null);
        append("Ready. Create -> Authorize -> Capture -> Refund. Choose TIMEOUT before Authorize to explore reconciliation.");
    }

    private JPanel row(Component... items) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT));
        for (Component item : items) row.add(item);
        return row;
    }

    private JButton button(String title, Runnable action) {
        JButton button = new JButton(title);
        controls.add(button);
        button.addActionListener(e -> {
            try { action.run(); }
            catch (Exception ex) { append("CLIENT: " + ex.getMessage()); }
        });
        return button;
    }

    private String selectedId() {
        try { return UUID.fromString(paymentId.getText().trim()).toString(); }
        catch (IllegalArgumentException ex) { throw new IllegalArgumentException("Enter a valid payment UUID."); }
    }

    private void create() {
        BigDecimal value;
        try { value = new BigDecimal(amount.getText().trim()); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException("Amount must be a number, e.g. 100.00."); }
        if (value.compareTo(new BigDecimal("0.01")) < 0) throw new IllegalArgumentException("Amount must be at least 0.01.");
        if (currency.getText().isBlank() || key.getText().isBlank()) throw new IllegalArgumentException("Currency and idempotency key are required.");
        String body = json.createObjectNode().put("amount", value).put("currency", currency.getText().trim()).toString();
        lastCreate = request("POST", "/payments", body, key.getText().trim());
        append("REQUEST BODY: " + body);
        send(lastCreate);
    }

    private void paymentAction(String suffix) {
        send(request(suffix.isEmpty() ? "GET" : "POST", "/payments/" + selectedId() + suffix, null, null));
    }

    private HttpRequest request(String method, String path, String body, String idempotencyKey) {
        URI root = URI.create(base.getText().trim());
        if (!("http".equals(root.getScheme()) || "https".equals(root.getScheme())) || root.getHost() == null
                || root.getRawQuery() != null || root.getFragment() != null || root.getUserInfo() != null) {
            throw new IllegalArgumentException("API must be an http(s) URL without credentials, query, or fragment.");
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(root.toString().replaceAll("/+$", "") + path))
                .timeout(Duration.ofSeconds(15)).header("Accept", "application/json");
        if (idempotencyKey != null) builder.header("Idempotency-Key", idempotencyKey);
        if (body != null) builder.header("Content-Type", "application/json");
        return builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private void send(HttpRequest request) {
        setBusy(true);
        append("REQUEST: " + request.method() + " " + request.uri()
                + request.headers().firstValue("Idempotency-Key").map(k -> " | key=" + k).orElse(""));
        long start = System.nanoTime();
        // Only network work runs off the event dispatch thread. All UI updates happen in done().
        new SwingWorker<HttpResponse<String>, Void>() {
            protected HttpResponse<String> doInBackground() throws Exception {
                return client.send(request, HttpResponse.BodyHandlers.ofString());
            }

            protected void done() {
                try {
                    HttpResponse<String> response = get();
                    connection.setText("Backend: responded HTTP " + response.statusCode());
                    append("RESPONSE: HTTP " + response.statusCode() + " (" + (System.nanoTime() - start) / 1_000_000 + " ms)");
                    String body = response.body();
                    JsonNode node = null;
                    try { node = json.readTree(body); }
                    catch (Exception ignored) { /* Processor mode endpoint returns plain text. */ }
                    append(node == null ? body : node.toPrettyString());
                    if (response.statusCode() >= 200 && response.statusCode() < 300 && node != null) {
                        if (request.uri().getPath().contains("/payments") && node.hasNonNull("id") && node.hasNonNull("status")) {
                            paymentId.setText(node.get("id").asText());
                            status.setText("Payment: " + node.get("status").asText() + " | observed " + LocalTime.now().withNano(0));
                        }
                        if (request.uri().getPath().endsWith("/actuator/health")) connection.setText("Backend health: " + node.path("status").asText("unknown"));
                    }
                    if (response.statusCode() == 404 && request.uri().getPath().contains("/processor/mode/")) {
                        append("HINT: processor controls require Spring's dev profile.");
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    append("CLIENT: request interrupted.");
                } catch (ExecutionException ex) {
                    connection.setText("Backend: request could not complete");
                    append("TRANSPORT ERROR: " + ex.getCause() + ". No payment outcome inferred. Look up the payment, or repeat creation with the same key.");
                } finally {
                    setBusy(false);
                }
            }
        }.execute();
    }

    private void setBusy(boolean value) {
        busy = value;
        controls.forEach(control -> control.setEnabled(!value));
    }

    private void append(String message) {
        log.append(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "  " + message + "\n");
        if (log.getDocument().getLength() > 100_000) log.replaceRange("", 0, log.getDocument().getLength() - 80_000);
        log.setCaretPosition(log.getDocument().getLength());
    }
}
