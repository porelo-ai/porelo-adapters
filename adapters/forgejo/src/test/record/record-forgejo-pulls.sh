#!/bin/bash
# Records Forgejo 17's pull request answers for the contract suite: a repository with a branch porelo/t, the open
# pull requests before and after one is opened, the branch's newest commit, the opening itself, and an opening with a
# token that may only read. Everything it makes is named core-forgejo-pulls* and removed on exit.
# Usage: record-forgejo-pulls.sh <out-dir>
set -euo pipefail
OUT=${1:?out dir}
mkdir -p "$OUT"
POD=core-forgejo-pulls
PORT=3998
BASE=http://127.0.0.1:$PORT
API=$BASE/api/v1
FORGEJO=codeberg.org/forgejo/forgejo:17.0.0
W=$(mktemp -d)
chmod 700 "$W"
cleanup() {
    podman pod rm -f "$POD" >/dev/null 2>&1 || true
    rm -rf "$W"
    echo "removed pod and $W"
}
trap cleanup EXIT INT TERM
podman pod exists "$POD" && {
    echo "REFUSED: pod $POD exists"
    exit 1
}

podman pod create --name "$POD" -p 127.0.0.1:$PORT:3000 >/dev/null
podman run -d -q --pod "$POD" --name $POD-forgejo -e FORGEJO__security__INSTALL_LOCK=true \
    -e FORGEJO__database__DB_TYPE=sqlite3 -e FORGEJO__server__ROOT_URL=http://localhost:3000/ "$FORGEJO" >/dev/null
for i in $(seq 60); do
    curl -fsS -o /dev/null $API/version 2>/dev/null && break
    sleep 2
done
fj() { podman exec -u git $POD-forgejo forgejo "$@"; }
PASS=$(head -c 18 /dev/urandom | base64 | tr -d '/+=')
fj admin user create --admin --username core --password "$PASS" --email core@example.invalid --must-change-password=false >/dev/null
printf 'user = "core:%s"\n' "$PASS" >"$W/basic.cfg"
token() { # token <name> <scopes as a JSON list>
    curl -fsS -K "$W/basic.cfg" -H 'Content-Type: application/json' \
        -d "{\"name\":\"$1\",\"scopes\":$2}" $API/users/core/tokens | python3 -c 'import json,sys; print(json.load(sys.stdin)["sha1"])'
}
TOKEN=$(token rec '["write:repository","write:user"]')
READ=$(token read '["read:repository"]')
printf 'header = "Authorization: token %s"\n' "$TOKEN" >"$W/auth.cfg"
printf 'header = "Authorization: token %s"\n' "$READ" >"$W/read.cfg"
a() { curl -fsS -K "$W/auth.cfg" -H 'Content-Type: application/json' "$@"; }
a -d '{"name":"probe","auto_init":true,"default_branch":"main"}' $API/user/repos >/dev/null
b64=$(printf 'fixed\n' | base64 -w0)
a -X POST -d "{\"content\":\"$b64\",\"message\":\"Fix the login\",\"branch\":\"main\",\"new_branch\":\"porelo/t\"}" \
    "$API/repos/core/probe/contents/fix.txt" >/dev/null
n=0
rec() { # rec <name> <method> <url> [auth|read] [body]
    n=$((n + 1))
    local f
    f=$(printf '%s/%02d-%s' "$OUT" "$n" "$1")
    local cfg=$W/auth.cfg
    [ "${4:-auth}" = read ] && cfg=$W/read.cfg
    local data=()
    [ -n "${5:-}" ] && data=(-H 'Content-Type: application/json' -d "$5")
    curl -sS -K "$cfg" -X "$2" "${data[@]}" -D "$f.headers" -o "$f.body" -w '%{http_code}\n' "$3" >"$f.status"
    echo "$1 $(cat "$f.status") $2 ${3#$BASE}" >>"$OUT/index.txt"
}
OPEN='{"head":"porelo/t","base":"main","title":"Fix the login"}'
rec repo GET "$API/repos/core/probe"
rec pulls-none GET "$API/repos/core/probe/pulls?state=open&limit=50&page=1"
rec branch GET "$API/repos/core/probe/branches/porelo/t"
rec open-read-only POST "$API/repos/core/probe/pulls" read "$OPEN"
rec open POST "$API/repos/core/probe/pulls" auth "$OPEN"
rec pulls-one GET "$API/repos/core/probe/pulls?state=open&limit=50&page=1"
python3 -I - "$OUT" "$TOKEN" "$READ" <<'PY'
import sys,pathlib
toks=[t.encode() for t in sys.argv[2:]]
print('token in', sum(any(t in p.read_bytes() for t in toks) for p in pathlib.Path(sys.argv[1]).iterdir()), 'files')
PY
cat "$OUT/index.txt"
