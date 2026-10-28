package com.payguard;

import com.payguard.api.Server;
import com.payguard.db.Database;
import com.payguard.risk.RiskEngine;
import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;

import static com.payguard.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class ApiTest {
    private Database db;
    private Server server;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach void setUp() throws Exception {
        db = freshDb(10);
        account(db, 1, 10_000_000);
        account(db, 2, 0);
        server = new Server(new TransferService(db, RiskEngine.defaults(), Clock.systemUTC()), 0);
        server.start();
    }

    @AfterEach void tearDown() {
        if (server != null) server.stop();
        if (db != null) db.close();
    }

    private HttpResponse<String> post(String path, String key, String body) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + path))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (key != null) b.header("Idempotency-Key", key);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static final String BODY =
            "{\"fromAccount\":1,\"toAccount\":2,\"amountMinor\":2500,\"currency\":\"GBP\",\"country\":\"GB\",\"deviceId\":\"d1\"}";

    @Test void healthAndTransferFlow() throws Exception {
        assertEquals(200, get("/health").statusCode());
        var created = post("/transfers", "api-1", BODY);
        assertEquals(201, created.statusCode());
        assertTrue(created.body().contains("\"outcome\":\"COMPLETED\""));
        var replay = post("/transfers", "api-1", BODY);
        assertEquals(200, replay.statusCode());
        assertTrue(replay.body().contains("\"replayed\":true"));
        assertTrue(get("/accounts/2").body().contains("\"balanceMinor\":2500"));
        assertEquals(200, get("/payments/1").statusCode());
    }

    @Test void errorsMapToStatusCodes() throws Exception {
        assertEquals(400, post("/transfers", null, BODY).statusCode());                 // missing key
        assertEquals(400, post("/transfers", "x", "{\"fromAccount\":1}").statusCode()); // missing fields
        assertEquals(400, post("/transfers", "x", "not json").statusCode());
        post("/transfers", "same", BODY);
        assertEquals(422, post("/transfers", "same", BODY.replace("2500", "2600")).statusCode());
        assertEquals(404, get("/payments/999").statusCode());
        assertEquals(404, get("/nope").statusCode());
    }

    @Test void reviewQueueOverHttp() throws Exception {
        String risky = BODY.replace("2500", "600000").replace("d1", "brand-new");
        var held = post("/transfers", "r1", risky);
        assertTrue(held.body().contains("HELD_FOR_REVIEW"));
        assertTrue(get("/review").body().contains("[1]"));
        assertEquals(200, post("/review/1/approve", null, "").statusCode());
        assertEquals(409, post("/review/1/approve", null, "").statusCode());
    }
}
