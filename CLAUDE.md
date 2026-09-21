# ordaro-backend

## Read the vault before exploring the code

Working memory for this project lives in the **`Ordaro-Vault`** repo, checked out beside this one:

```
../Ordaro-Vault/now.md
```

**Read `now.md` first** — current HEADs, what just landed, open items, traps. Do not read
`index.md` or `history.md` end to end; grep them for a heading and read that section.

Write back as you go: a fact in `now.md` that stops being true is edited in place; something that
ships, is decided, or breaks and is fixed is appended to `history.md`; long-term state changes go
in `index.md`. `git pull` before writing to the vault. No secrets in the vault.

The approved spec is `../docs/domain-model.md`. The company and product are **Ordaro**.

## Build

JDK 25 (Temurin). Tests need neither Docker nor a local PostgreSQL — they start an embedded
PostgreSQL 17 and run the real `db/bootstrap.sql`.

```
./mvnw test
```

If a shell still resolves Java 21, set `JAVA_HOME` to the JDK 25 folder for the command.
