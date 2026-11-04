package cn.wanfu;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LearningService {
    final Db db; final Security sec; final FinanceService finance; final FileService files; final GrowthService growth;
    public LearningService(Db db,Security sec,FinanceService finance,FileService files,GrowthService growth) { this.db=db; this.sec=sec; this.finance=finance; this.files=files; this.growth=growth; }
    String homeworkQuery() { return "SELECT h.id,h.task_id,h.student_id,s.name student_name,s.class_id,cl.name class_name,cl.campus_id,t.date,t.subject,t.type,t.title,t.description,h.status,h.accuracy,h.comment,h.version,h.submitted_at,h.reviewed_at,h.completed_at,h.updated_at,h.last_actor_id,(SELECT name FROM app_user WHERE id=h.last_actor_id) actor_name FROM homework h JOIN homework_task t ON t.id=h.task_id JOIN student s ON s.id=h.student_id JOIN school_class cl ON cl.id=s.class_id"; }
    Map<String,Object> homework(long id) {
        var h=db.one(homeworkQuery()+" WHERE h.id=?",id); sec.student(Db.id(h,"studentId")); h.put("attachments",files.linked("HOMEWORK",id));
        var history=db.list("SELECT id,action,detail,status,occurred_at,recorded_at,actor_name FROM growth_event WHERE source_type='HOMEWORK' AND source_id=? ORDER BY id",id);
        for(var entry:history) entry.put("attachments",files.linked("GROWTH",Db.id(entry,"id")));
        h.put("history",history); h.put("reviewerName",history.stream().filter(e->List.of("COMPLETED","CORRECTION").contains(e.get("action"))).reduce((a,b)->b).map(e->e.get("actorName")).orElse("")); h.put("mistakes",db.list("SELECT id,title,knowledge_point,error_reason,status FROM mistake WHERE homework_id=? ORDER BY id",id)); return h;
    }
    Map<String,Object> lockHomework(long id) { var h=db.one("SELECT * FROM homework WHERE id=?",id); sec.student(Db.id(h,"studentId")); finance.lockStudent(Db.id(h,"studentId")); return db.one("SELECT * FROM homework WHERE id=? FOR UPDATE",id); }
    @Transactional public Map<String,Object> task(Map<String,Object> b) {
        sec.staff(); var ids=Db.ids(b,"studentIds"); for(long id:ids) sec.student(id);
        var attachments=Db.optionalIds(b,"attachmentIds"); files.validate(attachments);
        String subject=Db.required(b,"subject",16),type=Db.required(b,"type",16); Db.choice(subject,"语文","数学","英语"); Db.choice(type,"WRITTEN","ORAL");
        if(Db.text(b,"description").length()>2000) throw ApiException.bad("description最多2000字");
        long task=db.insert("INSERT INTO homework_task(date,subject,type,title,description) VALUES(?,?,?,?,?)",sec.date(Db.text(b,"date")),subject,type,Db.required(b,"title",200),Db.text(b,"description"));
        for(long sid:ids) { long id=db.insert("INSERT INTO homework(task_id,student_id,status) VALUES(?,?,'NOT_STARTED')",task,sid); files.link(attachments,"HOMEWORK",id,sid); }
        sec.audit("HOMEWORK_ASSIGN","task="+task+", count="+ids.size()); return Map.of("taskId",task,"assignedCount",ids.size());
    }
    @Transactional public Map<String,Object> homeworkStatus(long id,Map<String,Object> b) {
        var h=lockHomework(id); String status=Db.required(b,"status",30),old=h.get("status").toString();
        long sid=Db.id(h,"studentId"); String key=growth.requestKey("homework:"+id,b,false);
        if(growth.replay(key,b)!=null) return homework(id);
        growth.pastOrToday((LocalDate)db.one("SELECT date FROM homework_task WHERE id=?",h.get("taskId")).get("date"));
        growth.version(h,b);
        if(sec.actor().role().equals("PARENT")) {
            boolean allowed=status.equals("PENDING_REVIEW")&&(old.equals("NOT_STARTED")||old.equals("IN_PROGRESS")||old.equals(status)) || status.equals("PENDING_RECHECK")&&(old.equals("CORRECTION")||old.equals(status));
            if(!allowed) throw ApiException.bad("家长仅可提交作业或订正");
        } else {
            Db.choice(status,"IN_PROGRESS","PENDING_REVIEW","PENDING_RECHECK");
            if(old.equals("COMPLETED") || status.equals("PENDING_RECHECK")&&!List.of("CORRECTION","PENDING_RECHECK").contains(old) || !status.equals("PENDING_RECHECK")&&List.of("CORRECTION","PENDING_RECHECK").contains(old)) throw ApiException.bad("当前作业状态不可直接修改");
        }
        String text=Db.text(b,"submissionText"); if(text.length()>2000) throw ApiException.bad("提交说明最多2000字");
        var attachments=Db.optionalIds(b,"attachmentIds"); if(attachments.size()>6) throw ApiException.bad("每次最多6个附件");
        files.validate(attachments);
        if(status.equals("PENDING_RECHECK")&&text.isBlank()&&attachments.stream().noneMatch(fid->db.count("SELECT COUNT(*) FROM file_link WHERE file_id=? AND kind='HOMEWORK' AND target_id=?",fid,id)==0)) throw ApiException.bad("请补充订正说明或新的订正附件");
        var task=db.one("SELECT date,title,type FROM homework_task WHERE id=?",h.get("taskId"));
        if(sec.actor().role().equals("PARENT")&&status.equals("PENDING_REVIEW")&&task.get("type").equals("ORAL")) {
            List<Long> candidates=new ArrayList<>(attachments); for(var file:files.linked("HOMEWORK",id)) candidates.add(Db.id(file,"id"));
            // Assignment examples share HOMEWORK links; answers need submission evidence or a bound guardian upload.
            boolean audio=candidates.stream().anyMatch(fid->db.count("SELECT COUNT(*) FROM stored_file f WHERE f.id=? AND f.content_type LIKE 'audio/%' AND (EXISTS(SELECT 1 FROM file_link l JOIN growth_event e ON e.id=l.target_id WHERE l.file_id=f.id AND l.kind='GROWTH' AND l.student_id=? AND e.source_type='HOMEWORK' AND e.source_id=? AND e.action IN ('PENDING_REVIEW','PENDING_RECHECK')) OR EXISTS(SELECT 1 FROM parent_student ps JOIN app_user u ON u.id=ps.user_id WHERE ps.user_id=f.owner_id AND ps.student_id=? AND u.role='PARENT'))",fid,sid,id,sid)>0);
            if(!audio) throw ApiException.bad("口头作业请先上传录音");
        }
        if(old.equals(status)&&text.isBlank()&&attachments.isEmpty()) return homework(id);
        files.link(attachments,"HOMEWORK",id,sid);
        db.update("UPDATE homework SET status=?,version=version+1,updated_at=?,submitted_at=?,last_actor_id=? WHERE id=?",status,sec.now(),status.equals("IN_PROGRESS")?h.get("submittedAt"):sec.now(),sec.actor().id(),id);
        growth.event(sid,(LocalDate)task.get("date"),"HOMEWORK","HOMEWORK",id,status,task.get("title").toString(),text.isBlank()?"作业状态已更新":text,status,sec.now(),"homework:"+id+":"+(Db.integer(h,"version",0)+1),key,b,attachments);
        sec.audit("HOMEWORK_STATUS","homework="+id+", status="+status); return homework(id);
    }
    @Transactional public Map<String,Object> reviewHomework(long id,Map<String,Object> b) {
        sec.staff(); var h=lockHomework(id); String status=Db.required(b,"status",30); Db.choice(status,"COMPLETED","CORRECTION");
        String key=growth.requestKey("homework:"+id,b,false); if(growth.replay(key,b)!=null) return homework(id);
        var task=db.one("SELECT date,title FROM homework_task WHERE id=?",h.get("taskId")); growth.pastOrToday((LocalDate)task.get("date"));
        if(h.get("status").equals("COMPLETED")) { if(status.equals("COMPLETED")) return homework(id); throw ApiException.bad("已完成作业不可退回"); }
        growth.version(h,b);
        int accuracy=Db.integer(b,"accuracy",-1); if(accuracy<0||accuracy>100||Db.text(b,"comment").length()>2000) throw ApiException.bad("正确率须为0至100且评语不超过2000字");
        if(status.equals("CORRECTION")&&Db.text(b,"comment").isBlank()) throw ApiException.bad("请说明需要订正的内容");
        if(h.get("status").equals(status)&&Objects.equals(h.get("accuracy"),accuracy)&&h.get("comment").equals(Db.text(b,"comment"))) return homework(id);
        db.update("UPDATE homework SET status=?,accuracy=?,comment=?,version=version+1,updated_at=?,reviewed_at=?,completed_at=?,last_actor_id=? WHERE id=?",status,accuracy,Db.text(b,"comment"),sec.now(),sec.now(),status.equals("COMPLETED")?sec.now():null,sec.actor().id(),id);
        if(status.equals("COMPLETED")) finance.points(Db.id(h,"studentId"),finance.rule("HOMEWORK_COMPLETED"),"HOMEWORK_COMPLETED","完成作业","homework:"+id);
        growth.event(Db.id(h,"studentId"),(LocalDate)task.get("date"),"HOMEWORK","HOMEWORK",id,status,task.get("title").toString(),Db.text(b,"comment").isBlank()?"老师现场检查确认":Db.text(b,"comment"),status,sec.now(),"homework:"+id+":"+(Db.integer(h,"version",0)+1),key,b,List.of());
        sec.audit("HOMEWORK_REVIEW","homework="+id+", status="+status); return homework(id);
    }
    @Transactional public Map<String,Object> homeworkFiles(long id,List<Long> ids) { var h=lockHomework(id); files.link(ids,"HOMEWORK",id,Db.id(h,"studentId")); return homework(id); }
    String mistakeQuery() { return "SELECT m.*,s.name student_name FROM mistake m JOIN student s ON s.id=m.student_id"; }
    Map<String,Object> decorateMistake(Map<String,Object> m) {
        long id=Db.id(m,"id"); m.put("attachments",files.linked("MISTAKE",id));
        var corrections=db.list("SELECT id,text,created_at FROM correction WHERE mistake_id=? ORDER BY id",id);
        for(var c:corrections) c.put("attachments",files.linked("CORRECTION",Db.id(c,"id")));
        m.put("corrections",corrections); m.put("reviews",db.list("SELECT id,due_date,status,completed_at FROM mistake_review WHERE mistake_id=? ORDER BY due_date",id));
        m.put("history",db.list("SELECT id,action,from_status,to_status,actor_id,actor_name,actor_role,comment,correction_id,review_id,created_at,legacy FROM mistake_history WHERE mistake_id=? ORDER BY id",id)); return m;
    }
    void mistakeEvent(long id,String action,String from,String to,String comment,Long correction,Long review) {
        db.update("INSERT INTO mistake_history(mistake_id,action,from_status,to_status,actor_id,actor_name,actor_role,comment,correction_id,review_id,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",id,action,from,to,sec.actor().id(),sec.actor().name(),sec.actor().role(),comment,correction,review,sec.now());
    }
    Map<String,Object> mistake(long id) { var m=db.one(mistakeQuery()+" WHERE m.id=?",id); sec.student(Db.id(m,"studentId")); return decorateMistake(m); }
    Map<String,Object> lockMistake(long id) { var m=db.one("SELECT * FROM mistake WHERE id=?",id); sec.student(Db.id(m,"studentId")); finance.lockStudent(Db.id(m,"studentId")); return db.one("SELECT * FROM mistake WHERE id=? FOR UPDATE",id); }
    @Transactional public Map<String,Object> createMistake(Map<String,Object> b) {
        long sid=Db.number(b,"studentId"); sec.student(sid); String subject=Db.required(b,"subject",16); Db.choice(subject,"语文","数学","英语");
        Long homework=b.get("homeworkId")==null?null:Db.number(b,"homeworkId");
        if(homework!=null) { var h=homework(homework); if(Db.id(h,"studentId")!=sid) throw ApiException.bad("作业与学生不匹配"); }
        if(Db.text(b,"knowledgePoint").length()>300||Db.text(b,"errorReason").length()>1000) throw ApiException.bad("知识点或错因过长");
        var attachmentIds=Db.optionalIds(b,"attachmentIds"); files.validate(attachmentIds);
        String question=Db.text(b,"questionText"),title=Db.text(b,"title");if(question.isBlank())question=title;
        if(question.length()>10000||title.length()>300)throw ApiException.bad("题目正文最多10000字，标题最多300字");
        if(question.isBlank()&&attachmentIds.isEmpty())throw ApiException.bad("请填写题目或上传题目照片");
        if(question.isBlank()&&attachmentIds.stream().noneMatch(file->db.one("SELECT content_type FROM stored_file WHERE id=?",file).get("contentType").toString().startsWith("image/")))throw ApiException.bad("请上传题目照片或填写题目");
        if(title.isBlank())title=question.isBlank()?"拍照错题":question.substring(0,Math.min(question.length(),100));
        long id=db.insert("INSERT INTO mistake(student_id,subject,title,question_text,knowledge_point,error_reason,status,homework_id,created_at) VALUES(?,?,?,?,?,?,'UNCORRECTED',?,?)",sid,subject,title,question,Db.text(b,"knowledgePoint"),Db.text(b,"errorReason"),homework,sec.now());
        files.link(attachmentIds,"MISTAKE",id,sid); mistakeEvent(id,"CREATE",null,"UNCORRECTED",Db.text(b,"title"),null,null); sec.audit("MISTAKE_CREATE","mistake="+id); return mistake(id);
    }
    @Transactional public Map<String,Object> correctMistake(long id,Map<String,Object> b) {
        var m=lockMistake(id); if(m.get("status").equals("MASTERED")) throw ApiException.bad("已掌握错题不可重复订正");
        String text=Db.required(b,"text",2000); var attachments=Db.optionalIds(b,"attachmentIds"); files.validate(attachments);
        long correction=db.insert("INSERT INTO correction(mistake_id,text,created_at) VALUES(?,?,?)",id,text,sec.now()); files.link(attachments,"CORRECTION",correction,Db.id(m,"studentId"));
        db.update("UPDATE mistake SET status='PENDING_REVIEW',correction_text=? WHERE id=?",text,id);
        mistakeEvent(id,"CORRECT",m.get("status").toString(),"PENDING_REVIEW",text,correction,null);
        sec.audit("MISTAKE_CORRECT","mistake="+id+", correction="+correction); return mistake(id);
    }
    @Transactional public Map<String,Object> reviewMistake(long id,Map<String,Object> b) {
        sec.staff(); var m=lockMistake(id); if(!(b.get("approved") instanceof Boolean)) throw ApiException.bad("approved须为布尔值"); boolean approved=Boolean.TRUE.equals(b.get("approved"));
        if(!m.get("status").equals("PENDING_REVIEW")&&!m.get("status").equals("MASTERED")) throw ApiException.bad("请先提交订正");
        if(Db.text(b,"comment").length()>2000) throw ApiException.bad("评语最多2000字");
        if(!approved&&Db.text(b,"comment").isBlank()) throw ApiException.bad("请填写继续订正的原因");
        if(m.get("status").equals("MASTERED")) { if(approved) return mistake(id); throw ApiException.bad("已掌握错题不可退回"); }
        db.update("UPDATE mistake SET status=?,teacher_comment=? WHERE id=?",approved?"MASTERED":"UNCORRECTED",Db.text(b,"comment"),id);
        mistakeEvent(id,approved?"APPROVE":"REJECT",m.get("status").toString(),approved?"MASTERED":"UNCORRECTED",Db.text(b,"comment"),null,null);
        if(approved) {
            finance.points(Db.id(m,"studentId"),finance.rule("MISTAKE_MASTERED"),"MISTAKE_MASTERED","错题订正通过","mistake:"+id);
            LocalDate approvedOn=sec.today();
            for(int days:new int[]{1,3,7}) if(db.count("SELECT COUNT(*) FROM mistake_review WHERE mistake_id=? AND due_date=?",id,approvedOn.plusDays(days))==0) db.update("INSERT INTO mistake_review(mistake_id,due_date,status) VALUES(?,?,'PENDING')",id,approvedOn.plusDays(days));
        }
        sec.audit("MISTAKE_REVIEW","mistake="+id+", approved="+approved); return mistake(id);
    }
    @Transactional public Map<String,Object> completeReview(long id) {
        sec.staff(); var r=db.one("SELECT r.mistake_id,r.due_date,r.status,m.student_id FROM mistake_review r JOIN mistake m ON m.id=r.mistake_id WHERE r.id=?",id); sec.student(Db.id(r,"studentId"));
        if(r.get("status").equals("PENDING")&&((LocalDate)r.get("dueDate")).isAfter(sec.today())) throw ApiException.bad("尚未到复习日期");
        if(db.update("UPDATE mistake_review SET status='DONE',completed_at=? WHERE id=? AND status='PENDING'",sec.now(),id)>0) {
            mistakeEvent(Db.id(r,"mistakeId"),"REVIEW_COMPLETE","PENDING","DONE","已完成到期复习",null,id);
            sec.audit("MISTAKE_REVIEW_COMPLETE","review="+id);
        }
        return Map.of("success",true);
    }
    @Transactional public Map<String,Object> fitness(Map<String,Object> b) {
        sec.staff(); var ids=Db.ids(b,"studentIds"); for(long sid:ids) sec.student(sid);
        int count=Db.integer(b,"count",0),duration=Db.integer(b,"duration",0); if(count<0||duration<0) throw ApiException.bad("数量、时长不可为负");
        LocalDate date=sec.date(Db.text(b,"date")); String project=Db.required(b,"project",80); int recorded=0;
        LocalDateTime at=growth.occurred(b,date);
        for(long sid:ids) {
            finance.lockStudent(sid); if(db.count("SELECT COUNT(*) FROM fitness WHERE student_id=? AND date=? AND project=?",sid,date,project)>0) continue;
            long id=db.insert("INSERT INTO fitness(student_id,date,project,count,duration,confirmed,occurred_at,recorded_at,actor_id) VALUES(?,?,?,?,?,TRUE,?,?,?)",sid,date,project,count,duration,at,sec.now(),sec.actor().id());
            finance.points(sid,finance.rule("FITNESS"),"FITNESS","体能："+project,"fitness:"+id); recorded++;
            growth.event(sid,date,"FITNESS","FITNESS",id,"RECORDED",project,count+" 次 · "+duration+" 秒","RECORDED",at,"fitness:"+id,null,Map.of(),List.of());
        }
        sec.audit("FITNESS_BATCH",date+" "+project+" count="+recorded); return Map.of("recordedCount",recorded);
    }
    String summaryContent(long sid,LocalDate date) {
        long total=db.count("SELECT COUNT(*) FROM homework h JOIN homework_task t ON t.id=h.task_id WHERE h.student_id=? AND t.date=?",sid,date);
        long done=db.count("SELECT COUNT(*) FROM homework h JOIN homework_task t ON t.id=h.task_id WHERE h.student_id=? AND t.date=? AND h.status='COMPLETED'",sid,date);
        long corrections=db.count("SELECT COUNT(*) FROM homework h JOIN homework_task t ON t.id=h.task_id WHERE h.student_id=? AND t.date=? AND h.status='CORRECTION'",sid,date);
        var a=db.optional("SELECT status,check_in_time,check_out_time FROM attendance WHERE student_id=? AND date=?",sid,date);
        StringBuilder text=new StringBuilder(date+" 学习日报\n考勤："+(a==null?"未到校":a.get("status").equals("LEFT")?"已离校":"在校"));
        if(a!=null) text.append("，到校 ").append(a.get("checkInTime")==null?"具体时间未记录":a.get("checkInTime")).append("，离校 ").append(a.get("checkOutTime")==null?(a.get("status").equals("LEFT")?"具体时间未记录":"尚未离校"):a.get("checkOutTime"));
        text.append("\n作业完成 ").append(done).append('/').append(total).append("，待订正 ").append(corrections);
        for(String subject:List.of("语文","数学","英语")) {
            var tasks=db.list("SELECT t.title,h.status,h.accuracy FROM homework h JOIN homework_task t ON t.id=h.task_id WHERE h.student_id=? AND t.date=? AND t.subject=? ORDER BY h.id",sid,date,subject);
            text.append("\n").append(subject).append("："); if(tasks.isEmpty()) text.append("无作业");
            for(var h:tasks) text.append(h.get("title")).append(' ').append(h.get("status")).append(h.get("accuracy")==null?"":" 正确率"+h.get("accuracy")+"%").append("；");
        }
        text.append("\n错题：").append(db.count("SELECT COUNT(*) FROM mistake WHERE student_id=? AND status<>'MASTERED'",sid)).append("题待掌握；当日订正").append(db.count("SELECT COUNT(*) FROM correction c JOIN mistake m ON m.id=c.mistake_id WHERE m.student_id=? AND c.created_at>=? AND c.created_at<?",sid,date.atStartOfDay(),date.plusDays(1).atStartOfDay())).append("次；到期复习").append(db.count("SELECT COUNT(*) FROM mistake_review r JOIN mistake m ON m.id=r.mistake_id WHERE m.student_id=? AND r.due_date<=? AND r.status='PENDING'",sid,date)).append("项");
        text.append("\n体能："); var fitness=db.list("SELECT project,count,duration,confirmed FROM fitness WHERE student_id=? AND date=? ORDER BY id",sid,date); long confirmedSeconds=0;
        if(fitness.isEmpty()) text.append("未记录");
        for(var f:fitness) {
            boolean confirmed=Boolean.TRUE.equals(f.get("confirmed"));
            text.append(f.get("project")).append(confirmed?"":"（待确认）").append(' ').append(f.get("count")).append("次 / ").append(f.get("duration")).append("秒；");
            if(confirmed) confirmedSeconds+=((Number)f.get("duration")).longValue();
        }
        if(!fitness.isEmpty()) text.append("已确认合计：").append(confirmedSeconds).append("秒");
        text.append("\n积分：当日变动 ").append(db.one("SELECT COALESCE(SUM(amount),0) amount FROM point_ledger WHERE student_id=? AND created_at>=? AND created_at<?",sid,date.atStartOfDay(),date.plusDays(1).atStartOfDay()).get("amount")).append("，余额 ").append(db.one("SELECT points FROM student WHERE id=?",sid).get("points"));
        for(var concern:growth.concerns(date,sid,"ISSUE")) text.append("\n关注事项：").append(concern.get("title")).append("；情况：").append(concern.get("reason")).append("；措施：").append(concern.get("measures")).append("；状态：").append(concern.get("status"));
        for(var pause:db.list("SELECT reason,measures FROM fitness_pause WHERE student_id=? AND date=?",sid,date)) text.append("\n运动暂停：").append(pause.get("reason")).append("；措施：").append(pause.get("measures"));
        for(var account:db.list("SELECT a.id,c.name,c.unit,a.balance FROM lesson_account a JOIN course c ON c.id=a.course_id WHERE a.student_id=? ORDER BY a.id",sid)) {
            Object consumed=db.one("SELECT COALESCE(SUM(sc.units),0) amount FROM settlement t JOIN schedule sc ON sc.id=t.schedule_id WHERE t.account_id=? AND t.date=? AND t.status='SETTLED'",account.get("id"),date).get("amount");
            text.append("\n课时账户 ").append(account.get("name")).append("：当日消耗 ").append(consumed).append(account.get("unit")).append("，余额 ").append(account.get("balance")).append(account.get("unit"));
        }
        return text.toString();
    }
    String summaryQuery() { return "SELECT d.id,d.student_id,s.name student_name,cl.campus_id,cp.name campus_name,d.date,d.status,CASE WHEN d.status='PUBLISHED' THEN d.published_content ELSE d.content END content,CASE WHEN d.status='PUBLISHED' THEN d.published_comment ELSE d.teacher_comment END teacher_comment,d.version,d.published_at,d.feedback_rating,d.feedback_satisfaction,d.feedback_text,d.notification_status,d.submitted_by,d.submitted_name,d.submitted_at,d.reviewed_by,d.reviewed_name,d.reviewed_at,d.review_comment,d.review_version FROM daily_summary d JOIN student s ON s.id=d.student_id JOIN school_class cl ON cl.id=s.class_id JOIN campus cp ON cp.id=cl.campus_id"; }
    Map<String,Object> decorateSummary(Map<String,Object> d) {
        boolean staff=!sec.actor().role().equals("PARENT"),editable=List.of("DRAFT","REJECTED").contains(d.get("status")),pending=d.get("status").equals("IN_REVIEW");
        boolean submitter=Objects.equals(d.get("submittedBy"),sec.actor().id());
        d.put("canEdit",staff&&editable); d.put("canSubmit",staff&&editable); d.put("canWithdraw",staff&&pending&&submitter);
        d.put("canReview",sec.isAdmin()&&pending&&!submitter); d.put("canPublish",staff&&d.get("status").equals("APPROVED"));
        d.put("photos",files.linked("SUMMARY_PHOTO",Db.id(d,"id")));
        var history=staff?db.list("SELECT id,action,from_status,to_status,version,actor_id,actor_name,actor_role,comment,content,teacher_comment,created_at FROM summary_review_history WHERE summary_id=? ORDER BY id",d.get("id")):List.<Map<String,Object>>of();
        for(var row:history) row.put("photos",files.linked("SUMMARY_HISTORY",Db.id(row,"id")));
        d.put("reviewHistory",history);
        if(!staff) d.remove("reviewComment"); return d;
    }
    Map<String,Object> summary(long id) {
        var d=db.one(summaryQuery()+" WHERE d.id=?",id); sec.student(Db.id(d,"studentId"));
        if(sec.actor().role().equals("PARENT")&&!d.get("status").equals("PUBLISHED")) throw ApiException.forbidden();
        return decorateSummary(d);
    }
    Map<String,Object> lockSummary(long id) {
        var d=db.one("SELECT student_id FROM daily_summary WHERE id=?",id); sec.student(Db.id(d,"studentId")); finance.lockStudent(Db.id(d,"studentId"));
        return db.one("SELECT * FROM daily_summary WHERE id=? FOR UPDATE",id);
    }
    void summaryVersion(Map<String,Object> d,Map<String,Object> b) {
        if(!b.containsKey("expectedVersion")) throw ApiException.bad("请携带 expectedVersion，刷新小结后重试"); growth.version(d,b);
    }
    void summaryEvent(long id,String action,String from,String comment) {
        var d=db.one("SELECT * FROM daily_summary WHERE id=?",id);
        long historyId=db.insert("INSERT INTO summary_review_history(summary_id,action,from_status,to_status,version,actor_id,actor_name,actor_role,comment,content,teacher_comment,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",id,action,from,d.get("status"),d.get("version"),sec.actor().id(),sec.actor().name(),sec.actor().role(),comment,d.get("content"),d.get("teacherComment"),sec.now());
        db.update("INSERT INTO file_link(file_id,kind,target_id,student_id) SELECT file_id,'SUMMARY_HISTORY',?,student_id FROM file_link WHERE kind='SUMMARY_PHOTO' AND target_id=?",historyId,id);
    }
    @Transactional public Map<String,Object> generate(Map<String,Object> b) {
        sec.staff(); var ids=Db.ids(b,"studentIds"); for(long id:ids) sec.student(id); LocalDate date=sec.date(Db.text(b,"date")); int generated=0;
        for(long sid:ids) {
            finance.lockStudent(sid); var d=db.optional("SELECT * FROM daily_summary WHERE student_id=? AND date=?",sid,date);
            if(d!=null&&(Boolean.TRUE.equals(b.get("keepExisting"))||!List.of("DRAFT","REJECTED").contains(d.get("status")))) continue;
            String content=summaryContent(sid,date); if(content.length()>12000) throw ApiException.bad("小结正文最多12000字，请先减少当天记录或分别整理"); long id;
            if(d==null) { id=db.insert("INSERT INTO daily_summary(student_id,date,status,content) VALUES(?,?,'DRAFT',?)",sid,date,content); summaryEvent(id,"GENERATE",null,""); }
            else { id=Db.id(d,"id"); db.update("UPDATE daily_summary SET content=?,version=version+1 WHERE id=?",content,id); summaryEvent(id,"REFRESH",d.get("status").toString(),""); }
            generated++;
        }
        sec.audit("SUMMARY_GENERATE",date+" count="+generated); return Map.of("generatedCount",generated);
    }
    @Transactional public Map<String,Object> editSummary(long id,Map<String,Object> b) {
        sec.staff(); var d=lockSummary(id); growth.version(d,b);
        if(!List.of("DRAFT","REJECTED").contains(d.get("status"))) throw ApiException.bad("仅草稿或退回的小结可编辑");
        String comment=Db.text(b,"teacherComment"),content=b.containsKey("content")?Db.required(b,"content",12000):d.get("content").toString();
        if(comment.length()>2000) throw ApiException.bad("评语最多2000字");
        boolean photoChanged=false;
        if(b.containsKey("photoId")) {
            summaryVersion(d,b);
            var current=db.optional("SELECT file_id FROM file_link WHERE kind='SUMMARY_PHOTO' AND target_id=?",id);
            Long oldId=current==null?null:Db.id(current,"fileId");
            Long photo=b.get("photoId")==null?null:Db.number(b,"photoId");
            photoChanged=!Objects.equals(oldId,photo);
            if(photoChanged) {
                if(photo!=null) {
                    var f=db.one("SELECT owner_id,content_type FROM stored_file WHERE id=? FOR UPDATE",photo);
                    if(Db.id(f,"ownerId")!=sec.actor().id()||db.count("SELECT COUNT(*) FROM file_link WHERE file_id=?",photo)>0) throw ApiException.forbidden();
                    if(!f.get("contentType").toString().startsWith("image/")) throw ApiException.bad("作品须为图片");
                    files.link(List.of(photo),"SUMMARY_PHOTO",id,Db.id(d,"studentId"));
                }
                if(oldId!=null) db.update("DELETE FROM file_link WHERE file_id=? AND kind='SUMMARY_PHOTO' AND target_id=?",oldId,id);
            }
        }
        if(!photoChanged&&content.equals(d.get("content"))&&comment.equals(d.get("teacherComment"))) return summary(id);
        db.update("UPDATE daily_summary SET content=?,teacher_comment=?,version=version+1 WHERE id=?",content,comment,id);
        summaryEvent(id,"EDIT",d.get("status").toString(),""); sec.audit("SUMMARY_EDIT","summary="+id); return summary(id);
    }
    @Transactional public Map<String,Object> submitSummary(long id,Map<String,Object> b) {
        sec.staff(); var d=lockSummary(id); summaryVersion(d,b); growth.pastOrToday((LocalDate)d.get("date"));
        if(!List.of("DRAFT","REJECTED").contains(d.get("status"))) throw ApiException.bad("仅草稿或退回的小结可提交审核");
        int version=Db.integer(d,"version",0)+1;
        db.update("UPDATE daily_summary SET status='IN_REVIEW',version=?,review_version=?,submitted_by=?,submitted_name=?,submitted_at=?,reviewed_by=NULL,reviewed_name=NULL,reviewed_at=NULL,review_comment='',review_content=content,review_teacher_comment=teacher_comment WHERE id=?",version,version,sec.actor().id(),sec.actor().name(),sec.now(),id);
        summaryEvent(id,"SUBMIT",d.get("status").toString(),""); sec.audit("SUMMARY_SUBMIT","summary="+id); return summary(id);
    }
    @Transactional public Map<String,Object> withdrawSummary(long id,Map<String,Object> b) {
        sec.staff(); var d=lockSummary(id);
        if(!Objects.equals(d.get("submittedBy"),sec.actor().id())) throw ApiException.forbidden();
        summaryVersion(d,b); if(!d.get("status").equals("IN_REVIEW")) throw ApiException.bad("仅待审小结可撤回");
        db.update("UPDATE daily_summary SET status='DRAFT',version=version+1,submitted_by=NULL,submitted_name=NULL,submitted_at=NULL,review_version=NULL,review_content=NULL,review_teacher_comment=NULL WHERE id=?",id);
        summaryEvent(id,"WITHDRAW","IN_REVIEW",""); sec.audit("SUMMARY_WITHDRAW","summary="+id); return summary(id);
    }
    @Transactional public Map<String,Object> reviewSummary(long id,Map<String,Object> b) {
        sec.admin(); var d=lockSummary(id);
        if(Objects.equals(d.get("submittedBy"),sec.actor().id())) throw new ApiException(403,"小结提交者不能审核自己的内容，请由另一位校长或老板审核");
        summaryVersion(d,b); if(!d.get("status").equals("IN_REVIEW")||d.get("submittedBy")==null) throw ApiException.bad("仅待审小结可审核");
        if(!(b.get("approved") instanceof Boolean)) throw ApiException.bad("approved须为布尔值");
        boolean approved=Boolean.TRUE.equals(b.get("approved")); String comment=Db.required(b,"comment",2000),status=approved?"APPROVED":"REJECTED";
        db.update("UPDATE daily_summary SET status=?,version=version+1,reviewed_by=?,reviewed_name=?,reviewed_at=?,review_comment=? WHERE id=?",status,sec.actor().id(),sec.actor().name(),sec.now(),comment,id);
        summaryEvent(id,approved?"APPROVE":"REJECT","IN_REVIEW",comment); sec.audit("SUMMARY_REVIEW","summary="+id+", approved="+approved); return summary(id);
    }
    @Transactional public Map<String,Object> publish(long id) { return publish(id,false); }
    @Transactional public Map<String,Object> publish(long id,boolean confirmedConcerns) {
        sec.staff(); var d=lockSummary(id); growth.pastOrToday((LocalDate)d.get("date"));
        if(d.get("status").equals("PUBLISHED")) return summary(id);
        if(!d.get("status").equals("APPROVED")||d.get("reviewedBy")==null||d.get("reviewContent")==null||Objects.equals(d.get("submittedBy"),d.get("reviewedBy"))) throw ApiException.bad("小结须由另一位校长或老板审核通过后再发布");
        if(!confirmedConcerns&&growth.concerns((LocalDate)d.get("date"),Db.id(d,"studentId"),"ISSUE").stream().anyMatch(c->List.of("OPEN","IN_PROGRESS").contains(c.get("status")))) throw ApiException.bad("请核对该学生待跟进事项的原因和措施后确认发布");
        db.update("UPDATE daily_summary SET status='PUBLISHED',published_content=review_content,published_comment=review_teacher_comment,version=version+1,published_at=?,notification_status='NOT_CONFIGURED' WHERE id=?",sec.now(),id);
        finance.points(Db.id(d,"studentId"),finance.rule("SUMMARY_PUBLISHED"),"SUMMARY_PUBLISHED","完成日小结","summary:"+id);
        summaryEvent(id,"PUBLISH","APPROVED",""); sec.audit("SUMMARY_PUBLISH","summary="+id); return summary(id);
    }
    @Transactional public Map<String,Object> feedback(long id,Map<String,Object> b) {
        sec.parent(); var row=lockSummary(id); if(!row.get("status").equals("PUBLISHED")) throw ApiException.forbidden();
        if(!Set.of("satisfaction","text").containsAll(b.keySet())) throw ApiException.bad("请选择满意或不满意，不再提交数字评分");
        String satisfaction=Db.required(b,"satisfaction",20),text=Db.text(b,"text"); Db.choice(satisfaction,"SATISFIED","DISSATISFIED");
        if(text.length()>2000||satisfaction.equals("DISSATISFIED")&&text.isBlank()) throw ApiException.bad("不满意时请填写原因（最多2000字）");
        db.update("UPDATE daily_summary SET feedback_rating=NULL,feedback_satisfaction=?,feedback_text=? WHERE id=?",satisfaction,text,id); sec.audit("SUMMARY_FEEDBACK","summary="+id+", satisfaction="+satisfaction); return summary(id);
    }
}
