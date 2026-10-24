#!/bin/bash
# Records Forgejo 17's Actions answers for the contract suite: a pod with Forgejo and a runner on the host label,
# a repository with a passing, a failing, a long and a cancelled workflow, and one run waiting before any runner.
# Everything it makes is named core-forgejo-rec* and removed on exit. Usage: record-forgejo.sh <out-dir>
set -euo pipefail
OUT=${1:?out dir}
mkdir -p "$OUT"
POD=core-forgejo-rec
PORT=3999
BASE=http://127.0.0.1:$PORT
API=$BASE/api/v1
FORGEJO=codeberg.org/forgejo/forgejo:17.0.0
RUNNER=code.forgejo.org/forgejo/runner:13.2.0
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
    -e FORGEJO__database__DB_TYPE=sqlite3 -e FORGEJO__server__ROOT_URL=http://localhost:3000/ \
    -e FORGEJO__actions__ENABLED=true -e FORGEJO__actions__DEFAULT_ACTIONS_URL=https://code.forgejo.org "$FORGEJO" >/dev/null
for i in $(seq 60); do
    curl -fsS -o /dev/null $API/version 2>/dev/null && break
    sleep 2
done
fj() { podman exec -u git $POD-forgejo forgejo "$@"; }
PASS=$(head -c 18 /dev/urandom | base64 | tr -d '/+=')
fj admin user create --admin --username core --password "$PASS" --email core@example.invalid --must-change-password=false >/dev/null
printf 'user = "core:%s"\n' "$PASS" >"$W/basic.cfg"
TOKEN=$(curl -fsS -K "$W/basic.cfg" -H 'Content-Type: application/json' \
    -d '{"name":"rec","scopes":["write:repository","write:user"]}' $API/users/core/tokens | python3 -c 'import json,sys; print(json.load(sys.stdin)["sha1"])')
printf 'header = "Authorization: token %s"\n' "$TOKEN" >"$W/auth.cfg"
a() { curl -fsS -K "$W/auth.cfg" -H 'Content-Type: application/json' "$@"; }
a -d '{"name":"probe","auto_init":true,"default_branch":"main"}' $API/user/repos >/dev/null
put() { # put <path> <content> <message>
    local b64
    b64=$(printf '%s' "$2" | base64 -w0)
    a -X POST -d "{\"content\":\"$b64\",\"message\":\"$3\",\"branch\":\"main\"}" "$API/repos/core/probe/contents/$1" |
        python3 -c 'import json,sys; print(json.load(sys.stdin)["commit"]["sha"])'
}
# Waiting: a workflow pushed while no runner exists.
WAIT_SHA=$(put .forgejo/workflows/wait.yml $'on: [push]\njobs:\n  first:\n    runs-on: host\n    steps:\n      - run: echo waiting\n' "a run with no runner")
sleep 5
# The runner on the host label: steps run in its own container's shell.
RT=$(fj actions generate-runner-token)
podman run -d -q --pod "$POD" --name $POD-runner --entrypoint sh "$RUNNER" -c \
    "cd /data && forgejo-runner register --no-interactive --instance http://localhost:3000 --token $RT --name rec --labels host:host && forgejo-runner daemon" >/dev/null
OK_SHA=$(put .forgejo/workflows/ok.yml $'on: [push]\njobs:\n  build:\n    runs-on: host\n    steps:\n      - run: echo all green\n  lint:\n    runs-on: host\n    steps:\n      - run: echo lint ok\n' "a passing build")
sleep 1
FAIL_SHA=$(put .forgejo/workflows/fail.yml $'on: [push]\njobs:\n  test:\n    runs-on: host\n    steps:\n      - run: echo before the failure\n      - run: |\n          for i in $(seq 1 3000); do echo "line $i of the test log"; done\n          exit 1\n' "a failing build")
for i in $(seq 90); do
    n=$(a "$API/repos/core/probe/actions/runs?limit=50" | python3 -c 'import json,sys; r=json.load(sys.stdin); print(sum(1 for x in r.get("workflow_runs",[]) if x["status"] in ("waiting","running","blocked")))')
    [ "$n" = 0 ] && break
    sleep 2
done
LONG_SHA=$(put .forgejo/workflows/long.yml $'on: [push]\njobs:\n  long:\n    runs-on: host\n    steps:\n      - run: sleep 600\n' "a long build")
sleep 15
n=0
rec() { # rec <name> <url> [auth|noauth|badauth]
    n=$((n + 1))
    local f
    f=$(printf '%s/%02d-%s' "$OUT" "$n" "$1")
    local auth=(-K "$W/auth.cfg")
    [ "${3:-auth}" = noauth ] && auth=()
    [ "${3:-auth}" = badauth ] && auth=(-H 'Authorization: token not-a-valid-token')
    curl -sS "${auth[@]}" -D "$f.headers" -o "$f.body" -w '%{http_code}\n' "$2" >"$f.status"
    echo "$1 $(cat "$f.status") ${2#$BASE}" >>"$OUT/index.txt"
}
rec head-main "$API/repos/core/probe/branches/main"
rec head-absent "$API/repos/core/probe/branches/no-such-branch"
rec repo "$API/repos/core/probe"
for c in ok:$OK_SHA fail:$FAIL_SHA long:$LONG_SHA wait:$WAIT_SHA none:0000000000000000000000000000000000000000; do
    rec "runs-${c%%:*}" "$API/repos/core/probe/actions/runs?head_sha=${c#*:}&limit=50&page=1"
done
for kind in ok fail long wait; do
    for id in $(python3 -c 'import json,sys; [print(r["id"]) for r in json.load(open(sys.argv[1])).get("workflow_runs",[])]' "$(ls $OUT/*-runs-$kind.body)"); do
        rec "jobs-$kind-$id" "$API/repos/core/probe/actions/runs/$id/jobs"
        for j in $(python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); [print(x["id"]) for x in (d if isinstance(d,list) else d.get("jobs",d.get("workflow_jobs",[])))]' "$(ls $OUT/*-jobs-$kind-$id.body)"); do
            [ "$kind" = long ] || [ "$kind" = wait ] || rec "log-$kind-$j" "$API/repos/core/probe/actions/jobs/$j/logs"
        done
    done
done
# Cancel the long run, then read it again.
LID=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["workflow_runs"][0]["id"])' "$(ls $OUT/*-runs-long.body)")
a -X POST "$API/repos/core/probe/actions/runs/$LID/cancel" >/dev/null || true
sleep 10
rec runs-cancelled "$API/repos/core/probe/actions/runs?head_sha=$LONG_SHA&limit=50&page=1"
rec jobs-cancelled-$LID "$API/repos/core/probe/actions/runs/$LID/jobs"
rec bad-token "$API/repos/core/probe/branches/main" badauth
rec no-auth "$API/repos/core/probe/branches/main" noauth
rec no-repo "$API/repos/core/no-such-repository/branches/main"
rec version "$API/version" noauth
echo "shas ok=$OK_SHA fail=$FAIL_SHA long=$LONG_SHA wait=$WAIT_SHA" >>"$OUT/index.txt"
python3 -I - "$OUT" "$TOKEN" <<'PY'
import sys,pathlib
tok=sys.argv[2].encode()
print('token in', sum(tok in p.read_bytes() for p in pathlib.Path(sys.argv[1]).iterdir()), 'files')
PY
cat "$OUT/index.txt"
