package cn.wanfu;

import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StudentNotificationService {
    final Db db; final Security sec; final FileService files;
    public StudentNotificationService(Db db,Security sec,FileService files){this.db=db;this.sec=sec;this.files=files;}
    void attendance(long student,long attendance,boolean leaving,LocalDate date){
        var child=db.one("SELECT name FROM student WHERE id=?",student);String event=leaving?"CHECK_OUT":"CHECK_IN",title=child.get("name")+(leaving?"已离校":"已到校");
        String message=date+" · "+child.get("name")+(leaving?"已由老师确认离校。":"已由老师确认到校。")+(date.equals(sec.today())?"":" 此记录为历史补录。");
        for(var parent:db.list("SELECT user_id FROM parent_student WHERE student_id=?",student))
            if(db.count("SELECT COUNT(*) FROM parent_notification WHERE parent_id=? AND attendance_id=? AND event=?",parent.get("userId"),attendance,event)==0)
                db.update("INSERT INTO parent_notification(parent_id,student_id,attendance_id,event,title,message,created_at) VALUES(?,?,?,?,?,?,?)",parent.get("userId"),student,attendance,event,title,message,sec.now());
    }
    List<Map<String,Object>> list(Long student){
        if(student!=null)sec.student(student);List<Object> args=new ArrayList<>();String sql="SELECT n.*,s.name student_name,u.name parent_name,cl.campus_id FROM parent_notification n JOIN student s ON s.id=n.student_id JOIN school_class cl ON cl.id=s.class_id JOIN app_user u ON u.id=n.parent_id JOIN parent_student ps ON ps.user_id=n.parent_id AND ps.student_id=n.student_id WHERE s.privacy_erased_at IS NULL AND "+sec.scope("s");
        if(sec.actor().role().equals("PARENT")){sql+=" AND n.parent_id=?";args.add(sec.actor().id());}if(student!=null){sql+=" AND s.id=?";args.add(student);}
        return db.list(sql+" ORDER BY n.id DESC LIMIT 500",args.toArray()).stream().map(n->{n.put("attachments",files.linked(n.get("event").equals("CHECK_IN")?"ATTENDANCE_IN":"ATTENDANCE_OUT",Db.id(n,"attendanceId")));return n;}).toList();
    }
    @Transactional public Map<String,Object> read(long id){
        sec.parent();var n=db.one("SELECT * FROM parent_notification WHERE id=?",id);if(Db.id(n,"parentId")!=sec.actor().id())throw ApiException.forbidden();sec.student(Db.id(n,"studentId"));
        db.update("UPDATE parent_notification SET read_at=COALESCE(read_at,?) WHERE id=?",sec.now(),id);return Map.of("success",true);
    }
}
