"""Private, single-inference PaddleOCR service. No external image URLs or cloud model calls."""
import base64
import hmac
import io
import json
import math
import os
import signal
import sys
from pathlib import Path
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

os.environ.setdefault('PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK','True')
os.environ.setdefault('PADDLE_PDX_MODEL_SOURCE','BOS')
from PIL import Image,ImageOps,ImageDraw,ImageFont
from grouping import group_questions, printed_boxes, ANSWER

Image.MAX_IMAGE_PIXELS=20_000_000
LOCK=threading.Lock()
PIPELINE=None
STARTUP_ERROR=''
KEY=Path(os.environ['OCR_KEY_FILE']).read_text().strip()
if len(KEY)<32:
    raise RuntimeError('OCR key must contain at least 32 characters')


def load_models():
    global PIPELINE,STARTUP_ERROR
    STARTUP_ERROR=''
    try:
        from paddleocr import DocPreprocessor,LayoutDetection,TextDetection,TextRecognition
        options={'device':'cpu','cpu_threads':int(os.getenv('OCR_CPU_THREADS','4')),'enable_mkldnn':False}
        PIPELINE={
            'orientation':DocPreprocessor(use_doc_orientation_classify=True,use_doc_unwarping=False,**options),
            'layout':LayoutDetection(model_name='PP-DocLayout-S',**options),
            'detection':TextDetection(model_name='PP-OCRv5_mobile_det',**options),
            'recognition':TextRecognition(model_name='PP-OCRv6_tiny_rec',**options),
        }
        warm_models()
        print('OCR models ready',flush=True)
    except Exception:
        import traceback
        PIPELINE=None
        STARTUP_ERROR='识别模型加载失败，请检查服务日志'
        traceback.print_exc()


def warm_models():
    page=Image.new('RGB',(1024,1365),'white')
    draw=ImageDraw.Draw(page);font=ImageFont.load_default(size=28)
    for index,text in enumerate(('1. What is 12 plus 8?','Choose the correct answer.','2. Read the following question.','Write down your method.')):
        draw.text((60,60+index*160),text,font=font,fill='black')
    output=io.BytesIO();page.save(output,format='PNG');recognize(output.getvalue())


def recognize(raw):
    if len(raw)>20*1024*1024:
        raise ValueError('图片超过20MB')
    with Image.open(io.BytesIO(raw)) as source:
        if source.width*source.height>20_000_000:
            raise ValueError('图片分辨率超过2000万像素')
        image=ImageOps.exif_transpose(source).convert('RGB')
    image.thumbnail((2600,2600))
    import numpy as np
    start=time.monotonic()
    page=PIPELINE['orientation'].predict(np.array(image)[:,:,::-1].copy())[0]['output_img']
    image=Image.fromarray(np.asarray(page)[:,:,::-1].astype('uint8'))
    layout=PIPELINE['layout'].predict(page)[0]['boxes']
    polygons=PIPELINE['detection'].predict(page,limit_side_len=1280,limit_type='max')[0]['dt_polys']
    boxes=[]
    for poly in polygons:
        xy=np.asarray(poly)
        boxes.append([float(xy[:,0].min()),float(xy[:,1].min()),float(xy[:,0].max()),float(xy[:,1].max())])
    selected=printed_boxes(boxes,image.width,image.height)
    # Similar line widths reduce padding within each recognition batch.
    selected.sort(key=lambda b:(b[2]-b[0])/max(1,b[3]-b[1]))
    crops=[]
    for x,y,right,bottom in selected:
        crops.append(page[max(0,int(y)):min(image.height,math.ceil(bottom)),max(0,int(x)):min(image.width,math.ceil(right))])
    lines=[]
    recognized=PIPELINE['recognition'].predict(crops,batch_size=4) if crops else []
    for box,result in zip(selected,recognized):
        text=str(result['rec_text']);score=float(result['rec_score'])
        if score>=.8 and not ANSWER.match(text):
            lines.append({'text':text,'score':score,'box':box})
    questions=group_questions(lines,image.width,image.height,layout)
    buffer=io.BytesIO();image.save(buffer,format='PNG',compress_level=1)
    return {'engine':'PaddleOCR 3.7 / PP-OCRv6-tiny-rec / 印刷题干识别','width':image.width,'height':image.height,
            'pageImage':base64.b64encode(buffer.getvalue()).decode(),'questions':questions,'seconds':round(time.monotonic()-start,2)}


class Handler(BaseHTTPRequestHandler):
    def log_message(self,format,*args):
        # Do not log images, credentials or request bodies.
        print(self.command,self.path.split('?')[0],args[1] if len(args)>1 else '',flush=True)

    def reply(self,status,value):
        encoded=json.dumps(value,ensure_ascii=False).encode()
        self.send_response(status);self.send_header('Content-Type','application/json; charset=utf-8')
        self.send_header('Content-Length',str(len(encoded)));self.end_headers();self.wfile.write(encoded)

    def authorized(self):
        if not hmac.compare_digest(self.headers.get('X-OCR-Key',''),KEY):
            self.reply(403,{'message':'服务凭据无效'});return False
        return True

    def do_GET(self):
        if not self.authorized():return
        if self.path!='/health':return self.reply(404,{'message':'接口不存在'})
        self.reply(200,{'available':PIPELINE is not None,'engine':'PaddleOCR / 印刷题干识别',
            'message':STARTUP_ERROR or ('专用识别服务已就绪' if PIPELINE is not None else '识别模型正在加载')})

    def do_POST(self):
        if not self.authorized():return
        if self.path!='/recognize':return self.reply(404,{'message':'接口不存在'})
        if PIPELINE is None:return self.reply(503,{'message':STARTUP_ERROR or '模型正在加载'})
        if not LOCK.acquire(blocking=False):return self.reply(429,{'message':'识别服务繁忙，请稍后重试'})
        try:
            length=int(self.headers.get('Content-Length','0'))
            if length<=0 or length>28*1024*1024:raise ValueError('图片请求大小无效')
            self.connection.settimeout(30)
            body=json.loads(self.rfile.read(length))
            if not isinstance(body,dict) or not isinstance(body.get('image'),str):raise ValueError('图片字段无效')
            raw=base64.b64decode(body['image'],validate=True)
            self.reply(200,recognize(raw))
        except (ValueError,KeyError,Image.DecompressionBombError,Image.UnidentifiedImageError):
            self.reply(400,{'message':'图片内容无效，请使用清晰的JPEG或PNG照片'})
        except Exception:
            import traceback
            traceback.print_exc();self.reply(500,{'message':'识别失败，请查看服务日志并重试'})
        finally:LOCK.release()


if __name__=='__main__':
    # Install process handlers after Paddle initialization so its native handlers cannot swallow shutdown.
    load_models()
    signal.signal(signal.SIGTERM,lambda *_:sys.exit(0))
    signal.signal(signal.SIGINT,lambda *_:sys.exit(0))
    with ThreadingHTTPServer((os.getenv('OCR_HOST','127.0.0.1'),int(os.getenv('OCR_PORT','18086'))),Handler) as server:
        server.daemon_threads=True
        server.serve_forever()
