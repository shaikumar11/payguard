# PayGuard

![CI](https://github.com/shaikumar11/payguard/actions/workflows/ci.yml/badge.svg)
![Java](https://img.shields.io/badge/Java-21-orange)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue)

A payment transfer service with a built-in risk engine, written in Java 21 on PostgreSQL. It shows how a money-movement service stays correct under retries and heavy concurrency.

- **Idempotent transfers.** A unique `Idempotency-Key` makes retries safe. The same key and body replays the stored result. The same key with a different body is rejected with 422.
- **Double entry ledger.** Every completed transfer writes two ledger entries. Tests assert the ledger always sums to zero and money is conserved.
- **No overdraft, no deadlock.** Both accounts are locked with `SELECT ... FOR UPDATE` in ascending id order, with a `CHECK (balance >= 0)` constraint as a backstop.
- **Risk engine.** A sealed `Rule` interface with four rules: high amount, velocity, country hop and new device. Scores combine into approve, review or decline.
- **Review queue.** Medium risk payments are held, then approved or rejected by a reviewer. Approval re-checks the balance.
- **JDK only HTTP layer.** `com.sun.net.httpserver` with one virtual thread per request. The only runtime dependency is the PostgreSQL JDBC driver.

## Run

Requires Java 21, Maven and Docker.

```bash
docker compose up -d db
export PG_URL=jdbc:postgresql://localhost:5432/payguard PG_USER=payguard PG_PASSWORD=payguard
mvn test                       # 27 tests, PostgreSQL integration tests included
mvn -q package && java -jar target/payguard-1.0.0.jar
```

Or run everything with `docker compose up --build`. Demo accounts 1, 2 and 3 are seeded with 50,000.00 GBP each.

On Windows PowerShell set the variables with `$env:PG_URL="jdbc:postgresql://localhost:5432/payguard"`, `$env:PG_USER="payguard"`, `$env:PG_PASSWORD="payguard"`.

## API

| Method and path | Purpose |
|---|---|
| `POST /transfers` with `Idempotency-Key` header | Create a transfer. 201 new, 200 replay, 422 key reused with a different body |
| `GET /payments/{id}` | Outcome, risk score and reasons |
| `GET /accounts/{id}` | Balance in minor units |
| `GET /review` | Payments held for review |
| `POST /review/{id}/approve` and `/reject` | Resolve a held payment |
| `GET /health` | Liveness |

```bash
curl -X POST localhost:8080/transfers -H "Idempotency-Key: demo-1" \
  -d '{"fromAccount":1,"toAccount":2,"amountMinor":2500,"currency":"GBP","country":"GB","deviceId":"phone-1"}'
```

```json
{"paymentId":1,"outcome":"COMPLETED","riskScore":0,"reasons":[],"replayed":false}
```

A risky payment (600.00 GBP from an unseen device) comes back held:

```json
{"paymentId":3,"outcome":"HELD_FOR_REVIEW","riskScore":55,"reasons":["HIGH_AMOUNT(+25): amount 600000 >= 500000","NEW_DEVICE(+30): unseen device new-laptop"],"replayed":false}
```

## Risk rules

| Rule | Points | Triggers when |
|---|---|---|
| HIGH_AMOUNT | 25 or 50 | amount at least 5,000.00 or 10,000.00 |
| VELOCITY | 40 | 5 or more earlier attempts in 60 seconds |
| COUNTRY_HOP | 45 | different country from the previous payment within 10 minutes |
| NEW_DEVICE | 30 | unseen device and amount at least 1,000.00 |

Score 40 or more is held for review. Score 80 or more is declined. Scores are capped at 100.

## Design notes

- **Lock before insert.** The payments insert takes foreign key locks on both accounts in request order. Locking the accounts first, in id order, is what prevents opposite direction transfers from deadlocking. An earlier version inserted first and the concurrency test caught the deadlock.
- **Duplicates wait, then replay.** A concurrent duplicate waits on the account lock, hits the unique key conflict, and returns the first result. A test sends 25 simultaneous requests with one key and asserts exactly one payment.
- **Money is a `long` of minor units.** No floating point.
- **Testable time.** Risk rules take a `Clock`, so velocity and country hop tests move time forward instead of sleeping.

## Tests

- `RiskEngineTest`: every rule boundary, score combination and cap.
- `MiniJsonTest`: parser and writer edge cases.
- `TransferServiceTest`: ledger, replay, conflicting key, insufficient funds, review flow, velocity, country hop, plus concurrency tests (120 parallel transfers on one account, 25 duplicate keys, 100 opposite direction transfers).
- `ApiTest`: status codes over real HTTP.

Integration tests skip automatically when `PG_URL` is not set.

## Limitations

- Single currency per transfer, no FX.
- Simple blocking connection pool and a minimal JSON parser, to keep dependencies at one.
- Risk rules are fixed in code, not configurable at runtime.
