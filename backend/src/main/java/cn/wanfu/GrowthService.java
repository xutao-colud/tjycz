package cn.wanfu;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GrowthService {
    final Db db; final Security sec; final FileService files; final ObjectMapper json; final PrivacyService privacy;
    public GrowthService(Db db,Security sec,FileService files,ObjectMapper json,PrivacyService privacy) { this.db=db; this.sec=sec; this.files=files; this.json=json; this.privacy=privacy; }
    void pastOrToday(LocalDate date) { if(date.isAfter(sec.today())) throw ApiException.bad("未来日期不可登记已经发生的成长记录"); }
    LocalDateTime occurred(Map<String,Object> b,LocalDate date) {
        pastOrToday(date); String value=Db.text(b,"occurredAt");
        if(value.isBlank()) return date.equals(sec.today())?sec.now():null;
        LocalDateTime at;
        try { at=LocalDateTime.parse(value.replace(' ','T')); }
        catch(java.time.format.DateTimeParseException e) { throw ApiException.bad("发生时间格式须为 YYYY-MM-DDTHH:mm"); }
        if(!at.toLocalDate().equals(date)||at.isAfter(sec.now())) throw ApiException.bad("发生时间须属于所选日期且不能晚于现在");
        return at;
    }
    String hash(Map<String,Object> body) {
        try { return sec.hash(json.writeValueAsString(new TreeMap<>(body))); }
        catch(com.fasterxml.jackson.core.JsonProcessingException e) { throw ApiException.bad("请求内容格式错误"); }
    }
    String requestKey(String scope,Map<String,Object> body,boolean required) {
        String id=Db.text(body,"requestId"); if(id.isBlank()&&!required) return null;
        if(id.isBlank()||id.length()>100) throw ApiException.bad("requestId须为1至100个字符");
        return sec.actor().id()+":"+scope+":"+id;
    }
    Map<String,Object> replay(String key,Map<String,Object> body) {
        if(key==null) return null;
        var row=db.optional("SELECT id,source_id,request_hash FROM growth_event WHERE request_key=?",key);
        if(row!=null&&!row.get("requestHash").equals(hash(body))) throw new ApiException(409,"requestId已用于不同内容，请刷新后重新操作");
        return row;
    }
    void version(Map<String,Object> row,Map<String,Object> body) {
        if(body.containsKey("expectedVersion")&&Db.integer(body,"expectedVersion",-1)!=Db.integer(row,"version",0)) throw new ApiException(409,"记录已被其他人更新，请刷新后核对");
    }
    long event(long sid,LocalDate date,String type,String source,long sourceId,String action,String title,String detail,String status,LocalDateTime at,String key,String requestKey,Map<String,Object> request,List<Long> attachments) {
        long id=db.insert("INSERT INTO growth_event(student_id,business_date,event_type,source_type,source_id,action,title,detail,status,occurred_at,recorded_at,actor_id,actor_name,event_key,request_key,request_hash) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            sid,date,type,source,sourceId,action,title,detail,status,at,sec.now(),sec.actor().id(),sec.actor().name(),key,requestKey,requestKey==null?null:hash(request));
        if(source.equals("NOTE")&&sourceId==0) db.update("UPDATE growth_event SET source_id=? WHERE id=?",id,id);
        files.link(attachments,"GROWTH",id,sid); return id;
    }
    @Transactional public Map<String,Object> note(Map<String,Object> b) {
        sec.staff(); var ids=Db.ids(b,"studentIds"); for(long sid:ids) sec.student(sid);
        LocalDate date=sec.date(Db.text(b,"date")); LocalDateTime at=occurred(b,date);
        String type=Db.required(b,"type",30); Db.choice(type,"PREPARATION","BEHAVIOR","COMMENT");
        String title=switch(type) { case "PREPARATION" -> "课前准备"; case "BEHAVIOR" -> "学习表现"; default -> "老师点评"; };
        String text=Db.required(b,"text",2000); var attachments=Db.optionalIds(b,"attachmentIds");
        if(attachments.size()>6) throw ApiException.bad("每条记录最多6个附件");
        List<Object> results=new ArrayList<>();
        for(long sid:ids) {
            db.one("SELECT id FROM student WHERE id=? FOR UPDATE",sid); sec.student(sid);
            String key=requestKey("note:"+sid,b,true); var old=replay(key,b);
            long id=old==null?event(sid,date,"COMMENT","NOTE",0,type,title,text,"RECORDED",at,key,key,b,attachments):Db.id(old,"id");
            results.add(Map.of("studentId",sid,"success",true,"id",id));
        }
        sec.audit("GROWTH_NOTE",date+" count="+ids.size()); return Map.of("results",results);
    }
    @Transactional public Map<String,Object> pause(Map<String,Object> b) {
        sec.staff(); var ids=Db.ids(b,"studentIds"); for(long sid:ids) sec.student(sid);
        LocalDate date=sec.date(Db.text(b,"date")); LocalDateTime at=occurred(b,date);
        String reason=Db.required(b,"reason",2000),measures=Db.required(b,"measures",2000);
        List<Object> results=new ArrayList<>();
        for(long sid:ids) {
            db.one("SELECT id FROM student WHERE id=? FOR UPDATE",sid); sec.student(sid);
            String key=requestKey("pause:"+sid,b,true); var old=replay(key,b);
            var pause=db.optional("SELECT * FROM fitness_pause WHERE student_id=? AND date=?",sid,date);
            long id;
            if(old!=null) id=Db.id(old,"sourceId");
            else {
                if(pause==null) id=db.insert("INSERT INTO fitness_pause(student_id,date,reason,measures,actor_id,occurred_at,updated_at) VALUES(?,?,?,?,?,?,?)",sid,date,reason,measures,sec.actor().id(),at,sec.now());
                else { id=Db.id(pause,"id"); db.update("UPDATE fitness_pause SET reason=?,measures=?,actor_id=?,occurred_at=?,updated_at=?,version=version+1 WHERE id=?",reason,measures,sec.actor().id(),at,sec.now(),id); }
                event(sid,date,"FITNESS","FITNESS_PAUSE",id,"PAUSED","体能因故暂停",reason+"\n已采取措施："+measures,"PAUSED",at,key,key,b,List.of());
            }
            results.add(Map.of("studentId",sid,"success",true,"id",id));
        }
        sec.audit("FITNESS_PAUSE",date+" count="+ids.size()); return Map.of("results",results);
    }
    List<Map<String,Object>> comparisonWorks(long sid) {
        sec.staff(); sec.student(sid);
        if(!privacy.canUseMedia(sid)) return List.of();
        return db.list("""
            SELECT work.file_id id,work.title,work.work_date FROM (
              SELECT l.file_id,t.title,t.date work_date FROM file_link l JOIN homework h ON h.id=l.target_id AND h.student_id=l.student_id
              JOIN homework_task t ON t.id=h.task_id WHERE l.kind='HOMEWORK' AND l.student_id=?
              UNION ALL
              SELECT l.file_id,m.title,CAST(m.created_at AS DATE) work_date FROM file_link l JOIN mistake m ON m.id=l.target_id AND m.student_id=l.student_id
              WHERE l.kind='MISTAKE' AND l.student_id=?
            ) work JOIN stored_file f ON f.id=work.file_id
            WHERE f.privacy_blocked=FALSE AND f.content_type LIKE 'image/%' AND f.campus_id=?
              AND NOT EXISTS (SELECT 1 FROM file_link l WHERE l.file_id=f.id AND l.student_id<>?)
            ORDER BY work.work_date DESC,work.file_id DESC LIMIT 30
            """,sid,sid,sec.studentCampus(sid),sid);
    }
    @Transactional public Map<String,Object> compareWorks(Map<String,Object> b) {
        sec.staff(); long sid=Db.number(b,"studentId"); sec.student(sid);
        db.one("SELECT id FROM student WHERE id=? FOR UPDATE",sid);
        sec.student(sid); privacy.requireMedia(sid);
        String key=requestKey("comparison:"+sid,b,true); var old=replay(key,b);
        if(old!=null) return Map.of("id",Db.id(old,"id"));
        long before=Db.number(b,"beforeId"),after=Db.number(b,"afterId");
        if(before==after) throw ApiException.bad("请选择两张不同的作品图");
        var works=comparisonWorks(sid);
        var first=works.stream().filter(row->Db.id(row,"id")==before).findFirst().orElseThrow(ApiException::forbidden);
        var second=works.stream().filter(row->Db.id(row,"id")==after).findFirst().orElseThrow(ApiException::forbidden);
        if(((LocalDate)first.get("workDate")).isAfter((LocalDate)second.get("workDate"))) throw ApiException.bad("请按作品日期从早到晚选择");
        String note=Db.required(b,"text",500);
        long id=event(sid,sec.today(),"COMMENT","NOTE",0,"COMPARISON","成长对比",note,"RECORDED",sec.now(),key,key,b,List.of());
        files.link(List.of(before),"GROWTH_BEFORE",id,sid);
        files.link(List.of(after),"GROWTH_AFTER",id,sid);
        sec.audit("GROWTH_COMPARISON","event="+id);
        return Map.of("id",id);
    }
    List<Map<String,Object>> feed(long sid,LocalDate date,boolean attachments) {
        sec.student(sid); List<Map<String,Object>> out=new ArrayList<>();
        var rows=db.list("SELECT id,student_id,business_date,event_type,source_type,source_id,action,title,detail,status,occurred_at,recorded_at,actor_id,actor_name FROM growth_event WHERE student_id=? AND ((occurred_at>=? AND occurred_at<?) OR (occurred_at IS NULL AND business_date=?)) ORDER BY id",sid,date.atStartOfDay(),date.plusDays(1).atStartOfDay(),date);
        for(var row:rows) {
            row.put("key","event-"+row.get("id"));
            if(attachments) {
                long id=Db.id(row,"id");
                if("COMPARISON".equals(row.get("action"))) {
                    row.put("before",files.linked("GROWTH_BEFORE",id));
                    row.put("after",files.linked("GROWTH_AFTER",id));
                } else row.put("attachments",files.linked("GROWTH",id));
            }
            out.add(row);
        }
        for(var a:db.list("SELECT * FROM attendance WHERE student_id=? AND date=?",sid,date)) {
            for(String action:List.of("CHECK_IN","CHECK_OUT")) {
                if(action.equals("CHECK_OUT")&&!a.get("status").equals("LEFT")) continue;
                if(db.count("SELECT COUNT(*) FROM growth_event WHERE source_type='ATTENDANCE' AND source_id=? AND action=?",a.get("id"),action)>0) continue;
                var item=legacy("attendance-"+a.get("id")+action,"ATTENDANCE","ATTENDANCE",Db.id(a,"id"),action.equals("CHECK_IN")?"到校签到":"离校签退","历史考勤登记",action.equals("CHECK_IN")?"PRESENT":"LEFT",a.get(action.equals("CHECK_IN")?"checkInTime":"checkOutTime"));
                out.add(item);
            }
        }
        for(var h:db.list("SELECT h.id,h.status,t.title,t.subject,h.updated_at FROM homework h JOIN homework_task t ON t.id=h.task_id WHERE h.student_id=? AND t.date=? AND NOT EXISTS(SELECT 1 FROM growth_event e WHERE e.source_type='HOMEWORK' AND e.source_id=h.id)",sid,date)) {
            var item=legacy("homework-"+h.get("id"),"HOMEWORK","HOMEWORK",Db.id(h,"id"),h.get("subject")+" · "+h.get("title"),"历史过程未记录；详情展示最新处理结果",h.get("status").toString(),null);
            if(attachments) item.put("attachments",files.linked("HOMEWORK",Db.id(h,"id"))); out.add(item);
        }
        for(var f:db.list("SELECT * FROM fitness f WHERE student_id=? AND date=? AND NOT EXISTS(SELECT 1 FROM growth_event e WHERE e.source_type='FITNESS' AND e.source_id=f.id)",sid,date))
            out.add(legacy("fitness-"+f.get("id"),"FITNESS","FITNESS",Db.id(f,"id"),f.get("project").toString(),f.get("count")+" 次 · "+f.get("duration")+" 秒","RECORDED",f.get("occurredAt")));
        for(var d:db.list("SELECT * FROM daily_summary WHERE student_id=? AND date=? AND status='PUBLISHED' AND NOT EXISTS(SELECT 1 FROM growth_event e WHERE e.source_type='SUMMARY' AND e.source_id=daily_summary.id)",sid,date))
            out.add(legacy("summary-"+d.get("id"),"COMMENT","SUMMARY",Db.id(d,"id"),"已发布老师点评",Objects.toString(d.get("publishedComment"),""),"PUBLISHED",d.get("publishedAt")));
        out.sort(Comparator.comparing((Map<String,Object> row)->(LocalDateTime)row.get("occurredAt"),Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(row->row.get("key").toString()));
        return out;
    }
    Map<String,Object> legacy(String key,String type,String source,long id,String title,String detail,String status,Object time) {
        Map<String,Object> row=new LinkedHashMap<>(); row.put("key",key); row.put("eventType",type); row.put("sourceType",source); row.put("sourceId",id); row.put("title",title); row.put("detail",detail); row.put("status",status); row.put("occurredAt",time); row.put("recordedAt",null); row.put("actorName",""); row.put("legacy",true); row.put("attachments",List.of()); return row;
    }
    List<Map<String,Object>> concerns(LocalDate date,Long sid,String kind) {
        List<Object> args=new ArrayList<>(List.of(date,kind)); String sql=FollowUpService.query()+" WHERE f.business_date=? AND f.kind=? AND "+(sec.scope("s")+" AND s.privacy_erased_at IS NULL");
        if(sid!=null) { sec.student(sid); sql+=" AND f.student_id=?"; args.add(sid); }
        return db.list(sql+" ORDER BY f.id DESC",args.toArray());
    }
}
