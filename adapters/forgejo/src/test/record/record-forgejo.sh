#!/bin/bash
# A private pod keeps capture workflows away from an existing forge or runner.
# Usage: record-forgejo.sh <empty-out-dir>
set -euo pipefail
umask 077
OUT=${1:?out dir}
if [ -e "$OUT" ] && { [ ! -d "$OUT" ] || [ -n "$(find "$OUT" -mindepth 1 -maxdepth 1 -print -quit)" ]; }; then
    echo "REFUSED: output directory is not empty: $OUT" >&2
    exit 1
fi
mkdir -p "$OUT"
POD=core-forgejo-rec
PORT=3999
BASE=http://127.0.0.1:$PORT
API=$BASE/api/v1
FORGEJO=codeberg.org/forgejo/forgejo:17.0.0
RUNNER=code.forgejo.org/forgejo/runner:13.2.0
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
podman run -d -q --pod "$POD" --name $POD-forgejo -e FORGEJO__security__INSTALL_LOCK=true \
    -e FORGEJO__database__DB_TYPE=sqlite3 -e FORGEJO__server__ROOT_URL=https://forge.example/ \
    -e FORGEJO__server__LOCAL_ROOT_URL=http://localhost:3000/ \
    -e FORGEJO__actions__ENABLED=true -e FORGEJO__actions__DEFAULT_ACTIONS_URL=https://code.forgejo.org "$FORGEJO" >/dev/null
for _ in $(seq 60); do
    curl -fsS -o /dev/null $API/version 2>/dev/null && break
    sleep 2
done
curl -fsS -o /dev/null "$API/version"
fj() { podman exec -u git $POD-forgejo forgejo "$@"; }
PASS=$(head -c 18 /dev/urandom | base64 | tr -d '/+=')
fj admin user create --admin --username core --password "$PASS" --email core@example.invalid --must-change-password=false >/dev/null
printf 'user = "core:%s"\n' "$PASS" >"$W/basic.cfg"
TOKEN=$(curl -fsS -K "$W/basic.cfg" -H 'Content-Type: application/json' \
    -d '{"name":"rec","scopes":["write:repository","write:user"]}' $API/users/core/tokens | python3 -c 'import json,sys; print(json.load(sys.stdin)["sha1"])')
printf 'header = "Authorization: token %s"\n' "$TOKEN" >"$W/auth.cfg"
a() { curl -fsS -K "$W/auth.cfg" -H 'Content-Type: application/json' "$@"; }
a -d '{"name":"probe","auto_init":true,"default_branch":"main"}' $API/user/repos >/dev/null
put() {
    local b64
    b64=$(printf '%s' "$2" | base64 -w0)
    a -X POST -d "{\"content\":\"$b64\",\"message\":\"$3\",\"branch\":\"${5:-main}\"}" "$API/repos/core/${4:-probe}/contents/$1" |
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
for _ in $(seq 90); do
    n=$(a "$API/repos/core/probe/actions/runs?limit=50" | python3 -c 'import json,sys; r=json.load(sys.stdin); print(sum(1 for x in r.get("workflow_runs",[]) if x["status"] in ("waiting","running","blocked")))')
    [ "$n" = 0 ] && break
    sleep 2
done
[ "$n" = 0 ] || {
    echo "Expected completed original workflows; got $n outstanding" >&2
    exit 1
}
wait_complete() {
    local repo=$1 sha=$2 expected=$3 state=empty
    for _ in $(seq 90); do
        a "$API/repos/core/$repo/actions/runs?head_sha=$sha&limit=50&page=1" >"$W/poll.json"
        state=$(
            python3 -I - "$W/poll.json" "$expected" <<'CHECK'
import json,sys
body=json.load(open(sys.argv[1]))
runs=body['workflow_runs']
assert body['total_count']==len(runs), 'run list is incomplete'
print('ready' if len(runs)>=int(sys.argv[2]) and all(r['status'] in ('success','failure','cancelled','skipped') for r in runs) else ','.join(r['status'] for r in runs) or 'empty')
CHECK
        )
        [ "$state" = ready ] && return
        sleep 2
    done
    echo "Expected $expected completed runs for $repo; got $state" >&2
    return 1
}
a -d '{"name":"triggers","auto_init":true,"default_branch":"main"}' "$API/user/repos" >/dev/null
put .forgejo/workflows/check.yml $'on: [push, pull_request]\njobs:\n  build:\n    runs-on: host\n    steps:\n      - run: echo both triggers\n  lint:\n    runs-on: host\n    steps:\n      - run: echo lint\n' "one workflow for two triggers" triggers >/dev/null
a -d '{"new_branch_name":"topic","old_branch_name":"main"}' "$API/repos/core/triggers/branches" >/dev/null
TRIGGERS_SHA=$(put trigger.txt "a change on the same repository branch" "a branch built twice" triggers topic)
a -d '{"head":"topic","base":"main","title":"two triggers"}' "$API/repos/core/triggers/pulls" >/dev/null
wait_complete triggers "$TRIGGERS_SHA" 2
a -d '{"name":"maven-failure","auto_init":true,"default_branch":"main"}' "$API/user/repos" >/dev/null
MAVEN_WORKFLOW=$(
    cat <<'WORKFLOW'
on: [push]
jobs:
  test:
    runs-on: host
    steps:
      - run: |
          echo '[INFO] Results:'
          echo '[INFO] '
          echo '[ERROR] Errors: '
          echo '[ERROR]   StandInReplayTest.aCommentSentToGitHubAndAReadAtOnceBothFinish:209->submission:138->push:110->call:87->call:96 » Varlink org.fuin.porelo.Tasks1.Failed {message=The fetched head of pull request 6 is not the one GitHub named}'
          echo '[ERROR]   StandInReplayTest.aCommentSentToGitHubIsOneResultThatGitHubsReplyJoins:281->submission:138->push:110->call:87->call:96 » Varlink org.fuin.porelo.Tasks1.Failed {message=The fetched head of pull request 6 is not the one GitHub named}'
          echo '[ERROR]   StandInReplayTest.aCommentWhoseLineDiffersFromItsThreadIsHeldAndTheRunPartial:161->forgeRun:115->run:120->push:110->call:87->call:96 » Varlink org.fuin.porelo.Tasks1.Failed {message=The fetched head of pull request 6 is not the one GitHub named}'
          echo '[ERROR]   StandInReplayTest.aForgedReplyToIsRefusedAndNothingIsWritten:180->submission:138->push:110->call:87->call:96 » Varlink org.fuin.porelo.Tasks1.Failed {message=The fetched head of pull request 6 is not the one GitHub named}'
          echo '[ERROR]   StandInReplayTest.aReplyToASentReplyGoesToItsRootOnGitHub:335->submission:138->push:110->call:87->call:96 » Varlink org.fuin.porelo.Tasks1.Failed {message=The fetched head of pull request 6 is not the one GitHub named}'
          echo '[ERROR]   StandInReplayTest.aSentCommentThatGitHubReadsBackOddlyIsNotHeld:377->submission:138->push:110->call:87->call:96 » Varlink org.fuin.porelo.Tasks1.Failed {message=The fetched head of pull request 6 is not the one GitHub named}'
          echo '[ERROR]   StandInReplayTest.pendingReadsGitHubAgainAfterItsPullRequestsWereStacked:268->call:87->call:96 » Varlink org.fuin.porelo.Tasks1.Failed {message=The fetched head of pull request 6 is not the one GitHub named}'
          echo '[INFO] '
          echo '[ERROR] Tests run: 52, Failures: 0, Errors: 7, Skipped: 0'
          exit 1
WORKFLOW
)
FAILED_TESTS_SHA=$(put .forgejo/workflows/test.yml "$MAVEN_WORKFLOW" "a failed test summary" maven-failure)
wait_complete maven-failure "$FAILED_TESTS_SHA" 1
LONG_SHA=$(put .forgejo/workflows/long.yml $'on: [push]\njobs:\n  long:\n    runs-on: host\n    steps:\n      - run: sleep 600\n' "a long build")
sleep 15
n=0
rec() {
    n=$((n + 1))
    local f
    f=$(printf '%s/%02d-%s' "$OUT" "$n" "$1")
    local auth=(-K "$W/auth.cfg")
    [ "${3:-auth}" = noauth ] && auth=()
    [ "${3:-auth}" = badauth ] && auth=(-H 'Authorization: token not-a-valid-token')
    curl -sS "${auth[@]}" -D "$f.headers" -o "$f.body" -w '%{http_code}\n' "$2" >"$f.status"
    echo "$1 $(cat "$f.status") ${2#"$BASE"}" >>"$OUT/index.txt"
}
body_of() {
    local matches=("$OUT"/*-"$1".body)
    if [ "${#matches[@]}" -ne 1 ] || [ ! -f "${matches[0]}" ]; then
        echo "Expected one capture body for $1; got ${#matches[@]}" >&2
        return 1
    fi
    printf '%s\n' "${matches[0]}"
}
rec head-main "$API/repos/core/probe/branches/main"
rec head-absent "$API/repos/core/probe/branches/no-such-branch"
rec repo "$API/repos/core/probe"
for c in ok:$OK_SHA fail:$FAIL_SHA long:$LONG_SHA wait:$WAIT_SHA none:0000000000000000000000000000000000000000; do
    rec "runs-${c%%:*}" "$API/repos/core/probe/actions/runs?head_sha=${c#*:}&limit=50&page=1"
done
for kind in ok fail long wait; do
    ids=$(python3 -c 'import json,sys; runs=json.load(open(sys.argv[1]))["workflow_runs"]; assert all(type(r["id"]) is int and r["id"]>0 for r in runs), "invalid run id"; [print(r["id"]) for r in runs]' "$(body_of "runs-$kind")")
    for id in $ids; do
        rec "jobs-$kind-$id" "$API/repos/core/probe/actions/runs/$id/jobs"
        job_ids=$(python3 -c 'import json,sys; jobs=json.load(open(sys.argv[1])); assert isinstance(jobs,list) and all(type(j["id"]) is int and j["id"]>0 for j in jobs), "invalid job array or id"; [print(j["id"]) for j in jobs]' "$(body_of "jobs-$kind-$id")")
        for j in $job_ids; do
            [ "$kind" = long ] || [ "$kind" = wait ] || rec "log-$kind-$j" "$API/repos/core/probe/actions/jobs/$j/logs"
        done
    done
done
for item in triggers:triggers:$TRIGGERS_SHA failed-tests:maven-failure:$FAILED_TESTS_SHA; do
    kind=${item%%:*}
    rest=${item#*:}
    repo=${rest%%:*}
    sha=${rest#*:}
    rec "runs-$kind" "$API/repos/core/$repo/actions/runs?head_sha=$sha&limit=50&page=1"
    capture=$(printf '%s/%02d-runs-%s.body' "$OUT" "$n" "$kind")
    ids=$(
        python3 -I - "$capture" <<'IDS'
import json,sys
for run in json.load(open(sys.argv[1]))['workflow_runs']:
    assert type(run['id']) is int and run['id']>0, 'invalid run id'
    print(run['id'])
IDS
    )
    for id in $ids; do
        rec "jobs-$kind-$id" "$API/repos/core/$repo/actions/runs/$id/jobs"
        capture=$(printf '%s/%02d-jobs-%s-%s.body' "$OUT" "$n" "$kind" "$id")
        job_ids=$(
            python3 -I - "$capture" <<'IDS'
import json,sys
jobs=json.load(open(sys.argv[1]))
assert isinstance(jobs,list), 'expected the Forgejo job array'
for job in jobs:
    assert type(job['id']) is int and job['id']>0, 'invalid job id'
    if job['status']=='failure':print(job['id'])
IDS
        )
        for job in $job_ids; do
            rec "log-$kind-$job" "$API/repos/core/$repo/actions/jobs/$job/logs"
        done
    done
done
# Cancel the long run, then read it again.
LID=$(python3 -c 'import json,sys; runs=json.load(open(sys.argv[1]))["workflow_runs"]; assert runs and type(runs[0]["id"]) is int and runs[0]["id"]>0, "invalid cancellation run id"; print(runs[0]["id"])' "$(body_of runs-long)")
a -X POST "$API/repos/core/probe/actions/runs/$LID/cancel" >/dev/null || true
sleep 10
rec runs-cancelled "$API/repos/core/probe/actions/runs?head_sha=$LONG_SHA&limit=50&page=1"
rec "jobs-cancelled-$LID" "$API/repos/core/probe/actions/runs/$LID/jobs"
rec bad-token "$API/repos/core/probe/branches/main" badauth
rec no-auth "$API/repos/core/probe/branches/main" noauth
rec no-repo "$API/repos/core/no-such-repository/branches/main"
rec version "$API/version" noauth
echo "shas ok=$OK_SHA fail=$FAIL_SHA long=$LONG_SHA wait=$WAIT_SHA triggers=$TRIGGERS_SHA failed-tests=$FAILED_TESTS_SHA" >>"$OUT/index.txt"
RECORDER_TOKEN="$TOKEN" python3 -I - "$OUT" <<'SCAN'
import sys,pathlib,os
token=os.environ.pop('RECORDER_TOKEN').encode()
exposed=[p.name for p in pathlib.Path(sys.argv[1]).iterdir() if p.is_file() and token in p.read_bytes()]
if exposed:raise SystemExit('REFUSED: capture contains its credential: '+', '.join(exposed))
SCAN
CAPTURE_DAY=$(date -u +%F)
export CAPTURE_DAY
python3 -I - "$OUT" <<'CONVERT'
import datetime,hashlib,json,os,pathlib,re,sys,urllib.parse

root=pathlib.Path(sys.argv[1])
day=os.environ['CAPTURE_DAY']
datetime.date.fromisoformat(day)
source='Forgejo 17.0.0 in podman, '+day
selectors={'triggers':'997951a163827581d3e30bc8319584a65d4603cc',
           'failed-tests':'2665984cb97ee201e778d0bb91d5191ec0256be4'}

def capture(name):
    matches=list(root.glob('*-'+name+'.body'))
    if len(matches)!=1:raise ValueError('Expected one capture for '+name+', got '+str(len(matches)))
    body=matches[0]
    prefix=body.with_suffix('')
    status=int(prefix.with_suffix('.status').read_text())
    if status!=200:raise ValueError('Expected HTTP 200 for '+name+', got '+str(status))
    headers={}
    for line in prefix.with_suffix('.headers').read_text().splitlines():
        key,sep,value=line.partition(':')
        if sep and key.lower()=='content-type':headers[key.lower()]=value.strip()
    index=[line.split(' ',2)[2] for line in (root/'index.txt').read_text().splitlines()
           if line.startswith(name+' ')]
    if len(index)!=1:raise ValueError('Expected one request path for '+name)
    return body.read_bytes(),headers,index[0]

def write(path,value):
    path.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n')

for case,selector in selectors.items():
    out=root/'recordings'/case
    out.mkdir(parents=True,exist_ok=False)
    original_repo='core/triggers' if case=='triggers' else 'core/maven-failure'
    expected={'jobs':[],'runs':[]}
    number=0
    def exchange(name,body=None,log=False):
        global number
        raw,headers,path=capture(name)
        path=path.removeprefix('/api/v1').replace('/repos/'+original_repo+'/', '/repos/porelo-ai/porelo-adapters/')
        path=re.sub(r'(?<=head_sha=)[0-9a-f]{40}',selector,path)
        number+=1
        file=f'{number:02}.json'
        entry={'recorded':True,'source':source,'capture':name,'method':'GET','path':path,'status':200,'headers':headers}
        if log:
            tail=raw[-65536:]
            (out/'failed-job.txt').write_bytes(tail)
            entry.update(bodyFile='failed-job.txt',reduction={'originalBytes':len(raw),'retainedBytes':len(tail),
                         'sha256':hashlib.sha256(raw).hexdigest(),'tailSha256':hashlib.sha256(tail).hexdigest()})
        else:
            entry['body']=json.dumps(body,separators=(',',':'),ensure_ascii=False)
        write(out/file,entry)
        return file,raw[-65536:]

    raw,_,_=capture('runs-'+case)
    body=json.loads(raw)
    runs=body['workflow_runs']
    if body['total_count']!=len(runs):raise ValueError('Run list is incomplete')
    if case=='triggers' and (len(runs)!=2 or {r.get('event') for r in runs}!={'push','pull_request'}
                            or len({r['workflow_id'] for r in runs})!=1):
        raise ValueError('Expected two runs of one workflow, triggered by push and pull_request; got '+repr(runs))
    if case=='failed-tests' and (len(runs)!=1 or runs[0]['status']!='failure'):
        raise ValueError('Expected exactly one failed Maven-summary run; got '+repr(runs))
    reduced=[]
    for run in runs:
        if type(run.get('id')) is not int or run['id']<=0:raise ValueError('Invalid run id')
        page=urllib.parse.urlsplit(run.get('html_url',''))
        if page.scheme!='https' or page.hostname!='forge.example' or page.username is not None:
            raise ValueError('Expected the configured HTTPS web host in run html_url; got '+run.get('html_url',''))
        reduced.append({k:run[k] for k in ('id','workflow_id','status','event','html_url') if k in run})
    run_file,_=exchange('runs-'+case,{'total_count':len(reduced),'workflow_runs':reduced})
    for index,run in enumerate(reduced):
        pointer=f'{run_file}#/workflow_runs/{index}'
        expected['runs'].append({'name':run['workflow_id'],'result':run['status'],'id':str(run['id']),
            'event':run['event'],'nameFrom':pointer+'/workflow_id','resultFrom':pointer+'/status',
            'idFrom':pointer+'/id','eventFrom':pointer+'/event'})
        raw,_,_=capture('jobs-'+case+'-'+str(run['id']))
        jobs=json.loads(raw)
        if not isinstance(jobs,list):raise ValueError('Expected a job array')
        if case=='failed-tests' and (len(jobs)!=1 or jobs[0].get('status')!='failure'):
            raise ValueError('Expected exactly one failed job for the Maven-summary capture; got '+str(len(jobs)))
        reduced_jobs=[{k:job[k] for k in ('id','name','status')} for job in jobs]
        job_file,_=exchange('jobs-'+case+'-'+str(run['id']),reduced_jobs)
        for job_index,job in enumerate(reduced_jobs):
            if type(job['id']) is not int or job['id']<=0:raise ValueError('Invalid job id')
            job_pointer=f'{job_file}#/{job_index}'
            each={'name':run['workflow_id']+' / '+job['name'],'result':job['status'],
                  'nameFrom':[pointer+'/workflow_id',job_pointer+'/name'],'resultFrom':job_pointer+'/status',
                  'run':str(run['id']),'runFrom':pointer+'/id',
                  'url':run['html_url']+'/jobs/'+str(job_index),'urlFrom':pointer+'/html_url'}
            if job['status']=='failure':
                log_file,tail=exchange('log-'+case+'-'+str(job['id']),log=True)
                each['log']=log_file
                names=[]
                refs=[]
                pending=[]
                block=False
                group=False
                log_lines=tail.decode('utf-8',errors='replace').split('\n')
                for line_index,line in enumerate(log_lines,1):
                    if len(line)>4096:continue
                    line=re.sub(r'\x1b\[[0-?]*[ -/]*[@-~]','',line)
                    line=re.sub(r'^\d{4}-\d{2}-\d{2}T[^ ]+ ','',line).rstrip('\r')
                    if line.rstrip()=='[INFO] Results:':block=True;group=False;pending=[]
                    elif block and line_index<len(log_lines) and re.fullmatch(r'\[(INFO|ERROR)\] Tests run: [0-9]+(?:,.*)?',line.rstrip()):
                        for name,ref in pending:
                            if name not in names and len(names)<50:names.append(name);refs.append(ref)
                        block=False
                    elif block and line.rstrip() in ('[ERROR] Errors:','[ERROR] Failures:'):group=True
                    elif block and 'Flakes:' in line:group=False
                    elif block and group:
                        match=re.match(r'\[ERROR\]   ([^\s:]+\.[^\s:]+):[0-9]',line)
                        if match:pending.append((match[1],f'{log_file}#line={line_index}'))
                if len(names)!=7:raise ValueError('Expected the seven echoed failed tests; got '+repr(names))
                each.update(failedTests=names,failedTestsFrom=refs)
            expected['jobs'].append(each)
    write(out/'expect.json',expected)
print('Contract recordings: '+str(root/'recordings'))
CONVERT
cat "$OUT/index.txt"
