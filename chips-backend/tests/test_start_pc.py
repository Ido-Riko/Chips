"""Exercise startup ordering/failures without altering host services or requiring sudo."""
import json
import os
from pathlib import Path
import subprocess
import pytest

SCRIPT = Path(__file__).resolve().parents[1] / "deploy/start-pc.sh"


@pytest.fixture
def startup(tmp_path):
    commands = tmp_path / "bin"
    commands.mkdir()
    log = tmp_path / "commands.log"
    state = tmp_path / "vpn"
    state.write_text("Running")
    fake = commands / "fake"
    fake.write_text('''#!/usr/bin/env python3
import json,os,sys
from pathlib import Path
name=Path(sys.argv[0]).name
args=sys.argv[1:]
with open(os.environ['CHIPS_TEST_LOG'],'a') as f: f.write(json.dumps([name,*args])+'\\n')
if name=='sudo': os.execvp(args[0],args)
if name=='systemctl' and args[:2]==['is-active','--quiet']:
    sys.exit(int(os.environ.get('CHIPS_TEST_DAEMON_STOPPED','0')))
if name=='tailscale':
    state=Path(os.environ['CHIPS_TEST_VPN'])
    if args[0]=='up': state.write_text('Running')
    if args[0]=='status': print(json.dumps({'BackendState':state.read_text(),'Self':{'DNSName':'chips.test.ts.net.'}}))
if name=='curl' and os.environ.get('CHIPS_TEST_HTTP_FAIL')=='1': sys.exit(22)
''')
    fake.chmod(0o755)
    for name in ['systemctl','sudo','tailscale','curl','sleep']:
        (commands / name).symlink_to(fake)
    env = os.environ | {'PATH': str(commands)+os.pathsep+os.environ['PATH'],
                        'CHIPS_TEST_LOG':str(log),'CHIPS_TEST_VPN':str(state)}
    def run(**overrides):
        result = subprocess.run(['bash',str(SCRIPT)],env=env | overrides,text=True,capture_output=True)
        calls = [json.loads(line) for line in log.read_text().splitlines()]
        return result,calls
    return run,state


def test_running_services_are_not_restarted_or_reset(startup):
    run,_=startup
    result,calls=run()
    assert result.returncode==0,result.stderr
    assert not any(c[0]=='sudo' or 'restart' in c or '--reset' in c for c in calls)
    assert ['tailscale','serve','--bg','--https=8443','http://127.0.0.1:8000'] in calls
    assert 'https://chips.test.ts.net:8443/api/v1' in result.stdout
    assert any(c[0]=='curl' and c[-1]=='https://chips.test.ts.net:8443/health' for c in calls)


def test_stopped_vpn_is_started_before_user_services(startup):
    run,state=startup
    state.write_text('Stopped')
    result,calls=run(CHIPS_TEST_DAEMON_STOPPED='1')
    assert result.returncode==0,result.stderr
    assert ['sudo','systemctl','start','tailscaled.service'] in calls
    assert ['sudo','tailscale','up','--timeout=30s'] in calls
    assert calls.index(['tailscale','up','--timeout=30s']) < next(i for i,c in enumerate(calls) if c[:3]==['systemctl','--user','start'])


def test_failed_service_does_not_report_success_or_change_proxy(startup):
    run,_=startup
    result,calls=run(CHIPS_TEST_HTTP_FAIL='1')
    assert result.returncode!=0
    assert 'did not become ready' in result.stderr
    assert not any(c[:2]==['tailscale','serve'] for c in calls)
    assert 'Chips is running' not in result.stdout
