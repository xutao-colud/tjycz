package cn.wanfu;

import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FamilyService {
    final Db db; final Security sec; final FinanceService finance; final QueryService queries; final FileService files;
    public FamilyService(Db db,Security sec,FinanceService finance,QueryService queries,FileService files) { this.db=db;this.sec=sec;this.finance=finance;this.queries=queries;this.files=files; }
    void version(Map<String,Object> row,Map<String,Object> body) {
        if(!body.containsKey("expectedVersion")) throw ApiException.bad("请刷新后携带 expectedVersion 重试");
        if(Db.integer(body,"expectedVersion",-1)!=(row==null?0:Db.integer(row,"version",0))) throw new ApiException(409,"记录已更新，请刷新后重试");
    }
    void fields(Map<String,Object> body,String... allowed) { if(!Set.of(allowed).containsAll(body.keySet())) throw ApiException.bad("请求包含不支持的字段"); }
    Map<String,Object> image(long id,long campus) {
        var row=db.one("SELECT * FROM stored_file WHERE id=?",id);
        if(Db.id(row,"campusId")!=campus||!files.canRead(id)) throw ApiException.forbidden();
        if(!row.get("contentType").toString().startsWith("image/")) throw ApiException.bad("请上传有效的图片文件");
        return row;
    }
    Map<String,Object> settings(long campus) {
        sec.campus(campus); var saved=db.optional("SELECT * FROM campus_family_service WHERE campus_id=?",campus);
        Map<String,Object> out=new LinkedHashMap<>();out.put("campusId",campus);out.put("campusName",db.one("SELECT name FROM campus WHERE id=?",campus).get("name"));
        out.put("mealPrice",saved==null?null:saved.get("mealPrice"));out.put("version",saved==null?0:saved.get("version"));
        Object qr=saved==null?null:saved.get("paymentQrFileId");out.put("paymentQr",qr==null?null:files.access(((Number)qr).longValue()));
        return out;
    }
    @Transactional public Map<String,Object> configure(long campus,Map<String,Object> body) {
        sec.admin();sec.campus(campus);fields(body,"mealPrice","paymentQrFileId","expectedVersion");
        db.one("SELECT id FROM campus WHERE id=? FOR UPDATE",campus);var saved=db.optional("SELECT * FROM campus_family_service WHERE campus_id=?",campus);version(saved,body);
        Long price=body.get("mealPrice")==null?null:Db.number(body,"mealPrice");
        if(price!=null&&(price<0||price>100000000)) throw ApiException.bad("餐费须为有效的非负金额（单位：分）");
        Long qr=body.get("paymentQrFileId")==null?null:Db.number(body,"paymentQrFileId");
        if(qr!=null) { image(qr,campus);if(db.count("SELECT COUNT(*) FROM file_link WHERE file_id=?",qr)>0) throw ApiException.bad("收款二维码须单独上传，不可使用学生附件"); }
        if(saved==null) db.update("INSERT INTO campus_family_service(campus_id,meal_price,payment_qr_file_id,updated_by,updated_at) VALUES(?,?,?,?,?)",campus,price,qr,sec.actor().id(),sec.now());
        else db.update("UPDATE campus_family_service SET meal_price=?,payment_qr_file_id=?,version=version+1,updated_by=?,updated_at=? WHERE campus_id=?",price,qr,sec.actor().id(),sec.now(),campus);
        sec.auditCampus(campus);sec.audit("FAMILY_SERVICE_CONFIGURE","campus="+campus);return settings(campus);
    }
    List<Map<String,Object>> intents(Long student) {
        if(student!=null)sec.student(student);List<Object> args=new ArrayList<>();
        String sql="SELECT r.id,r.account_id,a.student_id,s.name student_name,cl.campus_id,cp.name campus_name,c.name course_name,r.parent_id,u.name parent_name,r.intention,r.version,r.created_at,r.updated_at FROM renewal_intent r JOIN lesson_account a ON a.id=r.account_id JOIN student s ON s.id=a.student_id JOIN school_class cl ON cl.id=s.class_id JOIN campus cp ON cp.id=cl.campus_id JOIN course c ON c.id=a.course_id JOIN app_user u ON u.id=r.parent_id JOIN parent_student ps ON ps.user_id=r.parent_id AND ps.student_id=s.id WHERE s.privacy_erased_at IS NULL AND "+sec.scope("s");
        if(student!=null){sql+=" AND s.id=?";args.add(student);}if(sec.actor().role().equals("PARENT")){sql+=" AND r.parent_id=?";args.add(sec.actor().id());}
        return db.list(sql+" ORDER BY r.updated_at DESC,r.id DESC",args.toArray());
    }
    @Transactional public Map<String,Object> intention(long account,Map<String,Object> body) {
        sec.parent();fields(body,"intention","expectedVersion");var a=db.one("SELECT student_id FROM lesson_account WHERE id=?",account);long sid=Db.id(a,"studentId");sec.student(sid);finance.lockStudent(sid);
        String intention=Db.required(body,"intention",20);Db.choice(intention,"INTERESTED","NOT_NOW");
        var saved=db.optional("SELECT * FROM renewal_intent WHERE account_id=? AND parent_id=?",account,sec.actor().id());
        if(saved!=null&&saved.get("intention").equals(intention))return intents(sid).stream().filter(r->Db.id(r,"accountId")==account).findFirst().orElseThrow();
        version(saved,body);
        if(saved==null)db.update("INSERT INTO renewal_intent(account_id,parent_id,intention,created_at,updated_at) VALUES(?,?,?,?,?)",account,sec.actor().id(),intention,sec.now(),sec.now());
        else db.update("UPDATE renewal_intent SET intention=?,version=version+1,updated_at=? WHERE id=?",intention,sec.now(),saved.get("id"));
        sec.audit("RENEWAL_INTENT","account="+account+", intention="+intention);return intents(sid).stream().filter(r->Db.id(r,"accountId")==account).findFirst().orElseThrow();
    }
    List<Map<String,Object>> meals(LocalDate date,Long student) {
        if(student!=null)sec.student(student);String sql="SELECT m.id,m.student_id,s.name student_name,cl.name class_name,cl.campus_id,cp.name campus_name,m.meal_date date,m.ordered,m.amount,m.ordered_by,u.name parent_name,m.version,m.created_at,m.updated_at FROM meal_order m JOIN student s ON s.id=m.student_id JOIN school_class cl ON cl.id=s.class_id JOIN campus cp ON cp.id=cl.campus_id JOIN app_user u ON u.id=m.ordered_by WHERE m.meal_date=? AND s.privacy_erased_at IS NULL AND "+sec.scope("s");
        return student==null?db.list(sql+" ORDER BY m.updated_at DESC,m.id DESC",date):db.list(sql+" AND s.id=? ORDER BY m.id",date,student);
    }
    @Transactional public Map<String,Object> meal(long student,Map<String,Object> body) {
        sec.parent();sec.student(student);fields(body,"ordered","expectedVersion");if(!(body.get("ordered") instanceof Boolean))throw ApiException.bad("请选择今天是否订餐");
        finance.lockStudent(student);boolean ordered=Boolean.TRUE.equals(body.get("ordered"));var date=sec.today();var saved=db.optional("SELECT * FROM meal_order WHERE student_id=? AND meal_date=?",student,date);
        if(saved!=null&&Boolean.valueOf(ordered).equals(saved.get("ordered")))return meals(date,student).get(0);
        version(saved,body);var config=db.optional("SELECT meal_price FROM campus_family_service WHERE campus_id=?",sec.studentCampus(student));
        if(ordered&&(config==null||config.get("mealPrice")==null))throw ApiException.bad("校区尚未设置餐费，请联系老师配置后订餐");
        long amount=ordered?Db.id(config,"mealPrice"):saved==null?0:Db.id(saved,"amount");
        if(saved==null)db.update("INSERT INTO meal_order(student_id,meal_date,ordered,amount,ordered_by,created_at,updated_at) VALUES(?,?,?,?,?,?,?)",student,date,ordered,amount,sec.actor().id(),sec.now(),sec.now());
        else db.update("UPDATE meal_order SET ordered=?,amount=?,ordered_by=?,version=version+1,updated_at=? WHERE id=?",ordered,amount,sec.actor().id(),sec.now(),saved.get("id"));
        sec.audit("MEAL_ORDER","student="+student+", date="+date+", ordered="+ordered);return meals(date,student).get(0);
    }
    Map<String,Object> page(long student) {
        sec.student(student);var accounts=queries.accounts(student);var intentions=intents(student);
        for(var account:accounts) {var own=intentions.stream().filter(i->Db.id(i,"accountId")==Db.id(account,"id")&&Db.id(i,"parentId")==sec.actor().id()).findFirst().orElse(null);account.put("intention",own==null?null:own.get("intention"));account.put("intentVersion",own==null?0:own.get("version"));account.put("lowBalance",finance.dec(account,"availableBalance").compareTo(finance.dec(account,"warningThreshold"))<=0);}
        Map<String,Object> out=new LinkedHashMap<>();out.put("student",queries.student(student));out.put("date",sec.today());out.put("accounts",accounts);out.put("settings",settings(sec.studentCampus(student)));var meals=meals(sec.today(),student);out.put("meal",meals.isEmpty()?null:meals.get(0));return out;
    }
    @Transactional public Map<String,Object> avatar(long student,Map<String,Object> body) {
        sec.student(student);fields(body,"avatarFileId");if(!body.containsKey("avatarFileId"))throw ApiException.bad("请选择孩子头像");finance.lockStudent(student);
        Long file=body.get("avatarFileId")==null?null:Db.number(body,"avatarFileId");
        if(file!=null){image(file,sec.studentCampus(student));files.link(List.of(file),"STUDENT_AVATAR",student,student);}
        db.update("UPDATE student SET avatar_file_id=? WHERE id=?",file,student);sec.audit("STUDENT_AVATAR","student="+student);return queries.student(student);
    }
}
