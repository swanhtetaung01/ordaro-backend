# ordaro-backend

Multi-tenant POS and inventory API for Ordaro. Spring Boot 4.1 · Java 25 · Hibernate ORM 7.4 ·
PostgreSQL · Flyway. Spec: `../docs/domain-model.md`. Working memory: `../Ordaro-Vault/`.

Build step 1 (org + catalog + identity) is in place: accounts, organizations, locations
(`STORE` · `WAREHOUSE`), memberships and invitations, token auth, and plain catalog CRUD.

## Database

Two roles, created once by `db/bootstrap.sql` run as `postgres` (outside Flyway):

| Role | Used by | Can |
|---|---|---|
| `ordaro_owner` | Flyway (`spring.flyway.*`) | owns every table in schema `ordaro` |
| `ordaro_app` | the application pool (`spring.datasource.*`) | read/write rows; owns nothing, no `BYPASSRLS` |

Use a session-mode connection (port 5432) for both — never the transaction pooler (6543).

Local database with Docker: `cp .env.example .env`, fill in the passwords, `docker compose up -d`.
Seed a demo shop: run with `--spring.profiles.active=seed` (phone `+959000000001`).

## Tests

`./mvnw test` — no Docker needed: the tests start an embedded PostgreSQL 17 and run the real
`db/bootstrap.sql`, so they exercise the same role split as production.

## API (step 1)

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
| GET | `/.well-known/jwks.json`, `/actuator/health` | anyone |
