# Putting Ordaro online for the pilot shop

One server (EC2) runs three containers: the API, the web app, and Caddy, which gets the HTTPS
certificate by itself. The database is RDS PostgreSQL, reachable only from that server. Your
sister's browser only ever talks to Caddy.

```
phone / laptop ──HTTPS──▶ EC2: Caddy ──▶ web (Next.js) ──▶ backend (Spring) ──▶ RDS PostgreSQL
```

About **$38 a month**, paid from your AWS credits. Budget two hours the first time. Everything
below was rehearsed end to end on a local copy of this setup before it was written down.

Region for everything: **Asia Pacific (Singapore) ap-southeast-1**. Check the top-right of the AWS
console before every step.

---

## 0. Before you start (10 minutes)

1. **Budget alert.** Billing and Cost Management → Budgets → Create budget → *Monthly cost
   budget* → amount **15 USD** → your email. A forgotten resource can never surprise you. This
   also earns one of the $20 "Explore AWS" credits.
2. **The address.** With no domain yet, the address will be your server's IP written with dashes
   plus `.sslip.io`, for example `13-229-10-20.sslip.io`. It works with HTTPS and costs nothing.
   A real domain can replace it later without touching anything else.
3. **A sign-up code.** A word only you, your sister and her staff will know, for example
   `longyi-2026`. Without it, nobody else can create a shop on your server.

## 1. Two security groups (the firewalls)

EC2 → Network & Security → **Security Groups** → Create security group. Twice:

| Name | Inbound rules |
|---|---|
| `ordaro-web` | SSH, port 22, source **My IP** · HTTP, port 80, source Anywhere-IPv4 · HTTPS, port 443, source Anywhere-IPv4 |
| `ordaro-db` | PostgreSQL, port 5432, source **Custom → the `ordaro-web` group** (start typing `sg-` and pick it) |

Leave the outbound rules as they are. The database accepts connections only from the web server's
group, never from the internet.

## 2. The database (RDS)

RDS → Databases → **Create database**:

| Setting | Value |
|---|---|
| Creation method | Standard create |
| Engine | PostgreSQL, the newest **17.x** |
| Templates | Free tier if offered, otherwise Dev/Test |
| Availability | Single-AZ DB instance |
| DB instance identifier | `ordaro` |
| Master username | `postgres` |
| Credentials management | Self managed, **Auto generate password** |
| Instance class | Burstable → **db.t4g.micro** |
| Storage | gp3, **20 GiB**, storage autoscaling on, maximum **100 GiB** |
| Compute resource | Don't connect to an EC2 compute resource |
| VPC | Default VPC |
| **Public access** | **No** |
| VPC security group | Choose existing → **`ordaro-db`** (remove `default`) |
| Database authentication | Password authentication |
| Monitoring | Performance Insights on (7 days, free); Enhanced Monitoring off |
| Additional configuration → **Initial database name** | `ordaro` |
| Backup retention | 7 days |
| Encryption | on |
| **Deletion protection** | **on** |

Create it. On the next screen click **View credential details** and **save the master password in
a password manager**. AWS shows it only this once. Creation takes about 10 minutes. When the status
is *Available*, open the database and copy the **Endpoint**, which looks like
`ordaro.abc123xyz.ap-southeast-1.rds.amazonaws.com`.

## 3. The server (EC2)

EC2 → Instances → **Launch instances**:

| Setting | Value |
|---|---|
| Name | `ordaro` |
| Image | **Ubuntu Server 24.04 LTS**, architecture **64-bit (Arm)** |
| Instance type | **t4g.small** |
| Key pair | Create new key pair → `ordaro-key`, RSA, `.pem` → it downloads; keep that file safe |
| Network settings → Firewall | Select existing security group → **`ordaro-web`** |
| Storage | **20 GiB gp3** |

Launch. Then give it a fixed address: EC2 → **Elastic IPs** → Allocate Elastic IP address →
Allocate → select it → Actions → **Associate** → instance `ordaro` → Associate. Note the IP, for
example `13.229.10.20`. Your address is then **`13-229-10-20.sslip.io`**.

## 4. Open a terminal on the server

The easiest way: EC2 → Instances → select `ordaro` → **Connect** → *EC2 Instance Connect* →
Connect. A terminal opens in the browser.

Or from Windows PowerShell:

```powershell
ssh -i C:\path\to\ordaro-key.pem ubuntu@13.229.10.20
```

If it says the key's permissions are too open, run
`icacls C:\path\to\ordaro-key.pem /inheritance:r /grant:r "$($env:USERNAME):R"` once, then retry.

## 5. Get the code and prepare the server (10 minutes)

On the server:

```bash
mkdir -p ~/ordaro && cd ~/ordaro
git clone --branch main https://github.com/swanhtetaung01/ordaro-backend.git
git clone --branch main https://github.com/swanhtetaung01/ordaro-web.git
bash ordaro-backend/deploy/setup-server.sh
exit
```

Connect again, because Docker needs a fresh login to work without `sudo`.

## 6. Create the database roles (once)

```bash
cd ~/ordaro/ordaro-backend/deploy
./bootstrap-database.sh
```

It asks for the **endpoint** from step 2, the master username (`postgres`, just press Enter), the
**master password**, and the database name (press Enter). It creates the two roles the app uses
and writes their new random passwords into `deploy/.env`. Nobody ever needs to type those.

## 7. Two settings

```bash
nano .env
```

Change two lines, then save with Ctrl+O, Enter, and exit with Ctrl+X:

```
ORDARO_DOMAIN=13-229-10-20.sslip.io
ORDARO_SIGNUP_CODE=longyi-2026
```

## 8. Start it (5–10 minutes the first time)

```bash
./deploy.sh
```

It builds everything, starts it, waits until the API is healthy, and ends with
`== live at https://13-229-10-20.sslip.io`. Open that address on your phone. You should see the
Ordaro sign-in page with a padlock in the address bar.
`https://13-229-10-20.sslip.io/api/health` should answer `{"status":"UP","backend":"UP"}`.

## 9. A backup every night

```bash
crontab -e
```

Choose nano if it asks, add this line at the end, then save:

```
30 2 * * * $HOME/ordaro/ordaro-backend/deploy/backup.sh >> $HOME/ordaro-backups.log 2>&1
```

Every night at 02:30 Yangon time, a full copy of the database goes to `~/ordaro-backups`, and
copies older than 14 days are deleted. RDS separately keeps its own backups, which can restore any
minute of the last 7 days.

## 10. The acceptance check (5 minutes, before your sister signs up)

```bash
cd ~/ordaro/ordaro-backend
SIGNUP_CODE=longyi-2026 BASE=http://127.0.0.1:8080 bash scripts/vertical-slice.sh
```

It must end with `Vertical slice passed: 13 steps.` It creates two small test shops, *Slice Mart*
and *Other Shop*. Nobody else can see them.

## 11. Your sister's first steps

Give her the address and the sign-up code, and the shop guide (`docs/pilot-shop-guide.md`). Then:

1. **Create a business**: her name, the shop's name, her phone (`09…` is fine), a password, and
   the sign-up code.
2. **Settings**: Business type *Online shop*; **Tax % = 0** unless she really charges commercial
   tax (the default is 5, which would understate her profit); for cash on delivery, *New customers'
   credit limit* around her largest usual parcel, and *Days to pay* as long as her courier takes to
   pay her.
3. **Products** → Add product, with the stock she has now as opening stock.
4. **New sale** → *Online order* for every Facebook, Viber or phone order.

---

## Everyday operations

All from `~/ordaro/ordaro-backend/deploy` on the server.

| When | Do |
|---|---|
| There is new code on `main` | `./deploy.sh` (it backs up first; the database updates itself) |
| She forgot her password | `./admin.sh reset-password 09xxxxxxxxx`, tell her the printed password, and she changes it under **Account** |
| Too many wrong passwords ("wait 15 minutes") | `./admin.sh unlock 09xxxxxxxxx`, or just wait |
| A cashier forgot their PIN | Your sister does it herself: **Staff → Set PIN** |
| Which shops exist | `./admin.sh shops` |
| What is running | `docker compose ps` |
| What it is saying | `docker compose logs --tail 100 backend` (or `web`, `caddy`) |
| Restart everything | `docker compose restart` |
| The server rebooted | Nothing to do: the containers start by themselves |

**Uptime alert (free, 5 minutes):** create an account at uptimerobot.com → New monitor → HTTP(s) →
URL `https://13-229-10-20.sslip.io/api/health`, every 5 minutes. You get an email or Telegram
message if the site goes down.

## When something goes wrong

- **The site does not open.** Run `docker compose ps`: all three should say *Up* (backend and web
  also *healthy*). If Caddy is up but the browser shows a certificate error, check
  `docker compose logs caddy`. Usually `ORDARO_DOMAIN` does not match the Elastic IP, or port 80
  is not open in the `ordaro-web` security group.
- **The API does not start.** `docker compose logs --tail 200 backend`. A wrong password in `.env`
  or the database's security group are the usual causes.
- **Undo a bad deploy.** `git -C .. log --oneline -5`, then `git -C .. checkout <previous id>` and
  `docker compose up -d --build`. Database changes are never rolled back, only fixed forward, so
  tell Claude what happened before doing this.
- **Get data back.** First choice: RDS console → Databases → `ordaro` → Actions → **Restore to
  point in time**. That creates a new database as it was at the minute you pick; point
  `ORDARO_DB_URL` in `.env` at its endpoint and run `./deploy.sh`. The nightly file backups
  restore with `pg_restore` (the restore was rehearsed on 2026-09-23).

## Keep in mind

- **Never** set the database to *Public access: Yes*, and never commit `deploy/.env`. It holds
  the database passwords, and git already ignores it.
- The `ordaro-key.pem` file is the only way into the server besides the AWS console. Keep it
  private.
- **Both GitHub repositories are public**, so anyone can read the code. No passwords or keys are
  in it, but a business usually keeps its code private. To make them private: GitHub → each repo →
  Settings → Danger Zone → *Change visibility*. The server then needs read access to pull: create
  a fine-grained personal access token with *Contents: read-only* on the two repos, and run
  `git -C ~/ordaro/ordaro-backend remote set-url origin https://<token>@github.com/swanhtetaung01/ordaro-backend.git`
  (and the same for `ordaro-web`).
- A real domain later: point it at the Elastic IP (an A record), change `ORDARO_DOMAIN`, and run
  `./deploy.sh`. The certificate follows automatically.
