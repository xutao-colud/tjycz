"""Check launcher archive isolation and PID ownership without touching a running service."""
from pathlib import Path
import json
import sys
import tempfile
from unittest.mock import patch

source=(Path(__file__).with_name('backend-local.sh')).read_text().split("<<'PY'\n",1)[1].rsplit('\nPY',1)[0].rsplit('\ntry:\n',1)[0]
with tempfile.TemporaryDirectory(prefix='backend-launcher-check-') as directory:
    root=Path(directory);namespace={}
    with patch.object(sys,'argv',['backend-local.sh',str(root)]):exec(compile(source,'backend-local-fixture','exec'),namespace)
    jar=namespace['JAR'];runtime=namespace['RUNTIME_JAR'];jar.parent.mkdir(parents=True);runtime.parent.mkdir(parents=True)
    jar.write_bytes(b'fixture version one');namespace['prepare_runtime']();jar.write_bytes(b'fixture version two')
    assert runtime.read_bytes()==b'fixture version one','a new build must not overwrite the loaded runtime archive'
    assert namespace['command_for']('/usr/bin/java','a'*32)[3]==str(runtime)
    record={'pid':4242,'java':'/usr/bin/java','token':'a'*32,'started':'fixture-start'}
    namespace['PID_FILE'].write_text('4242')
    for archive,accepted in [(None,True),(str(runtime),True),(str(root/'unrelated.jar'),False)]:
        value=dict(record)
        if archive is not None:value['jar']=archive
        namespace['OWNER_FILE'].write_text(json.dumps(value))
        command=' '.join(namespace['command_for'](record['java'],record['token'],archive or jar))
        namespace['process_field']=lambda pid,field:command if field=='command' else record['started'] if field=='lstart' else ''
        assert (namespace['owner']()[0] is not None)==accepted,'runtime and legacy ownership must validate; unrelated archives must be refused'
    namespace['OWNER_FILE'].write_text(json.dumps({**record,'jar':str(runtime)}))
    namespace['process_field']=lambda pid,field:'different-process'
    assert namespace['owner']()[0] is None,'a stale PID or changed process must never be accepted'
print('PASS: running archive isolation; runtime/legacy ownership; unrelated archive and stale PID rejection')
