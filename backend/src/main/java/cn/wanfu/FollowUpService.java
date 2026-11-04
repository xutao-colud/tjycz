package cn.wanfu;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FollowUpService {
    final Db db; final Security sec; final FileService files; final GrowthService growth;
    public FollowUpService(Db db,Security sec,FileService files,GrowthService growth) { this.db=db; this.sec=sec; this.files=files; this.growth=growth; }
    static String query() {
        return "SELECT f.*,EXISTS(SELECT 1 FROM follow_up_log l WHERE l.follow_up_id=f.id AND l.action='PARENT_DONE') parent_completed,(SELECT l.actor_role FROM follow_up_log l WHERE l.follow_up_id=f.id ORDER BY l.id DESC LIMIT 1) last_actor_role,s.name student_name,cl.id class_id,cl.name class_name,cl.campus_id,cp.name campus_name,u.name owner_name FROM follow_up f JOIN student s ON s.id=f.student_id JOIN school_class cl ON cl.id=s.class_id JOIN campus cp ON cp.id=cl.campus_id JOIN app_user u ON u.id=f.owner_id";
    }
    boolean canOwn(long user,long sid) {
        return db.count("SELECT COUNT(*) FROM app_user u JOIN student s ON s.id=? JOIN school_class cl ON cl.id=s.class_id WHERE u.id=? AND (u.role='SUPER_ADMIN' OR (u.role='ADMIN' AND EXISTS(SELECT 1 FROM user_campus uc WHERE uc.user_id=u.id AND uc.campus_id=cl.campus_id)) OR (u.role='TEACHER' AND EXISTS(SELECT 1 FROM teacher_class tc WHERE tc.user_id=u.id AND tc.class_id=s.class_id)))",sid,user)>0;
    }
    List<Map<String,Object>> owners(long sid) {
        sec.student(sid);
        return db.list("SELECT u.id,u.name,u.role FROM app_user u WHERE u.role='TEACHER' AND EXISTS(SELECT 1 FROM teacher_class tc JOIN student s ON s.class_id=tc.class_id WHERE tc.user_id=u.id AND s.id=?) ORDER BY u.id",sid);
    }
    void source(Map<String,Object> b,long sid) {
        String type=Db.text(b,"sourceType"); if(type.isBlank()) { if(b.get("sourceId")!=null) throw ApiException.bad("关联记录须提供类型"); return; }
        String table=switch(type) { case "HOMEWORK" -> "homework"; case "ATTENDANCE" -> "attendance"; case "FITNESS" -> "fitness"; case "NOTE" -> "growth_event"; default -> throw ApiException.bad("不支持的关联记录类型"); };
        if(db.count("SELECT COUNT(*) FROM "+table+" WHERE id=? AND student_id=?",Db.number(b,"sourceId"),sid)==0) throw ApiException.forbidden();
    }
    long log(long id,String action,String from,String to,String text,List<Long> attachments,long sid) {
        long log=db.insert("INSERT INTO follow_up_log(follow_up_id,actor_id,actor_name,actor_role,action,from_status,to_status,text,created_at) VALUES(?,?,?,?,?,?,?,?,?)",id,sec.actor().id(),sec.actor().name(),sec.actor().role(),action,from,to,text,sec.now());
        files.link(attachments,"FOLLOWUP_LOG",log,sid); return log;
    }
    Map<String,Object> visible(long id) {
        var row=db.one(query()+" WHERE f.id=? AND "+sec.scope("s"),id); sec.student(Db.id(row,"studentId")); return row;
    }
    Map<String,Object> detail(long id) {
        var row=visible(id); var logs=db.list("SELECT l.*,EXISTS(SELECT 1 FROM follow_up_view v JOIN app_user u ON u.id=v.user_id WHERE v.follow_up_id=l.follow_up_id AND v.last_log_id>=l.id AND u.role IN ('TEACHER','ADMIN','SUPER_ADMIN')) read_by_staff,EXISTS(SELECT 1 FROM follow_up_view v JOIN app_user u ON u.id=v.user_id WHERE v.follow_up_id=l.follow_up_id AND v.last_log_id>=l.id AND u.role='PARENT') read_by_parent FROM follow_up_log l WHERE l.follow_up_id=? ORDER BY l.id",id);
        for(var l:logs) l.put("attachments",files.linked("FOLLOWUP_LOG",Db.id(l,"id")));
        row.put("logs",logs); row.put("canManage",sec.isAdmin()||sec.actor().id()==Db.id(row,"ownerId"));
        row.put("lastLogId",logs.isEmpty()?0:logs.get(logs.size()-1).get("id"));
        return row;
    }
    @Transactional public Map<String,Object> create(Map<String,Object> b) {
        long sid=Db.number(b,"studentId"); sec.student(sid); db.one("SELECT id FROM student WHERE id=? FOR UPDATE",sid);
        LocalDate date=sec.date(Db.text(b,"date")); growth.pastOrToday(date);
        String kind=Db.required(b,"kind",20); Db.choice(kind,"ISSUE","MESSAGE");
        if(kind.equals("ISSUE")) sec.staff();
        String key=growth.requestKey("followup-create:"+sid,b,true); var old=growth.replay(key,b); if(old!=null) return detail(Db.id(old,"sourceId"));
        boolean reminder=Boolean.TRUE.equals(b.get("tomorrowReminder"));
        if(reminder) {
            sec.staff();
            if(!kind.equals("MESSAGE")||!date.equals(sec.today())) throw ApiException.bad("明日提醒只能在当天的家校消息中发送");
        }
        String type=kind.equals("MESSAGE")?"COMMUNICATION":Db.required(b,"type",30);
        Db.choice(type,"LEARNING","HEALTH","ATTENDANCE","COMMUNICATION","OTHER");
        if(reminder) type="REMINDER";
        String reason=Db.required(b,"reason",2000),title=reminder?"明日提醒":kind.equals("MESSAGE")?"家校留言":Db.required(b,"title",200);
        String measures=kind.equals("ISSUE")?Db.required(b,"measures",2000):"";
        boolean needs=false;
        if(kind.equals("ISSUE")) {
            if(!(b.get("needsParent") instanceof Boolean)) throw ApiException.bad("请选择是否需要家长配合");
            needs=Boolean.TRUE.equals(b.get("needsParent"));
        }
        String parentAction=needs?Db.required(b,"parentAction",2000):"";
        long owner;
        if(sec.actor().role().equals("PARENT")) {
            var recipients=owners(sid); if(recipients.isEmpty()) throw ApiException.bad("该班尚未分配老师，请联系校区");
            owner=Db.id(recipients.get(0),"id");
        } else owner=b.get("ownerId")==null?sec.actor().id():Db.number(b,"ownerId");
        if(owner!=sec.actor().id()&&!sec.isAdmin()&&!sec.actor().role().equals("PARENT")) throw ApiException.forbidden();
        if(!canOwn(owner,sid)) throw ApiException.bad("负责人须有该学生的管理权限");
        source(b,sid); String source=Db.text(b,"sourceType");
        LocalDateTime next=null;
        if(!Db.text(b,"nextFeedbackAt").isBlank()) {
            try { next=LocalDateTime.parse(Db.text(b,"nextFeedbackAt").replace(' ','T')); }
            catch(java.time.format.DateTimeParseException e) { throw ApiException.bad("预计反馈时间格式须为 YYYY-MM-DDTHH:mm"); }
        }
        if(next!=null&&next.isBefore(sec.now())) throw ApiException.bad("预计反馈时间不能早于现在");
        var attachments=Db.optionalIds(b,"attachmentIds"); if(attachments.size()>6) throw ApiException.bad("最多6个附件");
        long id=db.insert("INSERT INTO follow_up(student_id,business_date,kind,type,title,reason,measures,owner_id,needs_parent,parent_action,next_feedback_at,source_type,source_id,status,created_by,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,'OPEN',?,?,?)",sid,date,kind,type,title,reason,measures,owner,needs,parentAction,next,source.isBlank()?null:source,source.isBlank()?null:Db.number(b,"sourceId"),sec.actor().id(),sec.now(),sec.now());
        log(id,"CREATE",null,"OPEN",reason+(measures.isBlank()?"":"\n已采取措施："+measures),attachments,sid);
        growth.event(sid,date,"FOLLOWUP","FOLLOWUP",id,"CREATE",title,reason,"OPEN",sec.now(),key,key,b,attachments);
        sec.audit("FOLLOWUP_CREATE","followup="+id); return detail(id);
    }
    @Transactional public Map<String,Object> update(long id,Map<String,Object> b) {
        var row=visible(id); long sid=Db.id(row,"studentId"); db.one("SELECT id FROM student WHERE id=? FOR UPDATE",sid);
        row=db.one(query()+" WHERE f.id=? FOR UPDATE",id);
        String key=growth.requestKey("followup-update:"+id,b,true); if(growth.replay(key,b)!=null) return detail(id);
        if(!b.containsKey("expectedVersion")) throw ApiException.bad("缺少记录版本，请刷新"); growth.version(row,b);
        String old=row.get("status").toString(),target=Db.required(b,"status",20),text=Db.required(b,"text",2000);
        sec.staff(); if(!sec.isAdmin()&&sec.actor().id()!=Db.id(row,"ownerId")) throw ApiException.forbidden();
        boolean allowed=(old.equals("OPEN")&&List.of("IN_PROGRESS","CANCELLED").contains(target))||(old.equals("IN_PROGRESS")&&List.of("RESOLVED","CANCELLED").contains(target))||(old.equals("RESOLVED")&&target.equals("IN_PROGRESS"));
        if(!allowed) throw ApiException.bad("当前事项状态不允许此操作");
        if(target.equals("CANCELLED")&&!sec.isAdmin()&&sec.actor().id()!=Db.id(row,"createdBy")) throw ApiException.forbidden();
        db.update("UPDATE follow_up SET status=?,version=version+1,updated_at=? WHERE id=?",target,sec.now(),id);
        log(id,"STATUS",old,target,text,List.of(),sid);
        growth.event(sid,(LocalDate)row.get("businessDate"),"FOLLOWUP","FOLLOWUP",id,"STATUS",row.get("title").toString(),text,target,sec.now(),key,key,b,List.of());
        sec.audit("FOLLOWUP_UPDATE","followup="+id+", status="+target); return detail(id);
    }
    @Transactional public Map<String,Object> reply(long id,Map<String,Object> b) {
        var row=visible(id); long sid=Db.id(row,"studentId"); db.one("SELECT id FROM student WHERE id=? FOR UPDATE",sid);
        row=db.one(query()+" WHERE f.id=? FOR UPDATE",id);
        String key=growth.requestKey("followup-reply:"+id,b,true); if(growth.replay(key,b)!=null) return detail(id);
        String action=Db.required(b,"action",30); Db.choice(action,"ACK","REPLY","PARENT_DONE");
        boolean parent=sec.actor().role().equals("PARENT");
        if(!parent&&!action.equals("REPLY")) throw ApiException.forbidden();
        if(row.get("status").equals("CANCELLED")) throw ApiException.bad("事项已撤销，不可继续回复");
        String text=action.equals("ACK")?"家长已了解":Db.required(b,"text",2000);
        var attachments=Db.optionalIds(b,"attachmentIds"); if(attachments.size()>6) throw ApiException.bad("最多6个附件");
        log(id,action,row.get("status").toString(),row.get("status").toString(),text,attachments,sid);
        db.update("UPDATE follow_up SET version=version+1,updated_at=? WHERE id=?",sec.now(),id);
        growth.event(sid,(LocalDate)row.get("businessDate"),"FOLLOWUP","FOLLOWUP",id,action,parent?"家长补充反馈":"老师回复",text,row.get("status").toString(),sec.now(),key,key,b,attachments);
        sec.audit("FOLLOWUP_REPLY","followup="+id); return detail(id);
    }
    @Transactional public Map<String,Object> reassign(long id,Map<String,Object> b) {
        sec.admin(); var row=visible(id); db.one("SELECT id FROM student WHERE id=? FOR UPDATE",Db.id(row,"studentId"));
        row=db.one(query()+" WHERE f.id=? FOR UPDATE",id);
        String key=growth.requestKey("followup-owner:"+id,b,true); if(growth.replay(key,b)!=null) return detail(id);
        if(!b.containsKey("expectedVersion")) throw ApiException.bad("缺少记录版本，请刷新");
        growth.version(row,b); long owner=Db.number(b,"ownerId"); String text=Db.required(b,"text",2000);
        if(!canOwn(owner,Db.id(row,"studentId"))) throw ApiException.bad("新负责人无权访问该学生");
        db.update("UPDATE follow_up SET owner_id=?,version=version+1,updated_at=? WHERE id=?",owner,sec.now(),id);
        log(id,"REASSIGN",row.get("status").toString(),row.get("status").toString(),text,List.of(),Db.id(row,"studentId"));
        growth.event(Db.id(row,"studentId"),(LocalDate)row.get("businessDate"),"FOLLOWUP","FOLLOWUP",id,"REASSIGN","调整负责老师",text,row.get("status").toString(),sec.now(),key,key,b,List.of());
        sec.audit("FOLLOWUP_OWNER","followup="+id); return detail(id);
    }
    @Transactional public Map<String,Object> read(long id,Map<String,Object> b) {
        var row=visible(id); long user=sec.actor().id(),last=Db.number(b,"lastLogId");
        long max=db.count("SELECT COALESCE(MAX(id),0) FROM follow_up_log WHERE follow_up_id=?",id);
        if(last<0||last>max) throw ApiException.bad("已读位置无效");
        db.one("SELECT id FROM app_user WHERE id=? FOR UPDATE",user);
        var previous=db.optional("SELECT last_log_id FROM follow_up_view WHERE follow_up_id=? AND user_id=?",id,user);
        if(previous==null) db.update("INSERT INTO follow_up_view VALUES(?,?,?,?)",id,user,last,sec.now());
        else if(last>Db.id(previous,"lastLogId")) db.update("UPDATE follow_up_view SET last_log_id=?,read_at=? WHERE follow_up_id=? AND user_id=?",last,sec.now(),id,user);
        return Map.of("success",true);
    }
}
