package cn.wanfu;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PrivacyService {
    static final String POLICY_VERSION="2026-10-v1";
    final Db db; final Security sec; final ObjectMapper json; final boolean consentRequired;
    public PrivacyService(Db db,Security sec,ObjectMapper json,@Value("${app.privacy.consent-required:false}") boolean consentRequired) { this.db=db; this.sec=sec; this.json=json; this.consentRequired=consentRequired; }
    @Transactional public Map<String,Object> logout() { return sec.logout(); }
    Map<String,Object> policy() {
        return Map.of("version",POLICY_VERSION,"consentRequired",consentRequired,"serviceText","为提供托管服务，机构记录孩子的基本资料、出勤、作业、成长表现和课时账目。资料按账号与校区权限提供给监护人及负责老师，不向其他家庭公开。",
            "mediaText",consentRequired?"图片、录音和视频用于提交作业与记录成长。媒体授权可单独撤回；撤回后暂停孩子的新媒体关联与媒体查看，共用媒体将暂停整文件查看。任一当前绑定监护人的明确撤回均优先于他人的旧同意。":"图片、录音和视频用于提交作业与记录成长，按孩子绑定、任教班级和校区权限提供查看。",
            "retentionText","可申请孩子的资料副本或资料脱敏。脱敏须先完成离校及费用、安全事项核对，并由管理员审核后再次确认；保留必要的脱敏账目和申请审计，共用附件不连带删除。",
            "externalConfiguration",List.of("机构正式告知文本与监护人身份核验","正式微信身份绑定与合法 HTTPS 域名","备份保留期限及到期清理规则"));
    }
    boolean effective(long student,String scope) {
        String latest=" FROM privacy_consent c JOIN parent_student p ON p.user_id=c.user_id AND p.student_id=c.student_id WHERE c.student_id=? AND c.scope=? AND c.policy_version=? AND NOT EXISTS (SELECT 1 FROM privacy_consent n WHERE n.student_id=c.student_id AND n.user_id=c.user_id AND n.scope=c.scope AND n.policy_version=c.policy_version AND n.id>c.id)";
        return db.count("SELECT COUNT(*)"+latest+" AND c.granted=TRUE",student,scope,POLICY_VERSION)>0 && db.count("SELECT COUNT(*)"+latest+" AND c.granted=FALSE",student,scope,POLICY_VERSION)==0;
    }
    boolean canUseMedia(long student) { return !consentRequired||(effective(student,"SERVICE")&&effective(student,"MEDIA")); }
    void requireMedia(long student) {
        if(!canUseMedia(student)) throw new ApiException(403,"孩子的服务或媒体授权尚未完成或已撤回，请监护人在隐私与授权中确认");
    }
    Map<String,Object> consents(long student) {
        sec.student(student); Map<String,Object> out=new LinkedHashMap<>();
        out.put("studentId",student); out.put("policyVersion",POLICY_VERSION); out.put("consentRequired",consentRequired); out.put("canConsent",consentRequired&&sec.actor().role().equals("PARENT"));
        for(String scope:List.of("SERVICE","MEDIA")) {
            var row=db.optional("SELECT granted,policy_version,created_at FROM privacy_consent WHERE student_id=? AND user_id=? AND scope=? ORDER BY id DESC LIMIT 1",student,sec.actor().id(),scope);
            out.put(scope.toLowerCase(Locale.ROOT),row==null?Map.of("status","UNSET"):Map.of("status",Boolean.TRUE.equals(row.get("granted"))?"GRANTED":"WITHDRAWN","policyVersion",row.get("policyVersion"),"createdAt",row.get("createdAt")));
        }
        out.put("effectiveService",!consentRequired||effective(student,"SERVICE")); out.put("effectiveMedia",canUseMedia(student));
        out.put("history",db.list("SELECT id,scope,policy_version,granted,created_at FROM privacy_consent WHERE student_id=? AND user_id=? ORDER BY id DESC LIMIT 100",student,sec.actor().id()));
        return out;
    }
    @Transactional public Map<String,Object> consent(Map<String,Object> b) {
        sec.parent(); long student=Db.number(b,"studentId"); sec.student(student);
        if(!consentRequired) throw new ApiException(410,"家长授权功能已关闭，无需提交授权");
        db.one("SELECT id FROM student WHERE id=? FOR UPDATE",student); sec.student(student);
        String scope=Db.required(b,"scope",20); Db.choice(scope,"SERVICE","MEDIA");
        if(!POLICY_VERSION.equals(Db.required(b,"policyVersion",40))) throw new ApiException(409,"授权说明已更新，请阅读最新版本后确认");
        if(!(b.get("granted") instanceof Boolean granted)) throw ApiException.bad("请明确选择同意或撤回");
        var old=db.optional("SELECT granted FROM privacy_consent WHERE student_id=? AND user_id=? AND scope=? AND policy_version=? ORDER BY id DESC LIMIT 1",student,sec.actor().id(),scope,POLICY_VERSION);
        if(old==null||!old.get("granted").equals(granted)) {
            db.update("INSERT INTO privacy_consent(student_id,user_id,scope,policy_version,granted,created_at) VALUES(?,?,?,?,?,?)",student,sec.actor().id(),scope,POLICY_VERSION,granted,sec.now());
            sec.audit(granted?"CONSENT_GRANT":"CONSENT_WITHDRAW","student="+student+", scope="+scope+", version="+POLICY_VERSION);
        }
        return consents(student);
    }
    String requestQuery() { return "SELECT r.id,r.student_id,s.name student_name,r.requested_by,r.type,r.status,r.reason,r.review_note,r.reviewed_at,r.completed_at,r.created_at FROM privacy_request r JOIN student s ON s.id=r.student_id"; }
    Map<String,Object> visible(long id) {
        var row=db.one(requestQuery()+" WHERE r.id=?",id);
        if(sec.actor().role().equals("PARENT")) {
            if(Db.id(row,"requestedBy")!=sec.actor().id()) throw ApiException.forbidden();
        } else { sec.admin(); sec.campus(sec.studentCampus(Db.id(row,"studentId"))); }
        return row;
    }
    Map<String,Object> receipt(Map<String,Object> row) {
        if(sec.actor().role().equals("PARENT")&&db.count("SELECT COUNT(*) FROM parent_student WHERE user_id=? AND student_id=?",sec.actor().id(),row.get("studentId"))==0) {
            Map<String,Object> out=new LinkedHashMap<>();
            for(String key:List.of("id","type","status","createdAt","reviewedAt","completedAt")) out.put(key,row.get(key));
            out.put("studentName","已解除绑定的孩子"); out.put("canExport",false); return out;
        }
        row.put("canExport",row.get("type").equals("EXPORT")&&List.of("APPROVED","COMPLETED").contains(row.get("status"))); return row;
    }
    Map<String,Object> request(long id) { return receipt(visible(id)); }
    List<Map<String,Object>> requests(Long student) {
        if(!sec.actor().role().equals("PARENT")) sec.admin();
        List<Object> args=new ArrayList<>(); String where=" WHERE ";
        if(sec.actor().role().equals("PARENT")) { where+="r.requested_by=?"; args.add(sec.actor().id()); }
        else where+=sec.scope("s");
        if(student!=null) { where+=" AND r.student_id=?"; args.add(student); }
        return db.list(requestQuery()+where+" ORDER BY r.id DESC",args.toArray()).stream().map(this::receipt).toList();
    }
    @Transactional public Map<String,Object> createRequest(Map<String,Object> b) {
        sec.parent(); long student=Db.number(b,"studentId"); sec.student(student);
        db.one("SELECT id FROM student WHERE id=? FOR UPDATE",student); sec.student(student);
        String type=Db.required(b,"type",20); Db.choice(type,"EXPORT","ERASURE"); String reason=Db.required(b,"reason",1000);
        var open=db.optional("SELECT id FROM privacy_request WHERE student_id=? AND requested_by=? AND type=? AND status IN ('OPEN','APPROVED')",student,sec.actor().id(),type);
        if(open!=null) return visible(Db.id(open,"id"));
        long id=db.insert("INSERT INTO privacy_request(student_id,requested_by,type,reason,created_at) VALUES(?,?,?,?,?)",student,sec.actor().id(),type,reason,sec.now());
        sec.audit("PRIVACY_REQUEST","request="+id+", student="+student+", type="+type); return visible(id);
    }
    @Transactional public Map<String,Object> review(long id,Map<String,Object> b) {
        sec.admin(); var row=visible(id); long student=Db.id(row,"studentId"); sec.student(student);
        db.one("SELECT id FROM student WHERE id=? FOR UPDATE",student); sec.student(student);
        db.one("SELECT id FROM privacy_request WHERE id=? FOR UPDATE",id); row=visible(id);
        if(!row.get("status").equals("OPEN")) throw new ApiException(409,"申请已处理，请刷新核对");
        String decision=Db.required(b,"decision",20); Db.choice(decision,"APPROVE","REJECT"); String note=Db.required(b,"note",1000);
        db.update("UPDATE privacy_request SET status=?,review_note=?,reviewed_by=?,reviewed_at=? WHERE id=?",decision.equals("APPROVE")?"APPROVED":"REJECTED",note,sec.actor().id(),sec.now(),id);
        sec.audit("PRIVACY_REVIEW","request="+id+", decision="+decision); return visible(id);
    }
    @Transactional public Map<String,Object> export(long id) {
        var row=visible(id); long student=Db.id(row,"studentId"); sec.student(student);
        db.one("SELECT id FROM student WHERE id=? FOR UPDATE",student); sec.student(student);
        db.one("SELECT id FROM privacy_request WHERE id=? FOR UPDATE",id); row=visible(id);
        if(!row.get("type").equals("EXPORT")||!List.of("APPROVED","COMPLETED").contains(row.get("status"))) throw new ApiException(409,"请先完成资料副本申请审核");
        var saved=db.one("SELECT export_json FROM privacy_request WHERE id=?",id).get("exportJson");
        if(saved!=null) { sec.audit("PRIVACY_EXPORT_READ","request="+id+", student="+student); return parse(saved.toString()); }
        Map<String,Object> data=new LinkedHashMap<>(); data.put("student",db.one("SELECT id,name,gender,grade,parent_name,parent_phone FROM student WHERE id=?",student));
        for(var entry:Map.ofEntries(
            Map.entry("attendance","SELECT date,status,check_in_time,check_out_time FROM attendance WHERE student_id=?"),
            Map.entry("homework","SELECT h.id,t.date,t.subject,t.title,h.status,h.accuracy,h.comment FROM homework h JOIN homework_task t ON t.id=h.task_id WHERE h.student_id=?"),
            Map.entry("mistakes","SELECT id,subject,title,question_text,knowledge_point,error_reason,status,correction_text,teacher_comment FROM mistake WHERE student_id=?"),
            Map.entry("questionScans","SELECT id,subject,status,engine,page_width,page_height,created_at,updated_at FROM question_scan WHERE student_id=?"),
            Map.entry("scanQuestions","SELECT q.display_number,q.group_name,q.question_text,q.recognition_text,q.regions_json,q.imported_mistake_id FROM question_scan_item q JOIN question_scan j ON j.id=q.scan_id WHERE j.student_id=? AND q.deleted=FALSE"),
            Map.entry("fitness","SELECT date,project,count,duration,confirmed FROM fitness WHERE student_id=?"),
            Map.entry("points","SELECT amount,balance,type,reason,created_at FROM point_ledger WHERE student_id=?"),
            Map.entry("summaries","SELECT date,status,teacher_comment,published_content,published_comment,feedback_satisfaction,feedback_text FROM daily_summary WHERE student_id=?"),
            Map.entry("renewalIntents","SELECT r.account_id,r.intention,r.created_at,r.updated_at FROM renewal_intent r JOIN lesson_account a ON a.id=r.account_id WHERE a.student_id=?"),
            Map.entry("mealOrders","SELECT meal_date,ordered,amount,created_at,updated_at FROM meal_order WHERE student_id=?"),
            Map.entry("accounts","SELECT a.id,c.name course_name,a.balance,a.purchased,a.gifted,a.consumed FROM lesson_account a JOIN course c ON c.id=a.course_id WHERE a.student_id=?"),
            Map.entry("lessonLedger","SELECT l.type,l.amount,l.balance_before,l.balance_after,l.reason,l.created_at FROM lesson_ledger l JOIN lesson_account a ON a.id=l.account_id WHERE a.student_id=?"),
            Map.entry("followUps","SELECT business_date,kind,type,title,reason,measures,status FROM follow_up WHERE student_id=?"),
            Map.entry("growthComparisons","SELECT business_date,title,detail,occurred_at FROM growth_event WHERE student_id=? AND action='COMPARISON'"),
            Map.entry("leave","SELECT leave_date,reason,status,review_comment,created_at FROM leave_request WHERE student_id=?"),
            Map.entry("pickup","SELECT pickup_name,relationship,phone,valid_from,expires_at,mode,start_date,end_date,start_time,end_time,note,face_status,status,created_at FROM pickup_authorization WHERE student_id=?"),
            Map.entry("pickupContacts","SELECT name,relationship,phone,is_default,face_status,created_at,updated_at,deleted_at FROM pickup_contact WHERE student_id=?"),
            Map.entry("pickupUsage","SELECT business_date,used_at FROM pickup_use WHERE student_id=?"),
            Map.entry("pickupAlerts","SELECT business_date,pickup_name,phone_last4,reason,status,created_at FROM pickup_alert WHERE student_id=?"),
            Map.entry("safetyHistory","SELECT entity_type,action,detail,created_at FROM safety_log WHERE student_id=?"),
            Map.entry("refunds","SELECT r.amount,r.lessons,r.gift_lessons,r.reason,r.status,r.created_at FROM refund_request r JOIN lesson_account a ON a.id=r.account_id WHERE a.student_id=?"),
            Map.entry("notifications","SELECT event,title,message,created_at,read_at FROM parent_notification WHERE student_id=?"),
            Map.entry("prizeRedemptions","SELECT prize_name,points_cost,created_at FROM prize_redemption WHERE student_id=?"),
            Map.entry("consents","SELECT scope,policy_version,granted,created_at FROM privacy_consent WHERE student_id=?"),
            Map.entry("media","SELECT DISTINCT f.id,f.name,f.content_type,f.size FROM stored_file f JOIN file_link l ON l.file_id=f.id WHERE l.student_id=? AND l.kind<>'SUMMARY_HISTORY' AND (l.kind<>'SUMMARY_PHOTO' OR EXISTS(SELECT 1 FROM daily_summary d WHERE d.id=l.target_id AND d.student_id=l.student_id AND d.status='PUBLISHED'))")
        ).entrySet()) data.put(entry.getKey(),db.list(entry.getValue(),student));
        data.put("dailyParentFeedback",db.list("SELECT parent_id,date,satisfaction,feedback_text,created_at,updated_at FROM daily_parent_feedback WHERE student_id=?"+(sec.actor().role().equals("PARENT")?" AND parent_id="+sec.actor().id():""),student));
        data.put("generatedAt",sec.now());
        db.update("UPDATE privacy_request SET status='COMPLETED',completed_by=?,completed_at=?,export_json=? WHERE id=?",sec.actor().id(),sec.now(),encode(data),id);
        sec.audit("PRIVACY_EXPORT","request="+id+", student="+student); return data;
    }
    List<String> erasureBlocks(long student) {
        List<String> blocked=new ArrayList<>();
        if(db.count("SELECT COUNT(*) FROM attendance WHERE student_id=? AND status='PRESENT'",student)>0) blocked.add("尚有在校记录，请先核对离校");
        if(db.count("SELECT COUNT(*) FROM lesson_account WHERE student_id=? AND balance<>0",student)>0) blocked.add("课时余额未结清");
        if(db.count("SELECT COUNT(*) FROM settlement WHERE student_id=? AND status='PENDING'",student)>0) blocked.add("尚有待结算课时");
        if(db.count("SELECT COUNT(*) FROM renewal r JOIN lesson_account a ON a.id=r.account_id WHERE a.student_id=? AND r.status='PENDING'",student)>0) blocked.add("尚有待确认续费");
        if(db.count("SELECT COUNT(*) FROM refund_request r JOIN lesson_account a ON a.id=r.account_id WHERE a.student_id=? AND r.status IN ('REQUESTED','APPROVED')",student)>0) blocked.add("退款尚未处理完成");
        if(db.count("SELECT COUNT(*) FROM follow_up WHERE student_id=? AND kind='ISSUE' AND status IN ('OPEN','IN_PROGRESS')",student)>0) blocked.add("尚有未完结关注事项");
        if(db.count("SELECT COUNT(*) FROM pickup_alert WHERE student_id=? AND status IN ('OPEN','HOLD')",student)>0) blocked.add("尚有未处理接送异常");
        if(db.count("SELECT COUNT(*) FROM leave_request WHERE student_id=? AND status IN ('REQUESTED','APPROVED') AND leave_date>=?",student,sec.today())>0) blocked.add("尚有今日或未来请假记录");
        if(db.count("SELECT COUNT(*) FROM pickup_authorization WHERE student_id=? AND status='ACTIVE' AND expires_at>?",student,sec.now())>0) blocked.add("尚有有效接送授权");
        if(db.count("SELECT COUNT(*) FROM pickup_verification WHERE student_id=? AND used_at IS NULL AND expires_at>?",student,sec.now())>0) blocked.add("尚有有效接送核验");
        return blocked;
    }
    Map<String,Object> checkErasure(long id) {
        sec.admin(); var row=visible(id); return Map.of("request",row,"blocks",erasureBlocks(Db.id(row,"studentId")));
    }
    @Transactional public Map<String,Object> erase(long id,Map<String,Object> b) {
        sec.admin(); var row=visible(id); long student=Db.id(row,"studentId");
        db.one("SELECT id FROM student WHERE id=? FOR UPDATE",student); db.one("SELECT id FROM privacy_request WHERE id=? FOR UPDATE",id); row=visible(id);
        if(row.get("type").equals("ERASURE")&&row.get("status").equals("COMPLETED")) return visible(id);
        if(!row.get("type").equals("ERASURE")||!row.get("status").equals("APPROVED")) throw new ApiException(409,"请先审核资料脱敏申请");
        if(!Boolean.TRUE.equals(b.get("confirmed"))) throw ApiException.bad("请核对保护条件并明确确认执行脱敏");
        Db.required(b,"note",1000); var blocks=erasureBlocks(student); if(!blocks.isEmpty()) throw new ApiException(409,String.join("；",blocks));
        for(var audit:db.list("SELECT id,detail FROM audit_log WHERE action='HISTORICAL_CHECK_OUT' AND detail LIKE ?","student="+student+", date=%")) {
            String detail=audit.get("detail").toString(); int reason=detail.indexOf(", reason=");
            if(reason>=0) db.update("UPDATE audit_log SET detail=? WHERE id=?",detail.substring(0,reason)+", reason=已随资料脱敏",audit.get("id"));
        }
        for(var ledger:db.list("SELECT l.id FROM lesson_ledger l JOIN lesson_account a ON a.id=l.account_id WHERE a.student_id=?",student)) {
            String prefix="ledger="+ledger.get("id")+", reason=";
            db.update("UPDATE audit_log SET detail=? WHERE action='LEDGER_REVERSE' AND detail LIKE ?",prefix+"已随资料脱敏",prefix+"%");
        }
        var files=db.list("SELECT DISTINCT file_id FROM file_link WHERE student_id=?",student);
        db.update("DELETE FROM file_link WHERE student_id=?",student);
        for(var file:files) if(db.count("SELECT COUNT(*) FROM file_link WHERE file_id=?",file.get("fileId"))==0) {
            db.update("UPDATE stored_file SET privacy_blocked=TRUE,name='已脱敏附件' WHERE id=?",file.get("fileId"));
            db.update("DELETE FROM file_access_ticket WHERE file_id=?",file.get("fileId"));
        }
        db.update("UPDATE student SET name=?,avatar_file_id=NULL,gender='OTHER',grade='已脱敏',parent_name='已脱敏',parent_phone='已脱敏',privacy_erased_at=? WHERE id=?","已脱敏学生-"+student,sec.now(),student);
        db.update("UPDATE homework SET comment='' WHERE student_id=?",student);
        for(var task:db.list("SELECT h.id homework_id,t.id task_id,t.date,t.subject,t.type FROM homework h JOIN homework_task t ON t.id=h.task_id WHERE h.student_id=?",student)) {
            if(db.count("SELECT COUNT(*) FROM homework WHERE task_id=? AND student_id<>?",task.get("taskId"),student)>0) {
                long taskId=db.insert("INSERT INTO homework_task(date,subject,type,title,description) VALUES(?,?,?,'已脱敏作业','')",task.get("date"),task.get("subject"),task.get("type"));
                db.update("UPDATE homework SET task_id=? WHERE id=?",taskId,task.get("homeworkId"));
            } else db.update("UPDATE homework_task SET title='已脱敏作业',description='' WHERE id=?",task.get("taskId"));
        }
        db.update("UPDATE correction SET text='' WHERE mistake_id IN (SELECT id FROM mistake WHERE student_id=?)",student);
        db.update("UPDATE mistake SET title='已脱敏学习记录',question_text='',knowledge_point='',error_reason='',correction_text='',teacher_comment='' WHERE student_id=?",student);
        db.update("UPDATE question_scan_item SET display_number='已脱敏',question_text='',recognition_text='',group_name='',warnings_json='[]' WHERE scan_id IN (SELECT id FROM question_scan WHERE student_id=?)",student);
        db.update("UPDATE question_scan SET status='FAILED',error_message='资料已脱敏',session_hash='' WHERE student_id=?",student);
        db.update("DELETE FROM parent_notification WHERE student_id=?",student);
        db.update("UPDATE mistake_history SET comment='' WHERE mistake_id IN (SELECT id FROM mistake WHERE student_id=?)",student);
        db.update("UPDATE growth_event SET title='已脱敏成长记录',detail='' WHERE student_id=?",student);
        db.update("UPDATE fitness SET project=CONCAT('已脱敏体能记录-',id) WHERE student_id=?",student);
        db.update("UPDATE fitness_pause SET reason='',measures='' WHERE student_id=?",student);
        db.update("UPDATE follow_up_log SET text='' WHERE follow_up_id IN (SELECT id FROM follow_up WHERE student_id=?)",student);
        db.update("UPDATE follow_up SET title='已脱敏关注记录',reason='',measures='',parent_action='' WHERE student_id=?",student);
        db.update("UPDATE summary_review_history SET comment='',content='',teacher_comment='' WHERE summary_id IN (SELECT id FROM daily_summary WHERE student_id=?)",student);
        db.update("UPDATE daily_summary SET content='',teacher_comment='',published_content='',published_comment='',feedback_text='',review_comment='',review_content='',review_teacher_comment='' WHERE student_id=?",student);
        db.update("UPDATE daily_parent_feedback SET feedback_text='' WHERE student_id=?",student);
        db.update("UPDATE point_ledger SET reason='已脱敏积分记录' WHERE student_id=?",student);
        db.update("UPDATE lesson_ledger SET reason='已脱敏课时记录' WHERE account_id IN (SELECT id FROM lesson_account WHERE student_id=?)",student);
        db.update("UPDATE settlement SET reason='已脱敏结算记录' WHERE student_id=?",student);
        db.update("UPDATE renewal SET note='' WHERE account_id IN (SELECT id FROM lesson_account WHERE student_id=?)",student);
        db.update("UPDATE refund_log SET text='' WHERE refund_id IN (SELECT r.id FROM refund_request r JOIN lesson_account a ON a.id=r.account_id WHERE a.student_id=?)",student);
        db.update("UPDATE refund_request SET reason='已脱敏退款记录',review_note='',confirmation_note='',payment_reference='' WHERE account_id IN (SELECT id FROM lesson_account WHERE student_id=?)",student);
        db.update("UPDATE leave_request SET reason='',review_comment='' WHERE student_id=?",student);
        db.update("UPDATE pickup_contact SET name='已脱敏',relationship='已脱敏',phone='已脱敏',avatar_file_id=NULL,is_default=FALSE,face_status='NOT_CONFIGURED',deleted_at=COALESCE(deleted_at,?),updated_at=?,version=version+1,identity_version=identity_version+1 WHERE student_id=?",sec.now(),sec.now(),student);
        db.update("UPDATE pickup_authorization SET note='',face_status='NOT_CONFIGURED',pickup_name='已脱敏',relationship='已脱敏',phone='已脱敏',code_hash='',status=CASE WHEN status='ACTIVE' THEN 'REVOKED' ELSE status END WHERE student_id=?",student);
        db.update("UPDATE pickup_alert_log SET note='',actor_name=CASE WHEN actor_role='PARENT' THEN '监护人' ELSE actor_name END WHERE alert_id IN (SELECT id FROM pickup_alert WHERE student_id=?)",student);
        db.update("UPDATE pickup_alert SET pickup_name='已脱敏',phone_last4='',reason='已脱敏接送记录' WHERE student_id=?",student);
        db.update("UPDATE safety_log SET detail='',actor_name=CASE WHEN actor_role='PARENT' THEN '监护人' ELSE actor_name END WHERE student_id=?",student);
        db.update("DELETE FROM enrollment WHERE student_id=?",student); db.update("DELETE FROM parent_student WHERE student_id=?",student);
        db.update("UPDATE privacy_request SET reason='原申请说明已随资料脱敏',review_note='',export_json=NULL,status=CASE WHEN id=? THEN 'COMPLETED' ELSE 'REJECTED' END,completed_by=?,completed_at=? WHERE student_id=?",id,sec.actor().id(),sec.now(),student);
        sec.auditCampus(sec.studentCampus(student)); sec.audit("PRIVACY_ERASURE","request="+id+", student="+student); return visible(id);
    }
    String encode(Object value) { try { return json.writeValueAsString(value); } catch(Exception e) { throw new IllegalStateException("无法生成资料副本",e); } }
    Map<String,Object> parse(String value) { try { return json.readValue(value,new com.fasterxml.jackson.core.type.TypeReference<>(){}); } catch(Exception e) { throw new IllegalStateException("资料副本无法读取",e); } }
}
