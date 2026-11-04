"""Read-only HTTP/ownership smoke check for the running local service."""
import json,subprocess,urllib.request,urllib.error
from pathlib import Path

root=Path(__file__).resolve().parent.parent
status=json.loads(subprocess.check_output(['bash',str(root/'scripts/ocr-local.sh'),'status'],text=True))
assert status['managed'] and status['health']['available'],status
key=(root/'.run/ocr.key').read_text().strip()
http=urllib.request.build_opener(urllib.request.ProxyHandler({}))
for method,path,credential,body,expected in [('GET','/health','invalid',None,403),('GET','/health',key,None,200),('POST','/recognize',key,b'{"image":null}',400)]:
    request=urllib.request.Request('http://127.0.0.1:18086'+path,method=method,data=body,headers={'X-OCR-Key':credential,'Content-Type':'application/json'})
    try:
        with http.open(request,timeout=5) as response:actual=response.status
    except urllib.error.HTTPError as error:actual=error.code;error.close()
    assert actual==expected,(method,path,actual,expected)
print('通过：本地进程归属、就绪、内部凭据与无效图片请求检查')
