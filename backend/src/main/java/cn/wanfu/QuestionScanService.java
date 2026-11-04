package cn.wanfu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.awt.image.BufferedImage;
import java.io.*;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.PlatformTransactionManager;

@Service
public class QuestionScanService {
    final Db db;final Security sec;final FileService files;final PrivacyService privacy;final LearningService learn;final QuestionOcrClient ocr;final ObjectMapper json;final TransactionTemplate tx;
    final AtomicBoolean working=new AtomicBoolean();
    final ExecutorService worker=Executors.newSingleThreadExecutor(r->{var thread=new Thread(r,"question-scan");thread.setDaemon(true);return thread;});
    public QuestionScanService(Db db,Security sec,FileService files,PrivacyService privacy,LearningService learn,QuestionOcrClient ocr,ObjectMapper json,PlatformTransactionManager manager){this.db=db;this.sec=sec;this.files=files;this.privacy=privacy;this.learn=learn;this.ocr=ocr;this.json=json;this.tx=new TransactionTemplate(manager);}
    @jakarta.annotation.PreDestroy void close(){worker.shutdownNow();}
    void wakeAfterCommit(){TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
        public void afterCommit(){if(!worker.isShutdown())worker.execute(QuestionScanService.this::poll);}
    });}
    String query(){return "SELECT j.*,s.name student_name FROM question_scan j JOIN student s ON s.id=j.student_id";}
    Map<String,Object> visible(long id){sec.staff();var job=db.one(query()+" WHERE j.id=?",id);long sid=Db.id(job,"studentId");sec.student(sid);privacy.requireMedia(sid);return job;}
    Map<String,Object> status(){sec.staff();return ocr.status();}
    List<Map<String,Object>> list(Long sid){
        sec.staff();if(sid!=null)sec.student(sid);
        var args=new ArrayList<Object>();String filter=" WHERE "+sec.scope("s");if(sid!=null){filter+=" AND j.student_id=?";args.add(sid);}
        return db.list(query()+filter+" ORDER BY j.id DESC LIMIT 40",args.toArray()).stream().filter(j->privacy.canUseMedia(Db.id(j,"studentId"))).map(j->decorate(j,false)).toList();
    }
    Map<String,Object> get(long id){return decorate(visible(id),true);}
    Map<String,Object> decorate(Map<String,Object> job,boolean full){
        var out=new LinkedHashMap<String,Object>();
        for(String field:List.of("id","studentId","studentName","campusId","subject","status","errorMessage","engine","pageWidth","pageHeight","version","createdAt","updatedAt"))out.put(field,job.get(field));
        var items=db.list("SELECT * FROM question_scan_item WHERE scan_id=? AND deleted=FALSE ORDER BY sort_order,id",job.get("id"));
        out.put("questionCount",items.size());out.put("importedCount",items.stream().filter(q->q.get("importedMistakeId")!=null).count());
        out.put("sourcePhoto",files.access(Db.id(job,"sourceFileId")));
        if(full){out.put("pagePhoto",files.access(Db.id(job,job.get("pageFileId")==null?"sourceFileId":"pageFileId")));out.put("questions",items.stream().map(q->{var value=new LinkedHashMap<String,Object>();for(String field:List.of("id","displayNumber","groupName","questionText","importedMistakeId"))value.put(field,q.get(field));value.put("regions",decode(q.get("regionsJson").toString()));value.put("warnings",decode(q.get("warningsJson").toString()));return value;}).toList());}
        return out;
    }
    @Transactional public Map<String,Object> create(Map<String,Object> body){
        sec.staff();fields(body,"studentId","photoFileId","subject","requestId");long sid=Db.number(body,"studentId"),photo=Db.number(body,"photoFileId");sec.student(sid);privacy.requireMedia(sid);
        String subject=Db.required(body,"subject",16),request=Db.required(body,"requestId",100);Db.choice(subject,"语文","数学","英语");
        db.one("SELECT id FROM student WHERE id=? FOR UPDATE",sid);sec.student(sid);
        var old=db.optional("SELECT id,student_id,source_file_id,subject FROM question_scan WHERE actor_id=? AND request_id=?",sec.actor().id(),request);
        if(old!=null){if(Db.id(old,"studentId")!=sid||Db.id(old,"sourceFileId")!=photo||!old.get("subject").equals(subject))throw new ApiException(409,"提交编号已用于另一张图片");return get(Db.id(old,"id"));}
        if(!ocr.configured())throw new ApiException(503,"识别服务尚未配置，可使用手工录题");
        if(db.count("SELECT COUNT(*) FROM question_scan WHERE actor_id=? AND status IN ('QUEUED','RECOGNIZING')",sec.actor().id())>=3)throw new ApiException(429,"已有扫描任务处理中，请稍后再提交");
        if(!files.canRead(photo))throw ApiException.forbidden();var file=db.one("SELECT campus_id,content_type FROM stored_file WHERE id=?",photo);
        if(Db.id(file,"campusId")!=sec.studentCampus(sid))throw ApiException.forbidden();if(!file.get("contentType").toString().startsWith("image/"))throw ApiException.bad("请上传题目照片");
        long id=db.insert("INSERT INTO question_scan(student_id,campus_id,actor_id,session_hash,source_file_id,subject,request_id,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)",sid,sec.studentCampus(sid),sec.actor().id(),sec.actor().sessionHash(),photo,subject,request,sec.now(),sec.now());
        files.link(List.of(photo),"SCAN_SOURCE",id,sid);
        sec.audit("QUESTION_SCAN_CREATE","scan="+id+", student="+sid);wakeAfterCommit();return get(id);
    }
    @Scheduled(fixedDelay=1500) void poll(){
        if(worker.isShutdown()||!ocr.configured()||!working.compareAndSet(false,true))return;
        try{
            db.update("UPDATE question_scan SET status='FAILED',error_message='上次识别未完成，可重新扫描',updated_at=? WHERE status='RECOGNIZING' AND updated_at<?",sec.now(),sec.now().minusMinutes(5));
            var row=db.optional("SELECT id FROM question_scan WHERE status='QUEUED' ORDER BY id LIMIT 1");
            if(row==null){working.set(false);return;}long id=Db.id(row,"id");
            if(db.update("UPDATE question_scan SET status='RECOGNIZING',error_message='',updated_at=? WHERE id=? AND status='QUEUED'",sec.now(),id)!=1){working.set(false);return;}
            worker.submit(()->{try{process(id);}finally{working.set(false);poll();}});
        }catch(RuntimeException error){working.set(false);throw error;}
    }
    void process(long id){
        var job=db.one("SELECT * FROM question_scan WHERE id=?",id);String hash=job.get("sessionHash").toString();long campus=Db.id(job,"campusId"),sid=Db.id(job,"studentId");
        try{
            byte[] source=sec.inSession(hash,campus,()->{sec.staff();sec.student(sid);privacy.requireMedia(sid);try{return files.bytes(Db.id(job,"sourceFileId"));}catch(IOException error){throw new UncheckedIOException(error);}});
            var result=ocr.recognize(source);
            sec.inSession(hash,campus,()->tx.execute(state->{
                sec.staff();sec.student(sid);privacy.requireMedia(sid);db.one("SELECT id FROM student WHERE id=? FOR UPDATE",sid);sec.student(sid);privacy.requireMedia(sid);
                var current=db.one("SELECT status FROM question_scan WHERE id=? FOR UPDATE",id);if(!current.get("status").equals("RECOGNIZING"))return null;
                if(!(result.get("questions") instanceof List<?> questions)||questions.isEmpty()||questions.size()>40)throw ApiException.bad("识别服务未返回有效题目");
                byte[] png=Base64.getDecoder().decode(Db.required(result,"pageImage",28*1024*1024));var image=readImage(png);
                long page;
                try{page=Db.id(files.generatedImage("扫描校正页.png",png,sid),"id");}catch(IOException error){throw new UncheckedIOException(error);}
                int index=0;
                for(Object raw:questions){var q=object(raw);String text=Db.text(q,"text");if(text.length()>10000)throw ApiException.bad("识别题目过长");var regions=regions(q.get("regions"));var warnings=new ArrayList<String>();if(q.get("warnings") instanceof List<?> rows)for(Object value:rows){if(warnings.size()<6)warnings.add(value.toString().substring(0,Math.min(160,value.toString().length())));}
                    db.update("INSERT INTO question_scan_item(scan_id,sort_order,display_number,group_name,question_text,recognition_text,regions_json,warnings_json) VALUES(?,?,?,?,?,?,?,?)",id,++index,Db.required(q,"number",80),bounded(q,"group",160),text,text,encode(regions),encode(warnings));
                }
                db.update("UPDATE question_scan SET page_file_id=?,page_width=?,page_height=?,engine=?,status='READY',updated_at=? WHERE id=?",page,image.getWidth(),image.getHeight(),bounded(result,"engine",200),sec.now(),id);
                sec.audit("QUESTION_SCAN_READY","scan="+id+", questions="+index);return null;
            }));
        }catch(Exception error){
            String message=error instanceof ApiException?error.getMessage():"识别结果处理失败，请重试或使用手工框题";
            db.update("UPDATE question_scan SET status='FAILED',error_message=?,updated_at=? WHERE id=? AND status='RECOGNIZING'",message.substring(0,Math.min(1000,message.length())),sec.now(),id);
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Question scan {} failed: {}",id,error.getClass().getSimpleName());
        }
    }
    @Transactional public Map<String,Object> retry(long id,Map<String,Object> body){
        var job=visible(id);long sid=Db.id(job,"studentId");db.one("SELECT id FROM student WHERE id=? FOR UPDATE",sid);job=visible(id);
        if(!job.get("status").equals("FAILED"))throw new ApiException(409,"仅失败的任务可以重试");
        if(!ocr.configured())throw new ApiException(503,"识别服务尚未配置");
        if(db.count("SELECT COUNT(*) FROM question_scan WHERE actor_id=? AND status IN ('QUEUED','RECOGNIZING')",job.get("actorId"))>=3)throw new ApiException(429,"已有扫描任务处理中，请稍后重试");
        db.update("UPDATE question_scan SET status='QUEUED',session_hash=?,error_message='',updated_at=?,version=version+1 WHERE id=?",sec.actor().sessionHash(),sec.now(),id);
        sec.audit("QUESTION_SCAN_RETRY","scan="+id);wakeAfterCommit();return get(id);
    }
    @Transactional public Map<String,Object> edit(long id,Map<String,Object> body){
        fields(body,"expectedVersion","questions");var job=visible(id);long sid=Db.id(job,"studentId");db.one("SELECT id FROM student WHERE id=? FOR UPDATE",sid);job=visible(id);db.one("SELECT id FROM question_scan WHERE id=? FOR UPDATE",id);
        version(job,body);if(!job.get("status").equals("READY"))throw new ApiException(409,"请等待识别完成后再调整题目");
        if(!(body.get("questions") instanceof List<?> rows)||rows.isEmpty()||rows.size()>40)throw ApiException.bad("请保留1至40道题目");
        var old=db.list("SELECT * FROM question_scan_item WHERE scan_id=? AND deleted=FALSE",id);var seen=new HashSet<Long>();int order=0;
        for(Object value:rows){var q=object(value);var rect=regions(q.get("regions"));String text=bounded(q,"questionText",10000),number=Db.required(q,"displayNumber",80),group=bounded(q,"groupName",160);long qid=q.get("id")==null?0:Db.number(q,"id");
            if(qid>0){var current=old.stream().filter(row->Db.id(row,"id")==qid).findFirst().orElseThrow(ApiException::forbidden);if(!seen.add(qid))throw ApiException.bad("题目不能重复");
                if(current.get("importedMistakeId")!=null){if(!text.equals(current.get("questionText"))||!encode(rect).equals(current.get("regionsJson"))||!number.equals(current.get("displayNumber"))||!group.equals(current.get("groupName")))throw new ApiException(409,"已入库题目请在错题本中修改");}
                db.update("UPDATE question_scan_item SET sort_order=?,display_number=?,group_name=?,question_text=?,regions_json=? WHERE id=?",++order,number,group,text,encode(rect),qid);
            }else db.update("INSERT INTO question_scan_item(scan_id,sort_order,display_number,group_name,question_text,recognition_text,regions_json,warnings_json) VALUES(?,?,?,?,?,'',?,?)",id,++order,number,group,text,encode(rect),encode(List.of("老师手工补充的题目，请核对范围")));
        }
        for(var row:old)if(!seen.contains(Db.id(row,"id"))){if(row.get("importedMistakeId")!=null)throw new ApiException(409,"已入库题目不能从扫描记录中移除");db.update("UPDATE question_scan_item SET deleted=TRUE WHERE id=?",row.get("id"));}
        db.update("UPDATE question_scan SET version=version+1,updated_at=? WHERE id=?",sec.now(),id);sec.audit("QUESTION_SCAN_EDIT","scan="+id);return get(id);
    }
    Map<String,Object> importItems(long id,Map<String,Object> body){
        fields(body,"expectedVersion","questionIds");var job=visible(id);version(job,body);if(!job.get("status").equals("READY"))throw new ApiException(409,"请先完成题目识别和校对");
        List<Long> selected=Db.ids(body,"questionIds");if(selected.size()>40)throw ApiException.bad("一次最多导入40题");
        for(long qid:selected)if(db.count("SELECT COUNT(*) FROM question_scan_item WHERE id=? AND scan_id=? AND deleted=FALSE",qid,id)!=1)throw ApiException.forbidden();
        var result=new ArrayList<Map<String,Object>>();
        for(long qid:selected){try{var imported=tx.execute(state->{var fresh=visible(id);long sid=Db.id(fresh,"studentId");db.one("SELECT id FROM student WHERE id=? FOR UPDATE",sid);fresh=visible(id);db.one("SELECT id FROM question_scan WHERE id=? FOR UPDATE",id);version(fresh,body);var q=db.one("SELECT * FROM question_scan_item WHERE id=? AND scan_id=? AND deleted=FALSE FOR UPDATE",qid,id);
                    if(q.get("importedMistakeId")!=null)return Map.<String,Object>of("id",qid,"success",true,"mistakeId",q.get("importedMistakeId"),"message","已在错题本中");
                    byte[] crop;try{crop=crop(readImage(files.bytes(Db.id(fresh,"pageFileId"))),regions(decode(q.get("regionsJson").toString())));}catch(IOException error){throw new UncheckedIOException(error);}
                    String number=q.get("displayNumber").toString(),caption=number.matches("^\\d.*")?"第"+number+"题":number;
                    String title=Db.text(q,"groupName");if(title.isBlank()||List.of("题目","手工框题","第"+number+"大题").contains(title))title=caption;
                    long file;try{file=Db.id(files.generatedImage(caption+".png",crop,sid),"id");}catch(IOException error){throw new UncheckedIOException(error);}
                    var mistake=learn.createMistake(Map.of("studentId",sid,"subject",fresh.get("subject"),"title",title,"questionText",q.get("questionText"),"attachmentIds",List.of(file)));
                    db.update("UPDATE question_scan_item SET imported_mistake_id=? WHERE id=?",mistake.get("id"),qid);db.update("UPDATE question_scan SET updated_at=? WHERE id=?",sec.now(),id);sec.audit("QUESTION_SCAN_IMPORT","scan="+id+", question="+qid+", mistake="+mistake.get("id"));return Map.<String,Object>of("id",qid,"success",true,"mistakeId",mistake.get("id"),"message","已加入错题本");});result.add(imported);
            }catch(ApiException error){result.add(Map.of("id",qid,"success",false,"message",error.getMessage()));}catch(RuntimeException error){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Scan {} question {} import failed: {}",id,qid,error.getClass().getSimpleName());result.add(Map.of("id",qid,"success",false,"message","题目保存未完成，请重试"));}}
        return Map.of("results",result);
    }
    static BufferedImage readImage(byte[] bytes){
        try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))){var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw ApiException.bad("图片格式暂不支持，请改用JPEG或PNG");var reader=readers.next();try{reader.setInput(input);if((long)reader.getWidth(0)*reader.getHeight(0)>20_000_000)throw ApiException.bad("图片分辨率过大");return reader.read(0);}finally{reader.dispose();}}
        catch(IOException error){throw ApiException.bad("图片无法读取，请重新拍照");}
    }
    byte[] crop(BufferedImage page,List<Map<String,Double>> regions)throws IOException{
        var pieces=new ArrayList<BufferedImage>();int width=1,height=0;
        for(var r:regions){int x=(int)Math.floor(r.get("x")*page.getWidth()),y=(int)Math.floor(r.get("y")*page.getHeight());int w=Math.min(page.getWidth()-x,Math.max(1,(int)Math.ceil(r.get("width")*page.getWidth()))),h=Math.min(page.getHeight()-y,Math.max(1,(int)Math.ceil(r.get("height")*page.getHeight())));var piece=trimWhite(page.getSubimage(x,y,w,h));pieces.add(piece);width=Math.max(width,piece.getWidth());height+=piece.getHeight()+12;}
        if((long)width*height>20_000_000)throw ApiException.bad("题目裁图过大，请缩小范围");var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);var graphics=image.createGraphics();graphics.setColor(java.awt.Color.WHITE);graphics.fillRect(0,0,width,height);int y=0;for(var piece:pieces){graphics.drawImage(piece,0,y,null);y+=piece.getHeight()+12;}graphics.dispose();var output=new ByteArrayOutputStream();ImageIO.write(image,"png",output);return output.toByteArray();
    }
    BufferedImage trimWhite(BufferedImage image){
        int left=image.getWidth(),top=image.getHeight(),right=-1,bottom=-1;
        // Only pure-white margins are removed; faint strokes, diagrams and original handwriting remain intact.
        for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++)if((image.getRGB(x,y)&0xffffff)!=0xffffff){left=Math.min(left,x);top=Math.min(top,y);right=Math.max(right,x);bottom=Math.max(bottom,y);}
        if(right<0)return image;left=Math.max(0,left-8);top=Math.max(0,top-8);right=Math.min(image.getWidth()-1,right+8);bottom=Math.min(image.getHeight()-1,bottom+8);
        return image.getSubimage(left,top,right-left+1,bottom-top+1);
    }
    List<Map<String,Double>> regions(Object raw){
        if(!(raw instanceof List<?> values)||values.isEmpty()||values.size()>8)throw ApiException.bad("每题须包含1至8个图片范围");var out=new ArrayList<Map<String,Double>>();
        for(Object value:values){var b=object(value);var rect=new LinkedHashMap<String,Double>();for(String key:List.of("x","y","width","height")){Object n=b.get(key);if(!(n instanceof Number))throw ApiException.bad("题目范围格式无效");double d=((Number)n).doubleValue();if(!Double.isFinite(d)||d<0||d>1)throw ApiException.bad("题目范围超出图片");rect.put(key,d);}
            if(rect.get("width")<.002||rect.get("height")<.002||rect.get("x")+rect.get("width")>1.000001||rect.get("y")+rect.get("height")>1.000001)throw ApiException.bad("题目范围无效或超出图片");out.add(rect);}
        return out;
    }
    void version(Map<String,Object> row,Map<String,Object> body){if(Db.id(row,"version")!=Db.number(body,"expectedVersion"))throw new ApiException(409,"题目已更新，请刷新后重新确认");}
    @SuppressWarnings("unchecked") Map<String,Object> object(Object value){if(!(value instanceof Map<?,?>))throw ApiException.bad("题目数据格式无效");return (Map<String,Object>)value;}
    Object decode(String value){try{return json.readValue(value,new TypeReference<Object>(){});}catch(IOException error){throw new IllegalStateException("扫描记录无法读取",error);}}
    String encode(Object value){try{return json.writeValueAsString(value);}catch(IOException error){throw new IllegalStateException(error);}}
    String bounded(Map<String,Object> body,String key,int max){String value=Db.text(body,key);if(value.length()>max)throw ApiException.bad(key+"超出长度限制");return value;}
    void fields(Map<String,Object> body,String... allowed){var keys=Set.of(allowed);if(!keys.containsAll(body.keySet()))throw ApiException.bad("请求包含不支持的字段");}
}
