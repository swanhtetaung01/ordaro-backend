# trillopos-backend

Multi-tenant POS and inventory API for TrilloPOS. Spring Boot 4.1 · Java 25 · Hibernate ORM 7.4 ·
PostgreSQL · Flyway. Spec: `../docs/domain-model.md`. Working memory: `../TrilloPOS-Vault/`.

Build steps 1–2 are in place: accounts, organizations, locations (`STORE` · `WAREHOUSE`),
memberships and invitations, token auth, catalog CRUD (step 1); the append-only stock ledger,
weighted-average costing, stock documents and the balance rebuild job (step 2); registers with
PIN login, cashier shifts and the idempotent sale-completion transaction (step 3).

## Database

Two roles, created once by `db/bootstrap.sql` run as `postgres` (outside Flyway):

| Role | Used by | Can |
|---|---|---|
| `trillopos_owner` | Flyway (`spring.flyway.*`) | owns every table in schema `trillopos` |
| `trillopos_app` | the application pool (`spring.datasource.*`) | read/write rows; owns nothing, no `BYPASSRLS` |

Use a session-mode connection (port 5432) for both — never the transaction pooler (6543).

## Run locally

Needs Docker Desktop and Java 25. Once: `cp .env.example .env`, then fill in the passwords.

```powershell
docker compose up -d          # PostgreSQL 17 on localhost:5432; the bootstrap runs on a new volume
.\mvnw.cmd spring-boot:run    # API on http://localhost:8080 (Git Bash: ./mvnw spring-boot:run)
```

`spring-boot:run` reads `.env` itself (configured in `pom.xml`), so no shell has to load it first;
real environment variables still win, and tests and the jar never read it. Then start the web app
(`npm run dev` in `trillopos-web`) and open http://localhost:3000.

Local accounts live only in the Docker database — a production account does not exist here — and
sign-up needs no code, because `TRILLOPOS_SIGNUP_CODE` is blank. For a ready-made shop, start once
with the demo seed, then sign in as `09000000001` / `demo-password` (Shwe Demo Shop, three products
with stock). In PowerShell the quotes are required:

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=seed"
```

`docker compose down -v` wipes the local database.

## Tests

`./mvnw test` — no Docker needed: the tests start an embedded PostgreSQL 17 and run the real
`db/bootstrap.sql`, so they exercise the same role split as production.

## Vertical slice (step 4)

`scripts/vertical-slice.sh` drives a running app with curl: sign-up, login → token → request,
create product → stock in → sell → retry the same completion → the balance drops once and one
receipt exists; a wrong-tenant request; a PIN register session; shift close; refresh rotation.
It needs only curl and python. Run the app against any PostgreSQL that `db/bootstrap.sql` has
been applied to, then:

```
BASE=http://localhost:8080 scripts/vertical-slice.sh
```

## API (steps 1–3)

| Method | Path | Who |
|---|---|---|
| POST | `/auth/signup`, `/auth/login`, `/auth/refresh`, `/auth/logout` | anyone |
| GET | `/auth/memberships` · POST `/auth/switch` | picker or tenant token |
| POST | `/auth/invitations/accept` (code) · `/auth/invitations/{id}/accept` | picker or tenant token |
| GET/PATCH | `/organization` | tenant (PATCH: owner) |
| GET/POST/PATCH | `/locations` | tenant (writes: owner) |
| GET/POST/PATCH | `/memberships` | owner |
| GET/POST/PATCH | `/categories`, `/suppliers` | tenant (writes: owner, stock manager) |
| GET/POST/PATCH/DELETE | `/products`, `/products/{id}/barcodes`, `/products/{id}/locations/{locationId}` | tenant (writes: owner, stock manager) |
| GET/POST/PUT | `/stock-documents`, `/stock-documents/{id}/post`, `/stock-documents/{id}/void` | owner, stock manager |
| GET | `/stock-balances` | tenant (average cost: owner, stock manager) |
| GET | `/stock-movements?locationId=&productId=` | owner, stock manager |
| GET · POST | `/inventory/verification` · `/inventory/rebuild` | owner |
| GET · POST | `/auth/register/staff` · `/auth/pin` (header `X-Register-Device`) | a bound register |
| GET/POST | `/registers`, `/registers/{id}/revoke` | owner, account session only |
| POST/GET | `/shifts`, `/shifts/current?locationId=`, `/shifts/{id}/close` | owner, stock manager, cashier |
| POST | `/sales/checkout` (idempotent; `Idempotent-Replay` header) | owner, stock manager, cashier |
| POST/PUT/GET | `/sales` (park a cart), `/sales/{id}`, `/sales/{id}/hold`, `/sales/{id}/complete`, `/sales/{id}/void` | owner, stock manager, cashier |
| GET | `/.well-known/jwks.json`, `/actuator/health` | anyone |
