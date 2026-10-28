"""Keep one local API/Whisper process running; stop via a per-run control marker."""
import argparse
from contextlib import contextmanager
import json
import os
from pathlib import Path
import secrets
import signal
import socket
import subprocess
import sys
import time

BACKEND = Path(__file__).resolve().parents[1]
STATE = BACKEND / 'instance/validation'


@contextmanager
def service_lock(path):
    with path.open('a+b') as handle:
        handle.seek(0)
        if not handle.read(1):
            handle.write(b'0')
            handle.flush()
        handle.seek(0)
        if os.name == 'nt':
            import msvcrt
            msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        try:
            yield
        finally:
            handle.seek(0)
            if os.name == 'nt':
                msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(handle, fcntl.LOCK_UN)


def write_state(path, value):
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(value) + '\n', encoding='utf-8')
    temporary.replace(path)


def run(service, python, env_file, port):
    from dotenv import dotenv_values
    if not python.is_file() or not env_file.is_file() or not 1 <= port <= 65535:
        raise ValueError('Check interpreter, environment file and port')
    identity = json.loads(subprocess.check_output([str(python), '-c',
        'import json,sys,sysconfig; print(json.dumps([sys._base_executable,sysconfig.get_path("purelib")]))'], text=True))
    STATE.mkdir(parents=True, exist_ok=True)
    with service_lock(STATE / f'{service}.lock'):
        with socket.socket() as probe:
            probe.settimeout(1)
            if probe.connect_ex(('127.0.0.1', port)) == 0:
                raise ValueError('Port already occupied; supervisor will not stop an unknown process')
        state_file, stop_file = STATE / f'{service}.json', STATE / f'stop-{service}.json'
        state = {'service': service, 'run_id': secrets.token_hex(16), 'supervisor_pid': os.getpid(),
                 'child_pid': None, 'restarts': 0, 'running': True}
        stopping = False
        def stop_signal(*_):
            nonlocal stopping
            stopping = True
        signal.signal(signal.SIGINT, stop_signal)
        signal.signal(signal.SIGTERM, stop_signal)
        def requested_stop():
            if stopping:
                return True
            try:
                return json.loads(stop_file.read_text())['run_id'] == state['run_id']
            except (OSError, ValueError, KeyError):
                return False
        child = None
        try:
            while not requested_stop():
                values = dotenv_values(env_file)
                env = {**os.environ, **{k: v for k, v in values.items() if v is not None}}
                env['PYTHONPATH'] = identity[1]
                module = 'app.main:app' if service == 'api' else 'inference.whisper_stt:app'
                args = [identity[0], '-m', 'uvicorn', module, '--host', '127.0.0.1',
                        '--port', str(port), '--workers', '1', '--no-access-log', '--no-proxy-headers']
                if service == 'api':
                    args.extend(['--log-config', 'logging.json'])
                with (STATE / f'{service}.stdout.log').open('ab') as out, (STATE / f'{service}.stderr.log').open('ab') as err:
                    child = subprocess.Popen(args, cwd=BACKEND, env=env, stdout=out, stderr=err,
                        creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
                state.update(child_pid=child.pid, started_at=int(time.time()))
                write_state(state_file, state)
                while child.poll() is None and not requested_stop():
                    time.sleep(0.5)
                if requested_stop():
                    break
                state['restarts'] += 1
                write_state(state_file, state)
                deadline = time.monotonic() + min(30, 2 ** min(state['restarts'], 5))
                while time.monotonic() < deadline and not requested_stop():
                    time.sleep(0.5)
        finally:
            if child is not None and child.poll() is None:
                child.terminate()
                child.wait(timeout=20)
            state.update(running=False, child_pid=None)
            write_state(state_file, state)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['run', 'stop'])
    parser.add_argument('service', choices=['api', 'stt'])
    parser.add_argument('--python', type=Path, default=Path(sys.executable))
    parser.add_argument('--env-file', type=Path)
    parser.add_argument('--port', type=int)
    args = parser.parse_args()
    try:
        if args.command == 'stop':
            state = json.loads((STATE / f'{args.service}.json').read_text())
            write_state(STATE / f'stop-{args.service}.json', {'run_id': state['run_id']})
            print('Stop requested for this supervisor run only.')
        else:
            env_file = args.env_file or (STATE / '.env' if args.service == 'api' else BACKEND / 'inference/.env')
            run(args.service, args.python.resolve(), env_file.resolve(), args.port or (8001 if args.service == 'api' else 8091))
        return 0
    except Exception as error:
        print('SUPERVISOR_ERROR: ' + type(error).__name__ + '. Check local configuration, lock and service logs.')
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
