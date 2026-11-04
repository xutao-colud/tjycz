import base64
import io
import json
import os
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

import numpy as np
from PIL import Image

with tempfile.NamedTemporaryFile(mode='w') as key:
    key.write('test-only-internal-key-32-characters');key.flush()
    with patch.dict(os.environ, {'OCR_KEY_FILE': key.name}):
        import server


def rectangle(x,y,w,h):
    return np.array([[x,y],[x+w,y],[x+w,y+h],[x,y+h]])


class LocatorTest(unittest.TestCase):
    def test_startup_warms_once_and_reports_unavailable_if_warmup_fails(self):
        for failure in (False,True):
            models=SimpleNamespace(DocPreprocessor=Mock(),LayoutDetection=Mock(),TextDetection=Mock(),TextRecognition=Mock())
            with patch.dict(sys.modules,{'paddleocr':models}),patch.object(server,'PIPELINE',None),patch.object(server,'STARTUP_ERROR',''),patch.object(server,'warm_models') as warm:
                if failure:warm.side_effect=RuntimeError('fixture calibration failure')
                server.load_models();warm.assert_called_once()
                if failure:self.assertIsNone(server.PIPELINE);self.assertTrue(server.STARTUP_ERROR)
                else:self.assertEqual(len(server.PIPELINE),4);self.assertEqual(server.STARTUP_ERROR,'')
    def test_printed_headings_body_and_table_are_returned_without_handwritten_answers(self):
        page=np.full((1000,1000,3),245,dtype=np.uint8)
        polys=[rectangle(100,50,700,30),rectangle(105,300,600,30),
               rectangle(170,400,500,90),rectangle(170,450,180,30),
               rectangle(100,550,710,30),rectangle(700,620,200,30),
               rectangle(100,800,130,30),rectangle(110,850,720,30)]
        text_by_width={700:'3.(1)根据图示选择条件。',600:'(2)列式解答。',180:'=27+30',
            710:'4.3位家长带6名儿童，怎样买票？',200:'成人票：每人9元',
            130:'【附加题】',720:'桌上有3个纸盒，请说明理由。'}
        rec=Mock();rec.predict.side_effect=lambda crops,**kwargs:[{'rec_text':text_by_width[crop.shape[1]],'rec_score':.99} for crop in crops]
        models={
            'orientation':Mock(predict=Mock(return_value=[{'output_img':page}])),
            'layout':Mock(predict=Mock(return_value=[{'boxes':[
                {'label':'paragraph_title','score':.9,'coordinate':[95,545,805,585]},
                {'label':'number','score':.9,'coordinate':np.array([450,950,550,975],dtype=np.float32)}]}])),
            'detection':Mock(predict=Mock(return_value=[{'dt_polys':polys}])),
            'recognition':rec,
        }
        image=io.BytesIO();Image.fromarray(page).save(image,format='PNG')
        with patch.object(server,'PIPELINE',models):result=server.recognize(image.getvalue())
        self.assertEqual([q['number'] for q in result['questions']],['3','4','附加题'])
        self.assertIn('根据图示',result['questions'][0]['text'])
        self.assertIn('(2)列式',result['questions'][0]['text'])
        self.assertIn('家长带6名儿童',result['questions'][1]['text'])
        self.assertIn('成人票',result['questions'][1]['text'])
        self.assertIn('纸盒',result['questions'][2]['text'])
        self.assertEqual(result['questions'][1]['group'],'3位家长带6名儿童，怎样买票？')
        self.assertTrue(all('=27+30' not in q['text'] for q in result['questions']))
        self.assertEqual(len(rec.predict.call_args.args[0]),7)
        self.assertEqual(max(crop.shape[1] for crop in rec.predict.call_args.args[0]),720)
        self.assertEqual(models['detection'].predict.call_args.kwargs['limit_type'],'max')
        self.assertLess(result['questions'][-1]['regions'][0]['y']+result['questions'][-1]['regions'][0]['height'],.95)
        normalized=Image.open(io.BytesIO(base64.b64decode(result['pageImage'])))
        self.assertEqual(normalized.getpixel((200,400)),(245,245,245))
        json.dumps(result)

    def test_no_detected_boundary_keeps_the_page_for_manual_framing(self):
        page=np.full((1000,1000,3),255,dtype=np.uint8)
        rec=Mock()
        models={
            'orientation':Mock(predict=Mock(return_value=[{'output_img':page}])),
            'layout':Mock(predict=Mock(return_value=[{'boxes':[]}])),
            'detection':Mock(predict=Mock(return_value=[{'dt_polys':[]}])),
            'recognition':rec,
        }
        image=io.BytesIO();Image.fromarray(page).save(image,format='PNG')
        with patch.object(server,'PIPELINE',models):result=server.recognize(image.getvalue())
        rec.predict.assert_not_called()
        self.assertEqual(result['questions'][0]['group'],'手工框题')
        self.assertEqual(result['questions'][0]['text'],'')
        self.assertTrue(result['questions'][0]['warnings'])


if __name__=='__main__':unittest.main()
