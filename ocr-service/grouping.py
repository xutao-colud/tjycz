"""Group actual OCR lines into reviewable questions; coordinates refer to the corrected page."""
import re
from statistics import median

ROOT = re.compile(r"^\s*(\d{1,3})\s*(?:[.．](?!\d)|[、)）])")
TIGHT_ROOT = re.compile(r"^\s*(\d{1,3})[.．](?=\d)")
CHILD = re.compile(r"^\s*[（(]\s*(\d{1,2})\s*[)）]")
BONUS = re.compile(r"^\s*[【\[(（]?\s*附加题")
SECTION = re.compile(r"^\s*(?:[一二三四五六七八九十]+[、．.]|第.{1,8}(?:单元|部分))")
READING = re.compile(r"阅读|短文|文章|材料|古诗")
PAGE_COUNTER = re.compile(r"^\s*(?:共\s*\d+\s*页|第\s*\d+\s*页|[Pp]age\s*\d+)")
ANSWER = re.compile(r"^\s*(?:[=＝]|答\s*[:：])")


def printed_boxes(boxes, width, height):
    heights = sorted(b[3]-b[1] for b in boxes if b[2]-b[0] > width*.16 and (b[2]-b[0])/max(1,b[3]-b[1]) > 5)
    normal = median(heights[:max(1,len(heights)*2//3)]) if heights else height*.025
    # shortcut: line geometry cannot separate overlapping print and handwriting; mixed lines need teacher review.
    return sorted([b for b in boxes if normal*.5 <= b[3]-b[1] <= normal*1.65 and b[2]-b[0] >= (b[3]-b[1])*2],key=lambda b:(b[1],b[0]))


def group_questions(lines, width, height, layout=()):
    lines = [dict(line) for line in lines if line['text'].strip() and line['box'][2] > line['box'][0]]
    margins = [item['coordinate'] for item in layout if item.get('label') in ('header','footer','page_number') and item.get('score',0) >= .7]
    margins += [item['coordinate'] for item in layout if item.get('label') == 'number' and item.get('score',0) >= .7 and item['coordinate'][1] > height*.9]
    margins += [line['box'] for line in lines if line['box'][1] > height*.9 and PAGE_COUNTER.match(line['text'])]
    page_bottom = min([height] + [box[1] for box in margins if box[1] > height*.85])
    lines = [line for line in lines if not any(box[0] <= (line['box'][0]+line['box'][2])/2 <= box[2] and box[1] <= (line['box'][1]+line['box'][3])/2 <= box[3] for box in margins)]
    if not lines:
        return [candidate([], [0, 0, width, height], width, height, '1', '手工框题', ['未读取到清晰文字，请调整题框并手工校对'])]
    anchors = [line for line in lines if ROOT.match(line['text']) or CHILD.match(line['text'])]
    # A second column needs numbered lines on both sides, with no line spanning the gap at the same height.
    right = [line for line in anchors if line['box'][0] > width * .48]
    left = [line for line in anchors if line['box'][0] < width * .35]
    numbered_top = min((line['box'][1] for line in anchors), default=height)
    spanning = any(line['box'][0] < width*.35 and line['box'][2] > width*.65 and line['box'][1] >= numbered_top for line in lines)
    split = width * .5 if right and left and not spanning and any(abs(a['box'][1] - b['box'][1]) < height * .25 for a in left for b in right) else None
    columns = [(0, split), (split, width)] if split else [(0, width)]
    result = []
    for x1, x2 in columns:
        ordered = sorted([line for line in lines if x1 <= (line['box'][0]+line['box'][2])/2 < x2], key=lambda line:(line['box'][1],line['box'][0]))
        if not ordered:
            continue
        sections = [line for line in ordered if SECTION.match(line['text'])]
        boundaries = [0] + [line['box'][1] for line in sections] + [page_bottom]
        boundaries = sorted(set(boundaries))
        for start, end in zip(boundaries, boundaries[1:]):
            block = [line for line in ordered if start <= line['box'][1] < end]
            if not block:
                continue
            title = next((line['text'][:100] for line in block if SECTION.match(line['text'])), '')
            root_anchors = []
            in_bonus = False
            for line in block:
                match = ROOT.match(line['text'])
                if BONUS.match(line['text']):
                    line['_number'] = '附加题';root_anchors.append(line);in_bonus = True
                elif match and not in_bonus:
                    if any(anchor['_number']==match.group(1) and line['box'][0]-anchor['box'][0]>width*.08 for anchor in root_anchors):
                        continue
                    line['_number'] = match.group(1);root_anchors.append(line)
            # Recover "4.3位…" only with an aligned sequential question and a detected paragraph heading.
            for line in block:
                match = TIGHT_ROOT.match(line['text'])
                if not match or any(BONUS.match(anchor['text']) and anchor['box'][1] < line['box'][1] for anchor in root_anchors):
                    continue
                number = int(match.group(1));x,y,right,bottom = line['box'];cx,cy = (x+right)/2,(y+bottom)/2
                sequence = any(anchor['_number'].isdigit() and abs(anchor['box'][0]-x) < width*.04 and ((anchor['box'][1]<y and int(anchor['_number'])==number-1) or (anchor['box'][1]>y and int(anchor['_number'])==number+1)) for anchor in root_anchors)
                heading = any(item.get('label') in ('paragraph_title','title') and item.get('score',0)>=.5 and item['coordinate'][0]<=cx<=item['coordinate'][2] and item['coordinate'][1]<=cy<=item['coordinate'][3] for item in layout)
                if sequence and heading:
                    line['_number'] = match.group(1);line['_tight'] = True;root_anchors.append(line)
            root_anchors.sort(key=lambda line:line['box'][1])
            child_anchors = [line for line in block if CHILD.match(line['text'])]
            starts = root_anchors or child_anchors
            if not starts:
                continue
            preamble = [line for line in block if line['box'][1] < starts[0]['box'][1] and not SECTION.match(line['text'])]
            shared = preamble if READING.search(title + ''.join(line['text'] for line in preamble)) and len(preamble) >= 2 else []
            shared_box = content_box(shared, x1, x2) if shared else None
            if not shared_box and READING.search(title) and starts[0]['box'][1]-start > height*.05:
                shared_box = [x1+8,start,x2-8,starts[0]['box'][1]-8]
            for index, anchor in enumerate(starts):
                top = anchor['box'][1] - 8
                bottom = starts[index+1]['box'][1] - 8 if index+1 < len(starts) else end - 6
                content = [line for line in block if top <= line['box'][1] < bottom]
                number = anchor.get('_number') or CHILD.match(anchor['text']).group(1)
                heading = re.sub(r'^\s*'+re.escape(number)+r'\s*[.．、)）]\s*','',anchor['text']).strip()
                group = '附加题' if number == '附加题' else title or heading or '题目'
                box = [x1+8,max(0,top),x2-8,min(height,bottom)]
                entry = candidate(content,box,width,height,number,group,['题号与正文数字相连，已结合版面和相邻题号分题，请核对'] if anchor.get('_tight') else [])
                if anchor.get('_tight'):
                    entry['text'] = re.sub(r'^\s*'+re.escape(number)+r'[.．]',number+'. ',entry['text'],count=1)
                if shared_box and number != '附加题':
                    entry['regions'].insert(0,region(shared_box,width,height))
                    entry['text']='\n'.join(line['text'] for line in shared)+'\n'+entry['text']
                    entry['warnings'].append('含共享阅读材料，请核对范围')
                result.append(entry)
    if not result:
        return [candidate(lines,[0,0,width,height],width,height,'1','手工框题',['未找到可靠题号，已保留整页，请手动框题或拆分'])]
    if len(result)>40:
        return [candidate(lines,[0,0,width,height],width,height,'1','手工框题',['题目超过单页40题上限，请裁小页面后重新扫描或手工拆分'])]
    figures = [item['coordinate'] for item in layout if item.get('label') in ('image','figure','table','formula','display_formula') and item.get('score',0) >= .5]
    for entry in result:
        for rect in entry['regions']:
            top,bottom=rect['y']*height,(rect['y']+rect['height'])*height
            left,right=rect['x']*width,(rect['x']+rect['width'])*width
            if any(box[0]<right and box[2]>left and (box[1]<top<box[3] or box[1]<bottom<box[3]) for box in figures):
                entry['warnings'].append('题框可能切到配图或公式，请调整范围后再入库')
                break
    return result


def content_box(lines, left, right):
    return [left+8,max(0,min(line['box'][1] for line in lines)-8),right-8,max(line['box'][3] for line in lines)+8]


def region(box,width,height):
    x1,y1,x2,y2=map(float,box)
    x1=max(0,min(width-1,x1));y1=max(0,min(height-1,y1))
    x2=max(x1+1,min(width,x2));y2=max(y1+1,min(height,y2))
    return {'x':round(x1/width,6),'y':round(y1/height,6),'width':round((x2-x1)/width,6),'height':round((y2-y1)/height,6)}


def candidate(lines,box,width,height,number,group,warnings):
    warnings=list(warnings)
    if lines and min(line.get('score',1) for line in lines)<.8:
        warnings.append('部分文字识别把握较低，请对照原图校对')
    return {'number':number,'group':group[:160],'text':'\n'.join(line['text'] for line in lines)[:10000],
            'regions':[region(box,width,height)],'warnings':warnings}
