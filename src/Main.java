package stocknotifier;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

public class Main {
    // Telegram bot token and chat ID
    private static final String TOKEN   = "8208462630:AAEIOlb5uf5e3I4Jj7RlHEBIx55Ld9GAFNY";
    private static final String CHAT_ID = "7754440397";
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    // Persistence
    private static final String DATA_FILE = "data.json";
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    // Thread‑safe lists
    private static final List<Product> PRODUCTS = new CopyOnWriteArrayList<>();
    private static final List<Order>   ORDERS   = new CopyOnWriteArrayList<>();

    public static void main(String[] args) {
        // 0) Load saved products & orders (if any)
        loadData();

        // 1) Schedule automated check
        ScheduledExecutorService scheduler =
                Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(
                Main::checkAllAndNotify, 0, 2, TimeUnit.MINUTES
        );

        // 2) Interactive console
        Scanner sc = new Scanner(System.in);
        while (true) {
            System.out.print(
                    "Command (newProd <url> | newOrder <sipNo> <email> | check | clear | quit): "
            );
            String line = sc.nextLine().trim();
            String[] parts = line.split("\\s+");
            if (parts[0].equalsIgnoreCase("quit")) {
                scheduler.shutdownNow();
                break;
            }

            try {
                switch (parts[0].toLowerCase()) {
                    case "newprod" -> {
                        System.out.println("\n🔄 Adding new product. Fetching product info...");
                        try {
                            Product p = new Product(parts[1]);
                            PRODUCTS.add(p);
                            saveData();
                            System.out.println("✅ Product added:");
                            System.out.println("   Name : " + p.name);
                            System.out.println("   Link : " + p.link + "\n");
                        } catch (Exception e) {
                            System.err.println("❌ Failed to add product: " + e.getMessage());
                        }
                    }
                    case "neworder" -> {
                        System.out.println("\n🔄 Adding new order. Fetching order status...");
                        try {
                            Order o = new Order(parts[1], parts[2]);
                            ORDERS.add(o);
                            saveData();
                            System.out.println("✅ Order added:");
                            System.out.println("   Order #: " + o.sipNo);
                            System.out.println("   Status : " + o.status + "\n");
                        } catch (Exception e) {
                            System.err.println("❌ Failed to add order: " + e.getMessage());
                        }
                    }
                    case "check" -> {
                        // manual dump
                        checkAllAndNotify();
                    }
                    case "clear" -> {
                        for (int i = 0; i < 100; i++) System.out.println();
                    }
                    default -> System.out.println("Unknown command: " + parts[0]);
                }
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
            }
        }
    }

    // --- persistence helpers ---
    private static void loadData() {
        File f = new File(DATA_FILE);
        if (!f.exists()) return;
        try {
            DataStore ds = MAPPER.readValue(f, DataStore.class);
            for (String link : ds.productLinks) {
                PRODUCTS.add(new Product(link));
            }
            for (OrderInfo oi : ds.orders) {
                ORDERS.add(new Order(oi.sipNo, oi.email));
            }
            System.out.printf(
                    "Loaded %d products and %d orders from %s%n",
                    PRODUCTS.size(), ORDERS.size(), DATA_FILE
            );
        } catch (Exception e) {
            System.err.println("Failed to load data: " + e.getMessage());
        }
    }

    private static void saveData() {
        try {
            DataStore ds = new DataStore();
            for (Product p : PRODUCTS) ds.productLinks.add(p.link);
            for (Order o : ORDERS)   ds.orders.add(new OrderInfo(o.sipNo, o.email));
            MAPPER.writeValue(new File(DATA_FILE), ds);
        } catch (Exception e) {
            System.err.println("Failed to save data: " + e.getMessage());
        }
    }

    // --- automated check & notify ---
    private static void checkAllAndNotify() {
        System.out.println("\n===== 🔁 AUTOMATED STATUS CHECK =====");

        // Products
        if (PRODUCTS.isEmpty()) {
            System.out.println("No products to check.");
        } else {
            System.out.println("\n--- 🛒 PRODUCT STATUS ---");
            for (Product p : PRODUCTS) {
                try {
                    String old = p.stockStatus;
                    p.updateStockStatus();
                    System.out.printf("[Product] %s%n   Stock: %s%n", p.name, p.stockStatus);
                    if (!old.equals(p.stockStatus) && "In stock".equals(p.stockStatus)) {
                        sendTelegram("Product now in stock: " + p.name + "\n" + p.link);
                        System.out.println("   🔔 Notification sent!");
                    }
                } catch (Exception e) {
                    System.err.println("   ⚠️ Error: " + e.getMessage());
                }
            }
        }

        // Orders
        if (ORDERS.isEmpty()) {
            System.out.println("\nNo orders to check.");
        } else {
            System.out.println("\n--- 📦 ORDER STATUS ---");
            for (Order o : ORDERS) {
                try {
                    String old = o.status;
                    o.updateStatus();
                    System.out.printf("[Order] #%s%n   Status: %s%n", o.sipNo, o.status);
                    if (!old.equals(o.status)) {
                        sendTelegram("Order " + o.sipNo + " status changed:\n" + o.status);
                        System.out.println("   🔔 Notification sent!");
                    }
                } catch (Exception e) {
                    System.err.println("   ⚠️ Error: " + e.getMessage());
                }
            }
        }

        System.out.println("\n===== ✅ STATUS CHECK COMPLETE =====\n");
    }

    // --- Telegram notifier ---
    private static void sendTelegram(String msg) throws Exception {
        String enc = URLEncoder.encode(msg, StandardCharsets.UTF_8);
        String url = String.format(
                "https://api.telegram.org/bot%s/sendMessage?chat_id=%s&text=%s",
                TOKEN, CHAT_ID, enc
        );
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET().build();
        CLIENT.send(req, HttpResponse.BodyHandlers.discarding());
    }

    // --- JSON data‐transfer objects ---
    static class DataStore {
        public List<String> productLinks = new ArrayList<>();
        public List<OrderInfo> orders     = new ArrayList<>();
    }

    static class OrderInfo {
        public String sipNo;
        public String email;
        public OrderInfo() {}
        public OrderInfo(String sipNo, String email) {
            this.sipNo = sipNo; this.email = email;
        }
    }

    // --- Product & Order classes (unchanged) ---
    static class Product {
        final String link, name;
        String stockStatus = "Unknown";
        Product(String link) throws Exception {
            this.link = link;
            this.name = fetchName();
        }
        void updateStockStatus() throws Exception {
            String html = CLIENT.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(link))
                            .header("User-Agent","Mozilla/5.0")
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            ).body();
            if (html.contains(">Tükendi<"))      stockStatus = "Out of stock";
            else if (html.contains(">Sepete Ekle<")) stockStatus = "In stock";
            else stockStatus = "Unknown";
        }
        String fetchName() throws Exception {
            // 1) Load main page
            HttpRequest reqMain = HttpRequest.newBuilder()
                    .uri(URI.create(link))
                    .header("User-Agent", "Mozilla/5.0")
                    .GET().build();
            String mainHtml = CLIENT.send(reqMain, HttpResponse.BodyHandlers.ofString()).body();

            // 2) Extract detailPartialPage URL
            Pattern pUrl = Pattern.compile("id=\"detailPartialPage\"\\s+data-url=\"(.*?)\"");
            Matcher mUrl = pUrl.matcher(mainHtml);
            String detailUrl = mUrl.find()
                    ? (mUrl.group(1).startsWith("http")
                    ? mUrl.group(1)
                    : "https://www.sitenizin-domaini.com" + mUrl.group(1))
                    : link;

            // 3) Load the partial-detail page
            HttpRequest reqDetail = HttpRequest.newBuilder()
                    .uri(URI.create(detailUrl))
                    .header("User-Agent", "Mozilla/5.0")
                    .GET().build();
            String detailHtml = CLIENT.send(reqDetail, HttpResponse.BodyHandlers.ofString()).body();

            // 4) Parse the <h1> title
            Pattern pName = Pattern.compile(
                    "<h1[^>]*class=\"product-list__product-name\"[^>]*>\\s*(.*?)\\s*</h1>",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL
            );
            Matcher mName = pName.matcher(detailHtml);
            return mName.find()
                    ? mName.group(1).trim()
                    : "Name not found";
        }
    }

    static class Order {
        final String sipNo, email;
        String status = "";
        Order(String sipNo, String email) throws Exception {
            this.sipNo = sipNo; this.email = email;
            updateStatus();
        }
        void updateStatus() throws Exception {
            String body = String.format(
                    "Item1.SipNo=%s&Item1.Email=%s",
                    URLEncoder.encode(sipNo, StandardCharsets.UTF_8),
                    URLEncoder.encode(email,  StandardCharsets.UTF_8)
            );
            String html = CLIENT.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("https://www.vatanbilgisayar.com/siparistakip"))
                            .header("Content-Type","application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            ).body();
            Matcher m = Pattern.compile(
                    "<span class=\"panel__cell\">(.*?)</span>"
            ).matcher(html);
            int i = 0;
            while (m.find()) {
                if (++i == 3) { status = htmlDecode(m.group(1).trim()); return; }
            }
            status = "Unknown";
        }
        private String htmlDecode(String t) {
            return t.replace("&#x15F;","ş")
                    .replace("&#x131;","ı");
        }
    }
}
