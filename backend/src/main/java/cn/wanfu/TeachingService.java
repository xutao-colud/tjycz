package cn.wanfu;

import java.util.*;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TeachingService {
    final Db db; final Security sec; final FinanceService finance; final FileService files;final LearningService learn;final GrowthService growth;
    public TeachingService(Db db,Security sec,FinanceService finance,FileService files,LearningService learn,GrowthService growth){this.db=db;this.sec=sec;this.finance=finance;this.files=files;this.learn=learn;this.growth=growth;}
    void image(long id,long campus){
        if(!files.canRead(id))throw ApiException.forbidden();var image=db.one("SELECT * FROM stored_file WHERE id=?",id);
        if(Db.id(image,"campusId")!=campus)throw ApiException.forbidden();if(!image.get("contentType").toString().startsWith("image/"))throw ApiException.bad("请上传照片文件");
    }
    @Transactional public Map<String,Object> photoAttendance(Map<String,Object> body){
        sec.staff();long student=Db.number(body,"studentId"),photo=Db.number(body,"photoFileId");sec.student(student);String action=Db.required(body,"action",20);Db.choice(action,"CHECK_IN","CHECK_OUT");
        if(body.containsKey("date")&&!sec.date(Db.text(body,"date")).equals(sec.today()))throw ApiException.bad("拍照签到仅登记当天，历史补录请使用人工考勤");
        finance.lockStudent(student);image(photo,sec.studentCampus(student));
        boolean teacherPickup=Boolean.TRUE.equals(body.get("teacherPickup"));
        boolean checkout=action.equals("CHECK_OUT");
        String kind=checkout?"ATTENDANCE_OUT":"ATTENDANCE_IN";
        if(teacherPickup) {
            var file=db.one("SELECT owner_id FROM stored_file WHERE id=? FOR UPDATE",photo);
            if(Db.id(file,"ownerId")!=sec.actor().id()) throw ApiException.forbidden();
            if(db.count("SELECT COUNT(*) FROM file_link WHERE file_id=?",photo)>0&&db.count(
                "SELECT COUNT(*) FROM file_link l JOIN attendance a ON a.id=l.target_id AND a.student_id=l.student_id WHERE l.file_id=? AND l.kind=? AND l.student_id=? AND a.date=?",
                photo,kind,student,sec.today())==0) throw ApiException.bad("这张照片已用于其他记录，请重新拍摄");
        } else files.link(List.of(photo),"ATTENDANCE_CAPTURE",photo,student);
        var result=finance.recordAttendance(List.of(student),sec.today(),checkout,"",teacherPickup?photo:null);
        @SuppressWarnings("unchecked") var outcome=((List<Map<String,Object>>)result.get("results")).get(0);
        if(!Boolean.TRUE.equals(outcome.get("success")))throw ApiException.bad(outcome.get("message").toString());
        var attendance=db.one("SELECT * FROM attendance WHERE student_id=? AND date=?",student,sec.today());long id=Db.id(attendance,"id");
        if(!teacherPickup) {
            if(db.count("SELECT COUNT(*) FROM file_link WHERE kind=? AND target_id=?",kind,id)==0)files.link(List.of(photo),kind,id,student);
            db.update("UPDATE attendance SET source='PHOTO' WHERE id=?",id);
        } else if(db.count("SELECT COUNT(*) FROM file_link l JOIN growth_event e ON e.id=l.target_id WHERE l.kind='GROWTH' AND l.file_id=? AND e.source_type='ATTENDANCE' AND e.source_id=? AND e.action=?",photo,id,action)>0)
            files.link(List.of(photo),kind,id,student);
        sec.audit("PHOTO_"+action,"student="+student+", attendance="+id+(teacherPickup?", mode=TEACHER_PICKUP":""));
        return Map.of("attendanceId",id,"studentId",student,"action",action,"message",outcome.get("message"),"notificationCount",db.count("SELECT COUNT(*) FROM parent_notification WHERE attendance_id=? AND event=?",id,action),"wechatStatus","NOT_CONFIGURED","photos",files.linked(kind,id));
    }
    @Transactional public Map<String,Object> grant(Map<String,Object> body){
        sec.staff();long sid=Db.number(body,"studentId"),course=Db.number(body,"courseId");sec.student(sid);finance.lockStudent(sid);long campus=sec.course(course);
        if(campus!=sec.studentCampus(sid))throw ApiException.bad("学生与课程必须属于同一校区");db.one("SELECT id FROM course WHERE id=? AND enabled=TRUE",course);
        String reason=Db.required(body,"reason",800),request=Db.required(body,"requestId",100),key="lesson-grant:"+sec.actor().id()+":"+request;BigDecimal units=Db.decimal(body,"units");
        if(units.signum()<=0||units.compareTo(new BigDecimal("1000"))>0)throw ApiException.bad("增加课时须大于0且不超过1000");
        var old=db.optional(finance.ledgerQuery()+" WHERE l.event_key=?",key);
        if(old!=null){var account=db.one("SELECT course_id FROM lesson_account WHERE id=?",old.get("accountId"));if(Db.id(old,"studentId")!=sid||Db.id(account,"courseId")!=course||finance.dec(old,"amount").compareTo(units)!=0||!old.get("reason").equals(reason))throw new ApiException(409,"requestId已用于不同的课时增加");return old;}
        finance.enrollment(sid,course);var account=db.one("SELECT id FROM lesson_account WHERE student_id=? AND course_id=?",sid,course);
        long ledger=finance.ledger(finance.account(Db.id(account,"id")),"TEACHER_GRANT",BigDecimal.ZERO,units,BigDecimal.ZERO,reason,key,null);sec.audit("LESSON_GRANT","student="+sid+", course="+course+", units="+units+", reason="+reason);return db.one(finance.ledgerQuery()+" WHERE l.id=?",ledger);
    }
    @Transactional public Map<String,Object> recognize(Map<String,Object> body){
        sec.staff();long sid=Db.number(body,"studentId"),photo=Db.number(body,"photoFileId");sec.student(sid);image(photo,sec.studentCampus(sid));files.link(List.of(photo),"TEACHING_CAPTURE",photo,sid);
        sec.audit("CAPTURE_REVIEW","student="+sid+", photo="+photo);return Map.of("status","NOT_CONFIGURED","text","","needsConfirmation",true,"message","识别服务尚未配置，请根据照片填写或校对题目后保存。","photo",files.access(photo));
    }
    @Transactional public Map<String,Object> saveCapture(Map<String,Object> body){
        sec.staff();long sid=Db.number(body,"studentId"),photo=Db.number(body,"photoFileId");sec.student(sid);finance.lockStudent(sid);String request=Db.required(body,"requestId",100),kind=Db.required(body,"kind",20),subject=Db.required(body,"subject",16),question=Db.text(body,"questionText");Db.choice(kind,"MISTAKE","HOMEWORK");Db.choice(subject,"语文","数学","英语");
        if(question.length()>(kind.equals("MISTAKE")?10000:2000))throw ApiException.bad("题目正文超出长度限制");String hash=growth.hash(new TreeMap<>(body));var old=db.optional("SELECT * FROM teaching_capture WHERE actor_id=? AND request_id=?",sec.actor().id(),request);
        if(old!=null){if(!old.get("requestHash").equals(hash))throw new ApiException(409,"requestId已用于不同拍照记录");return old.get("kind").equals("MISTAKE")?learn.mistake(Db.id(old,"targetId")):learn.homework(Db.id(old,"targetId"));}
        image(photo,sec.studentCampus(sid));Map<String,Object> record;
        if(kind.equals("MISTAKE"))record=learn.createMistake(Map.of("studentId",sid,"subject",subject,"questionText",question,"attachmentIds",List.of(photo)));
        else{
            long id;
            if(body.get("homeworkId")!=null){id=Db.number(body,"homeworkId");if(Db.id(learn.homework(id),"studentId")!=sid)throw ApiException.bad("作业与孩子不匹配");}
            else{var task=learn.task(Map.of("studentIds",List.of(sid),"date",sec.today().toString(),"subject",subject,"type","WRITTEN","title",question.isBlank()?"拍照作业":question.substring(0,Math.min(100,question.length())),"description",question,"attachmentIds",List.of(photo)));id=Db.id(db.one("SELECT id FROM homework WHERE task_id=? AND student_id=?",task.get("taskId"),sid),"id");}
            record=learn.homeworkStatus(id,Map.of("status","PENDING_REVIEW","submissionText",question,"attachmentIds",List.of(photo)));
        }
        db.update("INSERT INTO teaching_capture(student_id,actor_id,kind,target_id,request_id,request_hash) VALUES(?,?,?,?,?,?)",sid,sec.actor().id(),kind,record.get("id"),request,hash);sec.audit("CAPTURE_SAVE","student="+sid+", kind="+kind+", target="+record.get("id"));return record;
    }
}
