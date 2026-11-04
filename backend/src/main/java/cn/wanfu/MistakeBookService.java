package cn.wanfu;

import java.io.*;
import java.util.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MistakeBookService {
    final Db db;final Security sec;final QueryService queries;final FileService files;
    public MistakeBookService(Db db,Security sec,QueryService queries,FileService files){this.db=db;this.sec=sec;this.queries=queries;this.files=files;}
    @Transactional public Map<String,Object> create(Map<String,Object> body)throws IOException{
        long sid=Db.number(body,"studentId");sec.student(sid);String subject=Db.text(body,"subject");if(!subject.isBlank())Db.choice(subject,"语文","数学","英语");
        var selected=Db.optionalIds(body,"mistakeIds");var rows=queries.mistakes(sid,subject.isBlank()?null:subject,null);
        if(!selected.isEmpty()){Set<Long> visible=new HashSet<>();for(var row:rows)visible.add(Db.id(row,"id"));if(!visible.containsAll(selected))throw ApiException.forbidden();rows=rows.stream().filter(r->selected.contains(Db.id(r,"id"))).toList();}
        if(rows.isEmpty()||rows.size()>100)throw ApiException.bad("请选择1至100道错题生成打印本");
        byte[] pdf;
        try(var document=new PDDocument();var fontStream=getClass().getResourceAsStream("/fonts/NotoSansSC-Regular.ttf")){
            if(fontStream==null)throw new IOException("打印字体资源缺失");var font=PDType0Font.load(document,fontStream,true);var layout=new Layout(document,font,db.one("SELECT name FROM student WHERE id=?",sid).get("name").toString(),sec.today().toString());int number=0;
            for(var row:rows){layout.newQuestion(++number,row.get("subject").toString());String question=Objects.toString(row.get("questionText"),"");var scan=db.optional("SELECT j.page_width FROM question_scan_item q JOIN question_scan j ON j.id=q.scan_id WHERE q.imported_mistake_id=?",row.get("id"));if(scan==null)layout.text(question.isBlank()?row.get("title").toString():question,12,20);
                for(var image:files.linked("MISTAKE",Db.id(row,"id")))if(image.get("contentType").toString().startsWith("image/"))layout.image(readImage(Db.id(image,"id")),scan==null?0:((Number)scan.get("pageWidth")).intValue());
                layout.writingSpace();
            }
            layout.close();var output=new ByteArrayOutputStream();document.save(output);pdf=output.toByteArray();
        }
        var result=files.generatedPdf("错题本-"+sec.today()+".pdf",pdf,sid);sec.audit("MISTAKE_BOOK","student="+sid+", questions="+rows.size());return result;
    }
    BufferedImage readImage(long id)throws IOException{
        byte[] bytes=files.bytes(id);try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))){var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw ApiException.bad("题目照片暂不支持打印，请转换为JPEG或PNG后重试");var reader=readers.next();try{reader.setInput(input);if((long)reader.getWidth(0)*reader.getHeight(0)>20000000)throw ApiException.bad("题目照片分辨率过大，请压缩后打印");return reader.read(0);}finally{reader.dispose();}}
    }
    static class Layout{
        final PDDocument doc;final PDType0Font font;final String student,date;PDPage page;PDPageContentStream out;float y;int question;String subject;
        Layout(PDDocument doc,PDType0Font font,String student,String date){this.doc=doc;this.font=font;this.student=student;this.date=date;}
        void newQuestion(int n,String subject)throws IOException{this.question=n;this.subject=subject;newPage();}
        void newPage()throws IOException{
            if(out!=null)out.close();page=new PDPage(PDRectangle.A4);doc.addPage(page);out=new PDPageContentStream(doc,page);y=790;
            line("天健悦成长 · 错题练习",18);y-=12;line(student+"  |  "+subject+"  |  "+date,10);y-=20;line("第 "+question+" 题",13);y-=18;
            out.beginText();out.setFont(font,9);out.newLineAtOffset(48,30);out.showText("第 "+doc.getNumberOfPages()+" 页");out.endText();
        }
        String supported(String text)throws IOException{StringBuilder result=new StringBuilder();for(int cp:text.codePoints().toArray())if(!Character.isISOControl(cp)){String glyph=new String(Character.toChars(cp));try{font.encode(glyph);result.append(glyph);}catch(IllegalArgumentException error){result.append('?');}}return result.toString();}
        void line(String text,float size)throws IOException{out.beginText();out.setFont(font,size);out.newLineAtOffset(48,y);out.showText(supported(text));out.endText();}
        void text(String text,float size,float leading)throws IOException{
            for(String paragraph:text.split("\\R",-1)){StringBuilder buffer=new StringBuilder();float width=0;for(int cp:supported(paragraph).codePoints().toArray()){String glyph=new String(Character.toChars(cp));float next=font.getStringWidth(glyph)/1000*size;if(width+next>499&&!buffer.isEmpty()){if(y<70)newPage();line(buffer.toString(),size);y-=leading;buffer.setLength(0);width=0;}buffer.append(glyph);width+=next;}if(y<70)newPage();line(buffer.toString(),size);y-=leading;}y-=12;
        }
        void image(BufferedImage image,int sourcePageWidth)throws IOException{float scale=Math.min(499f/Math.max(image.getWidth(),sourcePageWidth),400f/image.getHeight()),w=image.getWidth()*scale,h=image.getHeight()*scale;if(y-h<65)newPage();out.drawImage(LosslessFactory.createFromImage(doc,image),48,y-h,w,h);y-=h+20;}
        void writingSpace()throws IOException{if(y<145)newPage();line("订正与思考",10);y-=22;out.setStrokingColor(0.82f);for(int i=0;i<3;i++){out.moveTo(48,y);out.lineTo(547,y);out.stroke();y-=28;}}
        void close()throws IOException{if(out!=null)out.close();}
    }
}
