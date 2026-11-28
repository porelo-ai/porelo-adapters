#!/bin/bash
# A private pod keeps review captures away from an existing forge.
# Usage: record-forgejo-reviews.sh <empty-out-dir>
set -euo pipefail
set +x
umask 077
OUT=${1:?out dir}
if [ -e "$OUT" ] && { [ ! -d "$OUT" ] || [ -n "$(find "$OUT" -mindepth 1 -maxdepth 1 -print -quit)" ]; }; then
    echo "REFUSED: output directory is not empty: $OUT" >&2
    exit 1
fi
mkdir -p "$OUT"
POD=core-forgejo-reviews
PORT=3997
BASE=http://127.0.0.1:$PORT
API=$BASE/api/v1
FORGEJO=codeberg.org/forgejo/forgejo:17.0.0
W=$(mktemp -d)
chmod 700 "$W"
POD_ID=
cleanup() {
    local status=$?
    trap - EXIT
    if [ -n "$POD_ID" ] && ! podman pod rm -f "$POD_ID" >/dev/null 2>&1; then
        echo "LEFTOVER: could not remove owned pod $POD_ID" >&2
        status=1
    fi
    if ! rm -rf "$W"; then
        echo "LEFTOVER: could not remove scratch directory $W" >&2
        status=1
    fi
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'status=$?; echo "FAILED: line $LINENO, exit $status" >&2; exit "$status"' ERR
if podman pod exists "$POD"; then
    echo "REFUSED: pod $POD exists" >&2
    exit 1
else
    status=$?
    if [ "$status" -ne 1 ]; then
        echo "REFUSED: pod existence check returned $status" >&2
        exit "$status"
    fi
fi
created=$(podman pod create --name "$POD" -p 127.0.0.1:$PORT:3000)
if [[ ! "$created" =~ ^[0-9a-f]{64}$ ]]; then
    echo "LEFTOVER: pod creation returned no valid owned id for $POD" >&2
    exit 1
fi
POD_ID=$created
podman run -d -q --pod "$POD" --name "$POD-forgejo" -e FORGEJO__security__INSTALL_LOCK=true \
    -e FORGEJO__database__DB_TYPE=sqlite3 -e FORGEJO__server__ROOT_URL="$BASE/" "$FORGEJO" >/dev/null
for _ in $(seq 60); do
    curl -q --noproxy '*' -fsS -o /dev/null "$API/version" 2>/dev/null && break
    sleep 2
done
curl -q --noproxy '*' -fsS "$API/version" | python3 -I -c 'import json,sys; assert json.load(sys.stdin)["version"].split("+",1)[0] == "17.0.0", "expected Forgejo 17.0.0"'
fj() { podman exec -u git "$POD-forgejo" forgejo "$@"; }
for user in core reviewer; do
    # Let Forgejo generate passwords so even account setup has no credential argument.
    fj admin user create --username "$user" --email "$user@example.invalid" --admin="$([ "$user" = core ] && echo true || echo false)" \
        --random-password --random-password-length 32 --must-change-password=false |
        python3 -I -c '
import json,pathlib,re,sys
matches=re.findall(r"^generated random password is '\''(.*)'\''$",sys.stdin.read(),re.M)
if len(matches)!=1:raise SystemExit("Expected one generated account password")
password=matches[0]
pathlib.Path(sys.argv[1]).write_text("user = "+json.dumps(sys.argv[2]+":"+password)+"\n")
' "$W/$user-basic.cfg" "$user"
done
token() { # token <user> <name> <scopes as a JSON list>
    curl -q --noproxy '*' -fsS -K "$W/$1-basic.cfg" -H 'Content-Type: application/json' \
        -d "{\"name\":\"$2\",\"scopes\":$3}" "$API/users/$1/tokens" |
        python3 -I -c 'import json,re,sys; t=json.load(sys.stdin)["sha1"]; assert re.fullmatch("[0-9a-f]{40}",t), "invalid token"; print(t)'
}
TOKEN=$(token core rec '["write:repository","write:user"]')
REVIEWER=$(token reviewer rec '["write:repository"]')
READ=$(token reviewer read '["read:repository"]')
printf 'header = "Authorization: token %s"\n' "$TOKEN" >"$W/auth.cfg"
printf 'header = "Authorization: token %s"\n' "$REVIEWER" >"$W/reviewer.cfg"
printf 'header = "Authorization: token %s"\n' "$READ" >"$W/read.cfg"
unset TOKEN REVIEWER READ
a() { curl -q --noproxy '*' -fsS -K "$W/auth.cfg" -H 'Content-Type: application/json' "$@"; }
r() { curl -q --noproxy '*' -fsS -K "$W/reviewer.cfg" -H 'Content-Type: application/json' "$@"; }
id_of() {
    python3 -I -c 'import json,sys; n=json.load(sys.stdin)[sys.argv[1]]; assert type(n) is int and n>0, "invalid id"; print(n)' "${1:-id}"
}
REPO=$API/repos/core/probe
a -d '{"name":"probe","auto_init":true,"default_branch":"main"}' "$API/user/repos" >/dev/null
put() { # put <file> <branch> <content> [existing blob sha]
    local content data method=POST
    content=$(printf '%s' "$3" | base64 -w0)
    data="{\"branch\":\"$2\",\"message\":\"Update $1\",\"content\":\"$content\"}"
    if [ -n "${4:-}" ]; then
        method=PUT
        data="${data%\}},\"sha\":\"$4\"}"
    fi
    a -X "$method" -d "$data" "$REPO/contents/$1"
}
LOGIN_BLOB=$(put login.txt main $'login\nold login\nkeep login\n' | python3 -I -c 'import json,sys; print(json.load(sys.stdin)["content"]["sha"])')
SETTINGS_BLOB=$(put settings.txt main $'settings\nold setting\nkeep setting\n' | python3 -I -c 'import json,sys; print(json.load(sys.stdin)["content"]["sha"])')
a -d '{"new_branch_name":"porelo/t","old_branch_name":"main"}' "$REPO/branches" >/dev/null
put login.txt porelo/t $'login\nnew login\nkeep login\n' "$LOGIN_BLOB" >/dev/null
put settings.txt porelo/t $'settings\nnew setting\nkeep setting\n' "$SETTINGS_BLOB" >/dev/null
a -d '{"head":"porelo/t","base":"main","title":"Review two changed files"}' "$REPO/pulls" >"$W/pull.json"
INDEX=$(id_of number <"$W/pull.json")
PULL=$REPO/pulls/$INDEX
HEAD=$(python3 -I -c 'import json,re,sys; s=json.load(sys.stdin)["head"]["sha"]; assert re.fullmatch("[0-9a-f]{40}",s), "invalid head sha"; print(s)' <"$W/pull.json")
r -d "{\"event\":\"COMMENT\",\"body\":\"Please check both sides of this change.\",\"commit_id\":\"$HEAD\",\"comments\":[{\"path\":\"login.txt\",\"new_position\":2,\"old_position\":0,\"body\":\"Explain the new login.\"},{\"path\":\"settings.txt\",\"new_position\":0,\"old_position\":2,\"body\":\"Why remove this setting?\"}]}" \
    "$PULL/reviews" >"$W/review.json"
REVIEW=$(id_of <"$W/review.json")
# The reader joins this reply to the earlier comment by path, side and line across reviews.
a -d "{\"event\":\"COMMENT\",\"body\":\"\",\"commit_id\":\"$HEAD\",\"comments\":[{\"path\":\"login.txt\",\"new_position\":2,\"old_position\":0,\"body\":\"The new login handles the empty input.\"}]}" \
    "$PULL/reviews" >"$W/reply.json"
REPLY=$(id_of <"$W/reply.json")
a "$PULL/reviews/$REVIEW/comments" >"$W/comments.json"
OLD_COMMENT=$(python3 -I -c 'import json,sys; c=[c for c in json.load(sys.stdin) if c["path"]=="settings.txt" and c["original_position"]==2]; assert len(c)==1, "expected old-side comment"; n=c[0]["id"]; assert type(n) is int and n>0; print(n)' <"$W/comments.json")

# Resolution needs a web session; neither cookies nor CSRF values leave the scratch directory.
web() { curl -q --noproxy '*' -fsS -b "$W/cookies" -c "$W/cookies" -H "Origin: $BASE" "$@"; }
csrf() {
    python3 -I - "$W/page.html" "$W/csrf" <<'PY'
from html.parser import HTMLParser
import pathlib,sys
class CSRF(HTMLParser):
    values=set()
    def handle_starttag(self,tag,attrs):
        a=dict(attrs)
        if a.get('name')=='_csrf':
            v=a.get('value') if tag=='input' else a.get('content') if tag=='meta' else None
            if v:self.values.add(v)
import re
page=pathlib.Path(sys.argv[1]).read_text()
p=CSRF()
p.feed(page)
p.values.update(re.findall(r"csrfToken:\s*'([^']+)'",page))
# Forgejo without a token in the page checks the request's origin instead.
if len(p.values)>1:raise SystemExit('Expected at most one CSRF value in the web form')
pathlib.Path(sys.argv[2]).write_text(p.values.pop() if p.values else '')
PY
}
web "$BASE/user/login" -o "$W/page.html"
csrf
python3 -I - "$W" <<'PY' | web --data-binary @- "$BASE/user/login" -o "$W/login.html" -w '%{http_code}\n' >"$W/login.status"
import json,pathlib,sys,urllib.parse
w=pathlib.Path(sys.argv[1])
basic=json.loads((w/'core-basic.cfg').read_text().split(' = ',1)[1])
user,password=basic.split(':',1)
print(urllib.parse.urlencode({'user_name':user,'password':password,'_csrf':(w/'csrf').read_text()}),end='')
PY
if [[ ! "$(cat "$W/login.status")" =~ ^30[23]$ ]]; then
    echo "Expected a redirect after web login; got HTTP $(cat "$W/login.status")" >&2
    exit 1
fi
web "$BASE/core/probe/pulls/$INDEX/files" -o "$W/page.html"
csrf
web --data-urlencode "_csrf@$W/csrf" --data-urlencode "comment_id=$OLD_COMMENT" \
    --data-urlencode 'action=Resolve' --data-urlencode 'origin=diff' \
    "$BASE/core/probe/issues/resolve_conversation" -o "$W/resolved.html"
a "$PULL/reviews/$REVIEW/comments" | python3 -I -c 'import json,sys; c=[c for c in json.load(sys.stdin) if c["id"]==int(sys.argv[1])]; assert len(c)==1 and c[0].get("resolver",{}).get("login")=="core", "conversation was not resolved"' "$OLD_COMMENT"
a -d "{\"event\":\"PENDING\",\"body\":\"Private unfinished review.\",\"commit_id\":\"$HEAD\",\"comments\":[{\"path\":\"settings.txt\",\"new_position\":2,\"old_position\":0,\"body\":\"Unsubmitted comment.\"}]}" \
    "$PULL/reviews" >"$W/pending.json"
PENDING=$(id_of <"$W/pending.json")
CAPTURE_DAY=$(date -u +%F)
export CAPTURE_DAY
n=0
rec() { # rec <name> <method> <url> [auth|reviewer|read] [body] [expected status]
    n=$((n + 1))
    local cfg=$W/${4:-auth}.cfg
    local data=()
    [ -n "${5:-}" ] && data=(-H 'Content-Type: application/json' -d "$5")
    curl -q --noproxy '*' -sS -K "$cfg" -X "$2" "${data[@]}" -D "$W/response.headers" -o "$W/response.body" \
        -w '%{http_code}\n' "$3" >"$W/response.status"
    LAST=$(printf '%s/%02d.json' "$OUT" "$n")
    python3 -I - "$W" "$LAST" "$1" "$2" "${3#"$API"}" "${6:-200}" <<'PY'
import json,os,pathlib,re,sys
w=pathlib.Path(sys.argv[1])
raw=(w/'response.body').read_bytes()
head=(w/'response.headers').read_bytes()
for name in ('auth','reviewer','read'):
    token=re.search(r'Authorization: token ([0-9a-f]{40})',(w/(name+'.cfg')).read_text()).group(1).encode()
    if token in raw or token in head:raise SystemExit('REFUSED: response contains a recording credential')
status=int((w/'response.status').read_text())
if status!=int(sys.argv[6]):
    raise SystemExit('Expected HTTP '+sys.argv[6]+' for '+sys.argv[3]+', got '+str(status)+': '+raw.decode())
headers={}
for line in head.decode().splitlines():
    key,sep,value=line.partition(':')
    if sep and key.lower() in ('content-type','x-total-count','link'):
        headers[key.lower()]=value.strip()
body=raw.decode()
json.loads(body)
entry={'recorded':True,'source':'Forgejo 17.0.0 in podman, '+os.environ['CAPTURE_DAY'],
       'capture':sys.argv[3],'method':sys.argv[4],'path':sys.argv[5],
       'status':status,'headers':headers,'body':body}
pathlib.Path(sys.argv[2]).write_text(json.dumps(entry,ensure_ascii=False,indent=2)+'\n')
PY
    echo "$1 $(cat "$W/response.status") $2 ${3#"$BASE"}" >>"$OUT/index.txt"
}
rec pull GET "$PULL"
python3 -I -c 'import json,sys; p=json.loads(json.load(sys.stdin)["body"]); assert p["state"]=="open" and p["changed_files"]==2 and p["head"]["sha"]==sys.argv[1], "expected an open pull request with two changed files at the captured head"' "$HEAD" <"$LAST"
rec reviews GET "$PULL/reviews?limit=50&page=1"
REVIEWS=$LAST
ids=$(
    python3 -I - "$REVIEWS" "$REVIEW" "$REPLY" "$PENDING" <<'PY'
import json,sys
entry=json.load(open(sys.argv[1]))
reviews=json.loads(entry['body'])
expected={int(n) for n in sys.argv[2:]}
assert {r['id'] for r in reviews}==expected and len(reviews)==3, 'expected three distinct reviews'
assert entry['headers'].get('x-total-count')=='3', 'expected a complete review list'
assert any(r['id']==int(sys.argv[2]) and r['state']=='COMMENT' and r['user']['login']=='reviewer' and r['body']=='Please check both sides of this change.' for r in reviews), 'missing submitted review body'
assert any(r['id']==int(sys.argv[3]) and r['state']=='COMMENT' and r['user']['login']=='core' for r in reviews), 'missing submitted reply review'
assert any(r['id']==int(sys.argv[4]) and r['state']=='PENDING' and r['user']['login']=='core' for r in reviews), 'missing reader pending review'
for r in reviews:
    assert type(r['id']) is int and r['id']>0, 'invalid review id'
    print(r['id'])
PY
)
for id in $ids; do
    rec "comments-$id" GET "$PULL/reviews/$id/comments"
done
# The writer has no pending review to accidentally submit along with the released comment.
WRITE="{\"body\":\"\",\"comments\":[{\"body\":\"Recorded single-line comment.\",\"extra_lines_count\":0,\"new_position\":3,\"old_position\":0,\"path\":\"login.txt\"}],\"commit_id\":\"$HEAD\",\"event\":\"COMMENT\"}"
rec create-review POST "$PULL/reviews" reviewer "$WRITE"
WRITTEN=$(python3 -I -c 'import json,sys; print(json.loads(json.load(sys.stdin)["body"])["id"])' <"$LAST")
[[ "$WRITTEN" =~ ^[1-9][0-9]*$ ]]
rec created-comments GET "$PULL/reviews/$WRITTEN/comments"
python3 -I -c 'import json,sys; c=json.loads(json.load(sys.stdin)["body"]); assert len(c)==1 and c[0]["body"]=="Recorded single-line comment." and c[0]["position"]==3, "expected exactly one written line comment"' <"$LAST"
rec create-review-read-only POST "$PULL/reviews" read "$WRITE" 403
cat "$OUT/index.txt"
