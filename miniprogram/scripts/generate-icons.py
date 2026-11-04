from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1] / 'assets' / 'icons'
ROOT.mkdir(parents=True, exist_ok=True)
SCALE = 4
PALETTE = {
    'homework': ('#ffab53', '#ff7d35'), 'mistakes': ('#c285ff', '#9a55ed'),
    'students': ('#58a6ff', '#1677ff'), 'attendance': ('#4eaeff', '#1677ff'),
    'fitness': ('#44d3b5', '#15bda1'), 'points': ('#ffd258', '#ffaa19'),
    'lessons': ('#5c9fff', '#3479ff'), 'summaries': ('#59afff', '#2682ff'),
    'logo': ('#59aaff', '#1677ff'), 'upload': ('#65b1ff', '#2786ff'),
    'mic': ('#b88cff', '#9258e8'), 'empty': ('#c5d6eb', '#a4b9d6')
}


def icon(name, top, bottom):
    size = 96
    im = Image.new('RGBA', (size*SCALE, size*SCALE))
    mask = Image.new('L', im.size)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, size*SCALE-1, size*SCALE-1), radius=20*SCALE, fill=255)
    def rgb(color): return tuple(bytes.fromhex(color[1:]))
    a, b = rgb(top), rgb(bottom)
    draw = ImageDraw.Draw(im)
    for y in range(size*SCALE):
        t = y/(size*SCALE-1)
        draw.line((0,y,size*SCALE,y), fill=tuple(round(a[i]*(1-t)+b[i]*t) for i in range(3))+(255,))
    im.putalpha(mask)
    d = ImageDraw.Draw(im)
    def box(coords, radius=0, fill=None, outline='white', width=3):
        c = tuple(round(x*SCALE) for x in coords)
        if radius: d.rounded_rectangle(c, radius*SCALE, fill, outline, width*SCALE)
        else: d.rectangle(c, fill, outline, width*SCALE)
    def line(points, fill='white', width=3): d.line([(round(x*SCALE),round(y*SCALE)) for x,y in points],fill=fill,width=width*SCALE,joint='curve')
    def ellipse(coords, fill=None, outline='white', width=3): d.ellipse(tuple(round(x*SCALE) for x in coords),fill,outline,width*SCALE)
    if name in ('homework','logo'):
        box((26,22,70,74),7,fill=(255,255,255,240),outline=None)
        box((35,20,43,29),3,fill=bottom,outline=None)
        box((53,20,61,29),3,fill=bottom,outline=None)
        line([(36,42),(59,42)],bottom,3)
        line([(36,51),(54,51)],bottom,3)
        line([(37,62),(43,67),(58,57)],bottom,3)
    elif name == 'mistakes':
        d.polygon([(x*SCALE,y*SCALE) for x,y in [(23,25),(44,29),(48,34),(52,29),(73,25),(73,69),(52,72),(48,76),(44,72),(23,69)]],fill='white')
        line([(48,36),(48,68)],bottom,2)
        line([(30,38),(40,40)],bottom,2)
        line([(30,47),(40,49)],bottom,2)
        line([(56,39),(66,37)],bottom,2)
        line([(56,48),(66,46)],bottom,2)
    elif name == 'students':
        ellipse((35,23,59,47),fill='white',outline=None)
        box((28,51,67,74),12,fill='white',outline=None)
        ellipse((16,32,32,48),fill=(255,255,255,160),outline=None)
        ellipse((64,32,80,48),fill=(255,255,255,160),outline=None)
        box((15,53,26,69),5,fill=(255,255,255,160),outline=None)
        box((69,53,80,69),5,fill=(255,255,255,160),outline=None)
    elif name == 'attendance':
        box((24,27,72,73),8,fill='white',outline=None)
        line([(25,40),(71,40)],bottom,3)
        line([(36,21),(36,32)],'white',4)
        line([(60,21),(60,32)],'white',4)
        line([(36,55),(44,63),(61,48)],bottom,4)
    elif name == 'fitness':
        ellipse((52,21,63,32),fill='white',outline=None)
        line([(52,39),(42,51),(53,58),(42,75)],'white',6)
        line([(50,40),(64,48),(73,43)],'white',5)
        line([(43,51),(31,58),(22,57)],'white',5)
        line([(51,58),(66,67)],'white',5)
    elif name == 'points':
        box((32,24,64,53),5,fill='white',outline=None)
        line([(33,30),(23,30),(24,44),(33,48)],'white',4)
        line([(63,30),(73,30),(72,44),(63,48)],'white',4)
        line([(48,53),(48,67)],'white',5)
        box((35,67,61,73),3,fill='white',outline=None)
        d.polygon([(x*SCALE,y*SCALE) for x,y in [(48,31),(51,37),(58,38),(53,43),(54,49),(48,46),(42,49),(43,43),(38,38),(45,37)]],fill=bottom)
    elif name == 'lessons':
        box((24,28,72,69),7,fill='white',outline=None)
        line([(26,39),(70,39)],bottom,3)
        line([(35,22),(35,33)],'white',4)
        line([(61,22),(61,33)],'white',4)
        for x in (36,48,60):
            for y in (48,58): box((x-2,y-2,x+2,y+2),1,fill=bottom,outline=None)
    elif name in ('summaries','empty'):
        box((27,21,69,75),7,fill='white',outline=None)
        line([(37,35),(58,35)],bottom,3)
        line([(37,45),(58,45)],bottom,3)
        line([(37,55),(52,55)],bottom,3)
        ellipse((55,58,74,77),fill=bottom,outline='white',width=2)
        line([(60,67),(64,71),(70,64)],'white',2)
    elif name == 'upload':
        box((24,28,72,68),8,outline='white',width=3)
        ellipse((53,36,61,44),fill='white',outline=None)
        line([(29,61),(41,47),(52,58),(58,53),(68,63)],width=3)
    elif name == 'mic':
        box((39,22,57,53),9,fill='white',outline=None)
        d.arc((30*SCALE,34*SCALE,66*SCALE,64*SCALE),0,180,fill='white',width=3*SCALE)
        line([(48,64),(48,73)],width=3)
        line([(38,74),(58,74)],width=3)
    im.resize((144,144),Image.Resampling.LANCZOS).save(ROOT/(name+'.png'))


for name, colors in PALETTE.items(): icon(name,*colors)

for name in ('home','grid','user'):
    for active in (False,True):
        im=Image.new('RGBA',(96*SCALE,96*SCALE))
        d=ImageDraw.Draw(im)
        color='#1677ff' if active else '#8e9eb8'
        def line(points,width=5): d.line([(int(x*SCALE),int(y*SCALE)) for x,y in points],fill=color,width=width*SCALE,joint='curve')
        if name=='home':
            line([(20,45),(48,22),(76,45)])
            line([(28,42),(28,73),(42,73),(42,55),(54,55),(54,73),(68,73),(68,42)])
        elif name=='grid':
            for x,y in [(24,24),(54,24),(24,54),(54,54)]: d.rounded_rectangle((x*SCALE,y*SCALE,(x+19)*SCALE,(y+19)*SCALE),4*SCALE,outline=color,width=4*SCALE)
        else:
            d.ellipse((36*SCALE,21*SCALE,60*SCALE,45*SCALE),outline=color,width=4*SCALE)
            d.arc((23*SCALE,48*SCALE,73*SCALE,90*SCALE),180,360,fill=color,width=4*SCALE)
        im.resize((72,72),Image.Resampling.LANCZOS).save(ROOT/(name+('-active' if active else '')+'.png'))
print('Generated',len(list(ROOT.glob('*.png'))),'local UI icons')
