#!/usr/bin/env bash
# Build step 4 — the vertical slice, with curl, against a running app (spec §11 step 4).
#
#   create product → stock in → sell → retry the same completion → balance drops once, one receipt
#   plus: login → token → request; a wrong-tenant request; a PIN register session.
#
# Usage: BASE=http://localhost:8080 scripts/vertical-slice.sh
#        (a server with TRILLOPOS_SIGNUP_CODE set: SIGNUP_CODE=that-code BASE=… scripts/vertical-slice.sh)
# Needs curl and python3 (for JSON field extraction; no jq dependency). Every step asserts its
# HTTP status and the values that matter; the script exits non-zero on the first failure.
set -euo pipefail

BASE="${BASE:-http://localhost:8080}"
RUN="$(date +%s)$RANDOM"
PHONE_A="+9597$(printf '%07d' $((RANDOM * 3 % 9999999)))"
PHONE_B="+9597$(printf '%07d' $((RANDOM * 7 % 9999999)))"
PASSWORD="correct horse battery"

step=0
say()  { printf '\n\033[1m%2d. %s\033[0m\n' "$((++step))" "$*"; }
# j "d['x']" prints one field; whole-number decimals (10.0000) print as 10
j()    { python -c "import json,sys; d=json.load(sys.stdin); v=$1; print(int(v) if isinstance(v,float) and v.is_integer() else v)"; }
fail() { printf '\033[31mFAIL: %s\033[0m\n' "$*" >&2; exit 1; }
expect() { [ "$1" == "$2" ] || fail "$3: expected $2, got $1"; printf '   ✓ %s = %s\n' "$3" "$2"; }

# call METHOD PATH [TOKEN] [BODY] [EXTRA-HEADER] → sets STATUS and BODY_OUT
call() {
  local method=$1 path=$2 token=${3:-} body=${4:-} extra=${5:-}
  local args=(-s -o /tmp/slice.body -w '%{http_code}' -X "$method" "$BASE$path" -H 'Content-Type: application/json')
  [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
  [ -n "$extra" ] && args+=(-H "$extra")
  [ -n "$body" ]  && args+=(-d "$body")
  STATUS=$(curl "${args[@]}")
  BODY_OUT=$(cat /tmp/slice.body)
}

say "Health"
call GET /actuator/health
expect "$STATUS" 200 "health status"

say "Sign up shop A (account + organization + owner + default STORE, one transaction)"
call POST /auth/signup "" "{\"phone\":\"$PHONE_A\",\"password\":\"$PASSWORD\",\"fullName\":\"Daw Slice\",\"businessName\":\"Slice Mart $RUN\",\"signupCode\":\"${SIGNUP_CODE:-}\"}"
expect "$STATUS" 201 "signup status"
expect "$(echo "$BODY_OUT" | j "d['kind']")" USER "token kind"
A_REFRESH=$(echo "$BODY_OUT" | j "d['refreshToken']")

say "Login → token → request round trip"
call POST /auth/login "" "{\"phone\":\"$PHONE_A\",\"password\":\"$PASSWORD\"}"
expect "$STATUS" 200 "login status"
expect "$(echo "$BODY_OUT" | j "d['tokens']['kind']")" USER "one active membership signs straight in"
A=$(echo "$BODY_OUT" | j "d['tokens']['accessToken']")
call GET /organization "$A"
expect "$STATUS" 200 "GET /organization with the token"
expect "$(echo "$BODY_OUT" | j "d['currencyCode']")" MMK "default currency"
call GET /organization
expect "$STATUS" 401 "GET /organization without a token"
call GET /locations "$A"
MAIN=$(echo "$BODY_OUT" | j "d[0]['id']")
expect "$(echo "$BODY_OUT" | j "d[0]['type']")" STORE "default location is a STORE"

say "Create a product"
call POST /products "$A" '{"name":"Cooking Oil 1L","unit":"PIECE","retailPrice":4500,"barcodes":["8834000000011"]}'
expect "$STATUS" 201 "create product"
OIL=$(echo "$BODY_OUT" | j "d['id']")
expect "$(echo "$BODY_OUT" | j "d['barcodes'][0]")" 8834000000011 "barcode stored"

say "Stock in 10 @ 3600 (posted stock document)"
call POST /stock-documents "$A" "{\"type\":\"STOCK_IN\",\"locationId\":\"$MAIN\",\"post\":true,\"lines\":[{\"productId\":\"$OIL\",\"quantity\":10,\"unitCost\":3600}]}"
expect "$STATUS" 201 "post stock-in"
expect "$(echo "$BODY_OUT" | j "d['status']")" POSTED "document status"
GRN=$(echo "$BODY_OUT" | j "d['documentNumber']")
echo "   document number $GRN"
call GET "/stock-balances?productId=$OIL" "$A"
expect "$(echo "$BODY_OUT" | j "d[0]['quantity']")" 10 "on hand after stock-in"
expect "$(echo "$BODY_OUT" | j "d[0]['averageCost']")" 3600 "average cost"

say "Open a shift"
call POST /shifts "$A" "{\"locationId\":\"$MAIN\",\"openingFloat\":50000}"
expect "$STATUS" 201 "open shift"
SHIFT=$(echo "$BODY_OUT" | j "d['id']")

say "Sell 3 (idempotency key slice-$RUN)"
CHECKOUT="{\"idempotencyKey\":\"slice-$RUN\",\"locationId\":\"$MAIN\",\"channel\":\"POS\",\"cashierShiftId\":\"$SHIFT\",\"lines\":[{\"productId\":\"$OIL\",\"quantity\":3}],\"payments\":[{\"method\":\"CASH\",\"amount\":13500,\"tenderedAmount\":20000}]}"
call POST /sales/checkout "$A" "$CHECKOUT"
expect "$STATUS" 201 "first completion"
SALE=$(echo "$BODY_OUT" | j "d['id']")
RECEIPT=$(echo "$BODY_OUT" | j "d['receiptNumber']")
expect "$(echo "$BODY_OUT" | j "d['status']")" COMPLETED "sale status"
expect "$(echo "$BODY_OUT" | j "d['lines'][0]['unitCost']")" 3600 "unit cost snapshotted on the line"
expect "$(echo "$BODY_OUT" | j "d['taxAmount']")" 642.86 "tax (5 % inclusive, per line)"
expect "$(echo "$BODY_OUT" | j "d['payments'][0]['changeAmount']")" 6500 "change"
echo "   receipt $RECEIPT"

say "Retry the same completion (network drop simulation)"
call POST /sales/checkout "$A" "$CHECKOUT"
expect "$STATUS" 200 "retry answers 200"
expect "$(echo "$BODY_OUT" | j "d['id']")" "$SALE" "same sale id"
expect "$(echo "$BODY_OUT" | j "d['receiptNumber']")" "$RECEIPT" "same receipt"

say "Balance dropped once, one receipt, ledger clean"
call GET "/stock-balances?productId=$OIL" "$A"
expect "$(echo "$BODY_OUT" | j "d[0]['quantity']")" 7 "on hand after the sale and its retry"
call GET "/stock-movements?locationId=$MAIN&productId=$OIL" "$A"
expect "$(echo "$BODY_OUT" | j "len(d)")" 2 "movements: one stock-in, one sale"
expect "$(echo "$BODY_OUT" | j "d[0]['referenceNumber']")" "$RECEIPT" "sale movement carries the receipt"
call GET /inventory/verification "$A"
expect "$(echo "$BODY_OUT" | j "len(d['mismatches'])")" 0 "rebuild job finds no drift"

say "Wrong-tenant request: shop B cannot see A's sale, product or document"
call POST /auth/signup "" "{\"phone\":\"$PHONE_B\",\"password\":\"$PASSWORD\",\"fullName\":\"Ko Other\",\"businessName\":\"Other Shop $RUN\",\"signupCode\":\"${SIGNUP_CODE:-}\"}"
expect "$STATUS" 201 "signup shop B"
B=$(echo "$BODY_OUT" | j "d['accessToken']")
call GET "/sales/$SALE" "$B";      expect "$STATUS" 404 "B reads A's sale"
call GET "/products/$OIL" "$B";    expect "$STATUS" 404 "B reads A's product"
call GET "/stock-balances?productId=$OIL" "$B"
expect "$(echo "$BODY_OUT" | j "len(d)")" 0 "B lists A's balances"
call POST /stock-documents "$B" "{\"type\":\"STOCK_IN\",\"locationId\":\"$MAIN\",\"post\":true,\"lines\":[{\"productId\":\"$OIL\",\"quantity\":1,\"unitCost\":1}]}"
expect "$STATUS" 400 "B posts stock into A's location"

say "PIN register session"
call POST /registers "$A" "{\"locationId\":\"$MAIN\",\"label\":\"Till 1\"}"
expect "$STATUS" 201 "bind a register"
DEVICE=$(echo "$BODY_OUT" | j "d['deviceCredential']")
call POST /memberships "$A" '{"displayName":"Ma Aye","role":"CASHIER","pin":"482913"}'
expect "$STATUS" 201 "register-only cashier with a PIN"
TILL=$(echo "$BODY_OUT" | j "d['membership']['id']")
call GET /auth/register/staff "" "" "X-Register-Device: $DEVICE"
expect "$(echo "$BODY_OUT" | j "d[0]['displayName']")" "Ma Aye" "register lists its staff"
call POST /auth/pin "" "{\"membershipId\":\"$TILL\",\"pin\":\"000000\"}" "X-Register-Device: $DEVICE"
expect "$STATUS" 401 "wrong PIN"
call POST /auth/pin "" "{\"membershipId\":\"$TILL\",\"pin\":\"482913\"}" "X-Register-Device: $DEVICE"
expect "$STATUS" 200 "right PIN"
expect "$(echo "$BODY_OUT" | j "d['kind']")" REGISTER "session kind"
R=$(echo "$BODY_OUT" | j "d['accessToken']")
call POST /sales/checkout "$R" "{\"idempotencyKey\":\"till-$RUN\",\"locationId\":\"$MAIN\",\"channel\":\"POS\",\"cashierShiftId\":\"$SHIFT\",\"lines\":[{\"productId\":\"$OIL\",\"quantity\":1}],\"payments\":[{\"method\":\"KBZ_PAY\",\"amount\":4500,\"referenceNo\":\"KBZ-$RUN\"}]}"
expect "$STATUS" 201 "cashier sells on the register"
expect "$(echo "$BODY_OUT" | j "d['lines'][0].get('unitCost')")" None "cashier does not see cost"
call GET /memberships "$R";  expect "$STATUS" 403 "register session cannot manage staff"
call GET /registers "$R";    expect "$STATUS" 403 "register session cannot bind registers"

say "Close the shift: expected cash = float + cash sales only"
call POST "/shifts/$SHIFT/close" "$A" '{"countedCash":63400}'
expect "$STATUS" 200 "close shift"
expect "$(echo "$BODY_OUT" | j "d['expectedCash']")" 63500 "expected cash (50000 + 13500; wallet sale excluded)"
expect "$(echo "$BODY_OUT" | j "d['variance']")" -100 "variance stored"

say "Refresh rotation and reuse detection"
call POST /auth/refresh "" "{\"refreshToken\":\"$A_REFRESH\"}"
expect "$STATUS" 200 "refresh"
call POST /auth/refresh "" "{\"refreshToken\":\"$A_REFRESH\"}"
expect "$STATUS" 401 "reused refresh token is refused"
expect "$(echo "$BODY_OUT" | j "d['code']")" refresh_token_reused "reuse revokes the family"

printf '\n\033[32mVertical slice passed: %d steps.\033[0m\n' "$step"
