package com.payguard.api;

import com.payguard.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/** HTTP API on the JDK server, one virtual thread per request. */
public final class Server {
    private final HttpServer http;
    private final TransferService service;

    public Server(TransferService service, int port) throws IOException {
        this.service = service;
        this.http = HttpServer.create(new InetSocketAddress(port), 0);
        http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        http.createContext("/", this::route);
    }

    public void start() { http.start(); }
    public void stop() { http.stop(0); }
    public int port() { return http.getAddress().getPort(); }

    private void route(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod();
            String[] p = ex.getRequestURI().getPath().split("/");   // "", seg1, seg2, ...
            String seg1 = p.length > 1 ? p[1] : "";
            if (method.equals("GET") && seg1.equals("health")) send(ex, 200, "{\"status\":\"ok\"}");
            else if (method.equals("POST") && seg1.equals("transfers") && p.length == 2) postTransfer(ex);
            else if (method.equals("GET") && seg1.equals("payments") && p.length == 3) send(ex, 200, json(service.find(id(p[2]))));
            else if (method.equals("GET") && seg1.equals("accounts") && p.length == 3)
                send(ex, 200, "{\"accountId\":" + id(p[2]) + ",\"balanceMinor\":" + service.balance(id(p[2])) + "}");
            else if (method.equals("GET") && seg1.equals("review") && p.length == 2)
                send(ex, 200, "{\"held\":" + service.heldForReview() + "}");
            else if (method.equals("POST") && seg1.equals("review") && p.length == 4 && p[3].equals("approve"))
                send(ex, 200, json(service.approve(id(p[2]))));
            else if (method.equals("POST") && seg1.equals("review") && p.length == 4 && p[3].equals("reject"))
                send(ex, 200, json(service.reject(id(p[2]))));
            else send(ex, 404, error("no such route"));
        } catch (PaymentException.NotFound e) {
            send(ex, 404, error(e.getMessage()));
        } catch (PaymentException.IdempotencyConflict e) {
            send(ex, 422, error(e.getMessage()));
        } catch (PaymentException.InvalidState e) {
            send(ex, 409, error(e.getMessage()));
        } catch (IllegalArgumentException e) {
            send(ex, 400, error(e.getMessage()));
        } catch (RuntimeException e) {
            send(ex, 500, error("internal error"));
        } finally {
            ex.close();
        }
    }

    private void postTransfer(HttpExchange ex) throws IOException {
        String key = ex.getRequestHeaders().getFirst("Idempotency-Key");
        Map<String, String> b = MiniJson.parseFlat(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        Transfer t = new Transfer(key, num(b, "fromAccount"), num(b, "toAccount"), num(b, "amountMinor"),
                text(b, "currency"), text(b, "country"), text(b, "deviceId"));
        TransferResult r = service.transfer(t);
        send(ex, r.replayed() ? 200 : 201, json(r));
    }

    private static String text(Map<String, String> b, String k) {
        String v = b.get(k);
        if (v == null) throw new IllegalArgumentException("missing field " + k);
        return v;
    }

    private static long num(Map<String, String> b, String k) {
        try { return Long.parseLong(text(b, k)); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("field " + k + " must be an integer"); }
    }

    private static long id(String s) {
        try { return Long.parseLong(s); } catch (NumberFormatException e) { throw new IllegalArgumentException("invalid id"); }
    }

    static String json(TransferResult r) {
        return "{\"paymentId\":" + r.paymentId() + ",\"outcome\":\"" + r.outcome() + "\",\"riskScore\":" + r.riskScore()
                + ",\"reasons\":" + MiniJson.array(r.reasons()) + ",\"replayed\":" + r.replayed() + "}";
    }

    private static String error(String m) { return "{\"error\":" + MiniJson.quote(m) + "}"; }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
    }
}
