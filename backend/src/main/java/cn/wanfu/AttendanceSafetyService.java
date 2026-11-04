package cn.wanfu;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AttendanceSafetyService {
    final Db db; final Security sec; final FileService files; final PickupAlertService alerts;
    public AttendanceSafetyService(Db db,Security sec,FileService files,PickupAlertService alerts) { this.db=db; this.sec=sec; this.files=files; this.alerts=alerts; }
    boolean admin() { return List.of("SUPER_ADMIN","ADMIN").contains(sec.actor().role()); }
    void lock(long sid) { sec.student(sid); db.one("SELECT id FROM student WHERE id=? FOR UPDATE",sid); sec.student(sid); }
    String request(Map<String,Object> body,String kind) { return kind+":"+sec.actor().id()+":"+Db.required(body,"requestId",80); }
    void version(Map<String,Object> row,Map<String,Object> body) {
        if(!body.containsKey("expectedVersion")||Db.integer(body,"expectedVersion",-1)!=Db.integer(row,"version",0)) throw new ApiException(409,"记录已变更，请刷新后重试");
    }
    void log(String type,long id,long sid,LocalDate date,String action,String detail,int revision,boolean timeline) {
        var actor=sec.actor();
        db.update("INSERT INTO safety_log(student_id,entity_type,entity_id,action,actor_id,actor_name,actor_role,detail,created_at) VALUES(?,?,?,?,?,?,?,?,?)",sid,type,id,action,actor.id(),actor.name(),actor.role(),detail,sec.now());
        sec.audit(type+"_"+action,"student="+sid+", id="+id);
        if(timeline) db.update("INSERT INTO growth_event(student_id,business_date,event_type,source_type,source_id,action,title,detail,status,occurred_at,recorded_at,actor_id,actor_name,event_key) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",sid,date,"SAFETY",type,id,action,type.equals("LEAVE")?"请假安排":"安全接送",detail,action,sec.now(),sec.now(),actor.id(),actor.name(),"safety:"+type+":"+id+":"+action+":"+revision);
    }
    Map<String,Object> leave(long id) {
        var row=db.one("SELECT l.*,s.name student_name,cl.campus_id,cp.name campus_name,u.name created_by_name,r.name reviewed_by_name FROM leave_request l JOIN student s ON s.id=l.student_id JOIN school_class cl ON cl.id=s.class_id JOIN campus cp ON cp.id=cl.campus_id JOIN app_user u ON u.id=l.created_by LEFT JOIN app_user r ON r.id=l.reviewed_by WHERE l.id=?",id);
        sec.student(Db.id(row,"studentId"));
        row.remove("requestHash"); row.remove("requestId");
        row.put("canReview",admin()&&row.get("status").equals("REQUESTED")&&Db.id(row,"createdBy")!=sec.actor().id());
        row.put("canCancel",List.of("REQUESTED","APPROVED").contains(row.get("status"))&&!((LocalDate)row.get("leaveDate")).isBefore(sec.today())&&(admin()||sec.actor().role().equals("PARENT")||Db.id(row,"createdBy")==sec.actor().id()));
        row.put("logs",logs("LEAVE",id)); return row;
    }
    List<Map<String,Object>> logs(String type,long id) { return db.list("SELECT id,action,actor_name,actor_role,detail,created_at FROM safety_log WHERE entity_type=? AND entity_id=? ORDER BY id",type,id); }
    List<Map<String,Object>> leaves(LocalDate date,Long sid) {
        if(sid!=null) sec.student(sid);
        return db.list("SELECT l.id FROM leave_request l JOIN student s ON s.id=l.student_id WHERE l.leave_date=? AND "+(sec.scope("s")+" AND s.privacy_erased_at IS NULL")+(sid==null?"":" AND s.id="+sid)+" ORDER BY l.id DESC",date).stream().map(r->leave(Db.id(r,"id"))).toList();
    }
    @Transactional public Map<String,Object> createLeave(Map<String,Object> body) {
        long sid=Db.number(body,"studentId"); lock(sid); LocalDate date=sec.date(Db.required(body,"date",10));
        String reason=Db.required(body,"reason",1000),key=request(body,"leave"),hash=sec.hash(sid+"\n"+date+"\n"+reason);
        var old=db.optional("SELECT id,request_hash FROM leave_request WHERE request_id=?",key);
        if(old!=null) { if(!hash.equals(old.get("requestHash"))) throw new ApiException(409,"requestId已用于其他请假内容"); return leave(Db.id(old,"id")); }
        if(date.isBefore(sec.today())) throw ApiException.bad("请假须在当天或未来日期提出，历史考勤请联系校长补录");
        if(db.count("SELECT COUNT(*) FROM attendance WHERE student_id=? AND date=?",sid,date)>0) throw ApiException.bad("已登记到校或离校，不能申请整日请假");
        if(db.count("SELECT COUNT(*) FROM leave_request WHERE student_id=? AND leave_date=? AND status IN ('REQUESTED','APPROVED')",sid,date)>0) throw new ApiException(409,"当天已有待审核或已批准的请假");
        long id=db.insert("INSERT INTO leave_request(student_id,leave_date,reason,status,created_by,created_at,updated_at,request_id,request_hash) VALUES(?,?,?,'REQUESTED',?,?,?,?,?)",sid,date,reason,sec.actor().id(),sec.now(),sec.now(),key,hash);
        log("LEAVE",id,sid,date,"REQUESTED",reason,0,true); return leave(id);
    }
    @Transactional public Map<String,Object> reviewLeave(long id,Map<String,Object> body) {
        sec.admin(); var initial=leave(id); long sid=Db.id(initial,"studentId"); lock(sid); var row=db.one("SELECT * FROM leave_request WHERE id=? FOR UPDATE",id);
        if(!(body.get("approved") instanceof Boolean)) throw ApiException.bad("请选择批准或驳回");
        String target=Boolean.TRUE.equals(body.get("approved"))?"APPROVED":"REJECTED",note=Db.required(body,"note",1000);
        if(row.get("status").equals(target)&&Objects.equals(row.get("reviewedBy"),sec.actor().id())&&row.get("reviewComment").equals(note)) return leave(id);
        version(row,body); if(!row.get("status").equals("REQUESTED")) throw ApiException.bad("仅待审核请假可处理");
        if(Db.id(row,"createdBy")==sec.actor().id()) throw ApiException.bad("请由另一位校长或老板审核，不能审核自己提出的请假");
        LocalDate date=(LocalDate)row.get("leaveDate");
        if(date.isBefore(sec.today())||db.count("SELECT COUNT(*) FROM attendance WHERE student_id=? AND date=?",sid,date)>0) throw ApiException.bad("日期已过或学生已经到校，请刷新核对");
        db.update("UPDATE leave_request SET status=?,reviewed_by=?,review_comment=?,updated_at=?,version=version+1 WHERE id=?",target,sec.actor().id(),note,sec.now(),id);
        log("LEAVE",id,sid,date,target,note,Db.integer(row,"version",0)+1,true); return leave(id);
    }
    @Transactional public Map<String,Object> cancelLeave(long id,Map<String,Object> body) {
        var initial=leave(id); long sid=Db.id(initial,"studentId"); lock(sid); var row=db.one("SELECT * FROM leave_request WHERE id=? FOR UPDATE",id);
        if(!admin()&&!sec.actor().role().equals("PARENT")&&Db.id(row,"createdBy")!=sec.actor().id()) throw ApiException.forbidden();
        if(row.get("status").equals("CANCELLED")) return leave(id); version(row,body); String note=Db.required(body,"note",1000);
        if(!List.of("REQUESTED","APPROVED").contains(row.get("status"))||((LocalDate)row.get("leaveDate")).isBefore(sec.today())) throw ApiException.bad("当前请假不可撤销");
        db.update("UPDATE leave_request SET status='CANCELLED',updated_at=?,version=version+1 WHERE id=?",sec.now(),id);
        log("LEAVE",id,sid,(LocalDate)row.get("leaveDate"),"CANCELLED",note,Db.integer(row,"version",0)+1,true); return leave(id);
    }
    void rejectIdentityFields(Object value) {
        if(value instanceof Map<?,?> map) for(var entry:map.entrySet()) {
            String field=entry.getKey().toString().toLowerCase(Locale.ROOT).replaceAll("[_ -]", "");
            if(field.contains("idcard")||field.contains("identitycard")||field.contains("identitynumber")||field.contains("identityno")||field.contains("nationalid")||field.equals("idno")||field.equals("idnumber")||field.contains("身份证")) throw ApiException.bad("不采集身份证信息，请移除身份证字段");
            rejectIdentityFields(entry.getValue());
        } else if(value instanceof List<?> list) list.forEach(this::rejectIdentityFields);
    }
    String phone(Map<String,Object> body) {
        String phone=Db.required(body,"phone",30).replaceAll("[ +()-]","");
        if(!phone.matches("[0-9]{6,20}")) throw ApiException.bad("接送人电话格式错误"); return phone;
    }
    Map<String,Object> rawContact(long id) { return db.one("SELECT * FROM pickup_contact WHERE id=?",id); }
    boolean guardianBound(long guardian,long sid) { return db.count("SELECT COUNT(*) FROM parent_student WHERE user_id=? AND student_id=?",guardian,sid)>0; }
    Map<String,Object> contact(long id) {
        var row=rawContact(id); sec.student(Db.id(row,"studentId"));
        row.remove("requestId"); row.remove("requestHash"); row.remove("identityVersion");
        String phone=row.get("phone").toString(); row.put("phoneLast4",phone.substring(Math.max(0,phone.length()-4))); row.put("phoneMasked","•••• "+row.get("phoneLast4"));
        if(!sec.actor().role().equals("PARENT")) row.remove("phone");
        row.put("canManage",sec.actor().role().equals("PARENT")&&row.get("deletedAt")==null);
        Map<String,Object> avatar=null;
        if(row.get("avatarFileId")!=null&&row.get("deletedAt")==null) for(var file:files.linked("PICKUP_CONTACT",id)) if(Db.id(file,"id")==Db.id(row,"avatarFileId")) avatar=file;
        row.put("avatar",avatar); if(avatar==null) row.put("avatarFileId",null);
        return row;
    }
    List<Map<String,Object>> contacts(long sid) {
        sec.student(sid);
        return db.list("SELECT id FROM pickup_contact WHERE student_id=? AND deleted_at IS NULL ORDER BY is_default DESC,id",sid).stream().map(row->contact(Db.id(row,"id"))).toList();
    }
    Long avatar(Map<String,Object> body) {
        if(body.get("avatarFileId")==null) return null;
        long id=Db.number(body,"avatarFileId");
        if(!files.canRead(id)) throw ApiException.forbidden();
        if(!db.one("SELECT content_type FROM stored_file WHERE id=?",id).get("contentType").toString().startsWith("image/")) throw ApiException.bad("接送人头像须为图片");
        return id;
    }
    void defaultContact(long sid,long id) {
        db.update("UPDATE pickup_contact SET is_default=FALSE,version=version+1,updated_at=? WHERE student_id=? AND id<>? AND is_default=TRUE AND deleted_at IS NULL",sec.now(),sid,id);
        db.update("UPDATE pickup_contact SET is_default=TRUE WHERE id=?",id);
    }
    @Transactional public Map<String,Object> createContact(Map<String,Object> body) {
        sec.parent(); rejectIdentityFields(body); long sid=Db.number(body,"studentId"); lock(sid);
        String name=Db.required(body,"name",80),relation=Db.required(body,"relationship",40),phone=phone(body),key=request(body,"pickup-contact");
        Long avatar=avatar(body); String hash=sec.hash(sid+"\n"+name+"\n"+relation+"\n"+phone+"\n"+avatar+"\n"+body.get("isDefault"));
        var old=db.optional("SELECT id,request_hash FROM pickup_contact WHERE request_id=?",key);
        if(old!=null) { if(!hash.equals(old.get("requestHash"))) throw new ApiException(409,"requestId已用于其他接送人内容"); return contact(Db.id(old,"id")); }
        if(body.containsKey("isDefault")&&!(body.get("isDefault") instanceof Boolean)) throw ApiException.bad("isDefault须为明确的布尔值");
        boolean isDefault=Boolean.TRUE.equals(body.get("isDefault"))||!body.containsKey("isDefault")&&db.count("SELECT COUNT(*) FROM pickup_contact WHERE student_id=? AND deleted_at IS NULL",sid)==0;
        long id=db.insert("INSERT INTO pickup_contact(student_id,guardian_id,name,relationship,phone,avatar_file_id,is_default,created_at,updated_at,request_id,request_hash) VALUES(?,?,?,?,?,?,?,?,?,?,?)",sid,sec.actor().id(),name,relation,phone,avatar,false,sec.now(),sec.now(),key,hash);
        if(avatar!=null) files.link(List.of(avatar),"PICKUP_CONTACT",id,sid);
        if(isDefault) defaultContact(sid,id);
        log("PICKUP_CONTACT",id,sid,sec.today(),"CREATED","监护人登记常用接送人",0,false); return contact(id);
    }
    void invalidateContact(long id,long sid) {
        for(var row:db.list("SELECT id,version FROM pickup_authorization WHERE contact_id=? AND status='ACTIVE'",id)) {
            db.update("UPDATE pickup_authorization SET status='REVOKED',version=version+1,updated_at=? WHERE id=?",sec.now(),row.get("id"));
            log("PICKUP",Db.id(row,"id"),sid,sec.today(),"REVOKED","常用接送人已修改或删除，须重新授权并核验",Db.integer(row,"version",0)+1,false);
        }
    }
    @Transactional public Map<String,Object> updateContact(long id,Map<String,Object> body) {
        sec.parent(); rejectIdentityFields(body); var initial=rawContact(id); long sid=Db.id(initial,"studentId"); lock(sid);
        var row=rawContact(id); version(row,body); if(row.get("deletedAt")!=null) throw ApiException.bad("接送人已删除");
        String name=Db.required(body,"name",80),relation=Db.required(body,"relationship",40),phone=phone(body);
        Long avatar=body.containsKey("avatarFileId")?avatar(body):row.get("avatarFileId")==null?null:Db.id(row,"avatarFileId");
        if(body.containsKey("isDefault")&&!(body.get("isDefault") instanceof Boolean)) throw ApiException.bad("isDefault须为明确的布尔值");
        boolean isDefault=body.containsKey("isDefault")?Boolean.TRUE.equals(body.get("isDefault")):Boolean.TRUE.equals(row.get("isDefault"));
        if(avatar!=null&&body.containsKey("avatarFileId")) files.link(List.of(avatar),"PICKUP_CONTACT",id,sid);
        db.update("UPDATE pickup_contact SET name=?,relationship=?,phone=?,avatar_file_id=?,is_default=?,face_status='NOT_CONFIGURED',identity_version=identity_version+1,version=version+1,updated_at=? WHERE id=?",name,relation,phone,avatar,isDefault,sec.now(),id);
        if(isDefault) defaultContact(sid,id); invalidateContact(id,sid);
        log("PICKUP_CONTACT",id,sid,sec.today(),"UPDATED","监护人修改常用接送人，相关授权已失效",Db.integer(row,"version",0)+1,false); return contact(id);
    }
    @Transactional public Map<String,Object> setDefaultContact(long id,Map<String,Object> body) {
        sec.parent(); rejectIdentityFields(body); var row=rawContact(id); long sid=Db.id(row,"studentId"); lock(sid); row=rawContact(id); version(row,body);
        if(row.get("deletedAt")!=null) throw ApiException.bad("接送人已删除"); defaultContact(sid,id);
        db.update("UPDATE pickup_contact SET version=version+1,updated_at=? WHERE id=?",sec.now(),id);
        log("PICKUP_CONTACT",id,sid,sec.today(),"DEFAULT","监护人设为默认接送人",Db.integer(row,"version",0)+1,false); return contact(id);
    }
    @Transactional public Map<String,Object> deleteContact(long id,Map<String,Object> body) {
        sec.parent(); rejectIdentityFields(body); var row=rawContact(id); long sid=Db.id(row,"studentId"); lock(sid); row=rawContact(id);
        if(row.get("deletedAt")!=null) return Map.of("success",true); version(row,body);
        db.update("UPDATE pickup_contact SET deleted_at=?,is_default=FALSE,identity_version=identity_version+1,version=version+1,updated_at=? WHERE id=?",sec.now(),sec.now(),id); invalidateContact(id,sid);
        if(Boolean.TRUE.equals(row.get("isDefault"))) { var next=db.optional("SELECT id FROM pickup_contact WHERE student_id=? AND deleted_at IS NULL ORDER BY id LIMIT 1",sid); if(next!=null) defaultContact(sid,Db.id(next,"id")); }
        log("PICKUP_CONTACT",id,sid,sec.today(),"DELETED","监护人删除常用接送人，相关授权已失效",Db.integer(row,"version",0)+1,false); return Map.of("success",true);
    }
    record Window(LocalDate startDate,LocalDate endDate,LocalTime startTime,LocalTime endTime) {
        LocalDateTime from() { return startDate.atTime(startTime); }
        LocalDateTime until() { return endDate.atTime(endTime); }
        boolean contains(LocalDateTime now) { return !now.toLocalDate().isBefore(startDate)&&!now.toLocalDate().isAfter(endDate)&&!now.toLocalTime().isBefore(startTime)&&now.toLocalTime().isBefore(endTime); }
        boolean overlaps(Window other) { return !startDate.isAfter(other.endDate)&&!other.startDate.isAfter(endDate)&&startTime.isBefore(other.endTime)&&other.startTime.isBefore(endTime); }
    }
    LocalTime time(Object value) { return value instanceof LocalTime t?t:LocalTime.parse(value.toString()); }
    Window window(Map<String,Object> row) {
        var from=(LocalDateTime)row.get("validFrom"); var until=(LocalDateTime)row.get("expiresAt");
        return new Window(row.get("startDate") instanceof LocalDate d?d:from.toLocalDate(),row.get("endDate") instanceof LocalDate d?d:until.toLocalDate(),row.get("startTime")==null?from.toLocalTime():time(row.get("startTime")),row.get("endTime")==null?until.toLocalTime():time(row.get("endTime")));
    }
    boolean contactValid(Map<String,Object> row) {
        if(row.get("contactId")==null) return true; var contact=db.optional("SELECT identity_version,deleted_at FROM pickup_contact WHERE id=? AND student_id=?",row.get("contactId"),row.get("studentId"));
        return contact!=null&&contact.get("deletedAt")==null&&Db.integer(contact,"identityVersion",-1)==Db.integer(row,"contactVersion",-2);
    }
    boolean activeNow(Map<String,Object> row) { return row.get("status").equals("ACTIVE")&&sec.now().isBefore((LocalDateTime)row.get("expiresAt"))&&!sec.now().isBefore((LocalDateTime)row.get("validFrom"))&&window(row).contains(sec.now())&&contactValid(row); }
    boolean usedToday(long id) { return db.count("SELECT COUNT(*) FROM pickup_use WHERE authorization_id=? AND business_date=?",id,sec.today())>0; }
    String pickupQuery() { return "SELECT p.*,s.name student_name,cl.campus_id,cp.name campus_name,u.name guardian_name FROM pickup_authorization p JOIN student s ON s.id=p.student_id JOIN school_class cl ON cl.id=s.class_id JOIN campus cp ON cp.id=cl.campus_id JOIN app_user u ON u.id=p.guardian_id"; }
    Map<String,Object> pickup(long id) {
        var row=db.one(pickupQuery()+" WHERE p.id=?",id); long sid=Db.id(row,"studentId"); sec.student(sid);
        row.remove("codeHash"); row.remove("requestHash"); row.remove("requestId");
        String phone=row.get("phone").toString(); row.put("phoneLast4",phone.substring(Math.max(0,phone.length()-4))); row.put("phoneMasked","•••• "+row.get("phoneLast4"));
        if(!sec.actor().role().equals("PARENT")) row.remove("phone");
        Window w=window(row); row.put("startDate",w.startDate()); row.put("endDate",w.endDate()); row.put("startTime",w.startTime().toString()); row.put("endTime",w.endTime().toString());
        boolean expired=!sec.now().isBefore((LocalDateTime)row.get("expiresAt")),bound=guardianBound(Db.id(row,"guardianId"),sid),available=contactValid(row),used=usedToday(id);
        row.put("effectiveStatus",row.get("status").equals("ACTIVE")?(expired?"EXPIRED":!bound||!available?"INVALIDATED":"ACTIVE"):row.get("status"));
        row.put("canRevoke",sec.actor().role().equals("PARENT")&&row.get("status").equals("ACTIVE")&&!expired);
        boolean inSchool=db.count("SELECT COUNT(*) FROM attendance WHERE student_id=? AND date=? AND status='PRESENT'",sid,sec.today())>0;
        row.put("usedToday",used); row.put("usageDates",db.list("SELECT business_date FROM pickup_use WHERE authorization_id=? ORDER BY business_date",id).stream().map(use->use.get("businessDate")).toList());
        row.put("canVerify",bound&&available&&!sec.actor().role().equals("PARENT")&&inSchool&&activeNow(row)&&!used);
        row.put("logs",logs("PICKUP",id));
        var verification=db.optional("SELECT id,actor_id,authorization_version,verified_at,expires_at,used_at FROM pickup_verification WHERE authorization_id=? ORDER BY id DESC LIMIT 1",id);
        row.put("verification",verification);
        row.put("canCheckout",!alerts.blocked(sid,sec.today())&&bound&&available&&inSchool&&activeNow(row)&&!used&&verification!=null&&Db.id(verification,"actorId")==sec.actor().id()&&verification.get("usedAt")==null&&Db.integer(verification,"authorizationVersion",-1)==Db.integer(row,"version",0)&&((LocalDateTime)verification.get("verifiedAt")).toLocalDate().equals(sec.today())&&sec.now().isBefore((LocalDateTime)verification.get("expiresAt"))); return row;
    }
    List<Map<String,Object>> pickups(LocalDate date,Long sid) {
        if(sid!=null) sec.student(sid); List<Object> args=new ArrayList<>(); String where=" WHERE "+sec.scope("s")+" AND s.privacy_erased_at IS NULL";
        if(date!=null) { where+=" AND p.start_date<=? AND p.end_date>=?"; args.add(date); args.add(date); }
        if(sid!=null) { where+=" AND s.id=?"; args.add(sid); }
        return db.list("SELECT p.id FROM pickup_authorization p JOIN student s ON s.id=p.student_id"+where+" ORDER BY p.id DESC",args.toArray()).stream().map(r->pickup(Db.id(r,"id"))).toList();
    }
    LocalDateTime inputTime(Map<String,Object> body,String field) {
        try { return LocalDateTime.parse(Db.required(body,field,30)); } catch(java.time.format.DateTimeParseException e) { throw ApiException.bad("授权时间格式错误"); }
    }
    Window inputWindow(Map<String,Object> body,String mode) {
        LocalDate date=body.containsKey("startDate")?sec.date(Db.required(body,"startDate",10)):sec.date(body.containsKey("date")?Db.required(body,"date",10):null);
        LocalDate end=body.containsKey("endDate")?sec.date(Db.required(body,"endDate",10)):date;
        LocalDateTime from=body.containsKey("validFrom")?inputTime(body,"validFrom"):date.atStartOfDay(),until=body.containsKey("expiresAt")?inputTime(body,"expiresAt"):end.atTime(23,59,59);
        LocalTime startTime,endTime;
        try { startTime=body.containsKey("startTime")?LocalTime.parse(Db.required(body,"startTime",8)):from.toLocalTime(); endTime=body.containsKey("endTime")?LocalTime.parse(Db.required(body,"endTime",8)):until.toLocalTime(); }
        catch(java.time.format.DateTimeParseException e) { throw ApiException.bad("每日接送时间须为HH:mm或HH:mm:ss"); }
        if(end.isBefore(date)||mode.equals("SINGLE")&&!date.equals(end)||!endTime.isAfter(startTime)||!from.toLocalDate().equals(date)||!until.toLocalDate().equals(end)) throw ApiException.bad("授权须在当天或未来，单次须同日且每日起止时间不能跨午夜");
        return new Window(date,end,startTime,endTime);
    }
    String code() { return String.format(Locale.ROOT,"%06d",sec.random.nextInt(1_000_000)); }
    @Transactional public Map<String,Object> createPickup(Map<String,Object> body) {
        sec.parent(); rejectIdentityFields(body); long sid=Db.number(body,"studentId"); lock(sid);
        Map<String,Object> contact=null; Long contactId=body.get("contactId")==null?null:Db.number(body,"contactId");
        if(contactId!=null) { contact=rawContact(contactId); if(Db.id(contact,"studentId")!=sid) throw ApiException.forbidden(); if(!body.containsKey("expectedContactVersion")) throw ApiException.bad("请选择并确认当前版本的常用接送人 expectedContactVersion"); }
        String name=contact==null?Db.required(body,"pickupName",80):contact.get("name").toString(),relation=contact==null?Db.required(body,"relationship",40):contact.get("relationship").toString(),phone=contact==null?phone(body):contact.get("phone").toString();
        String mode=body.containsKey("mode")?Db.required(body,"mode",12):"SINGLE"; Db.choice(mode,"SINGLE","MULTI"); Window w=inputWindow(body,mode);
        String note=Db.text(body,"note"); if(note.length()>1000) throw ApiException.bad("接送备注不能超过1000字");
        boolean legacy=List.of("mode","startDate","endDate","startTime","endTime","note","contactId").stream().noneMatch(body::containsKey);
        String key=request(body,"pickup"),hash=sec.hash(contactId!=null?sid+"\n"+contactId+"\n"+Db.integer(body,"expectedContactVersion",-1)+"\n"+w+"\n"+mode+"\n"+note:legacy?sid+"\n"+w.startDate()+"\n"+name+"\n"+relation+"\n"+phone+"\n"+w.from()+"\n"+w.until():sid+"\n"+name+"\n"+relation+"\n"+phone+"\n"+w+"\n"+mode+"\n"+note+"\n"+contactId);
        var old=db.optional("SELECT id,request_hash FROM pickup_authorization WHERE request_id=?",key);
        if(old!=null) { if(!hash.equals(old.get("requestHash"))) throw new ApiException(409,"requestId已用于其他接送安排"); return pickup(Db.id(old,"id")); }
        if(contact!=null&&(contact.get("deletedAt")!=null||Db.integer(contact,"version",0)!=Db.integer(body,"expectedContactVersion",-1))) throw new ApiException(409,"常用接送人已变更，请刷新并重新确认");
        if(w.startDate().isBefore(sec.today())||!w.until().isAfter(sec.now())) throw ApiException.bad("授权须在当天或未来且截止时间尚未到达");
        if(mode.equals("SINGLE")&&db.count("SELECT COUNT(*) FROM attendance WHERE student_id=? AND date=? AND status='LEFT'",sid,w.startDate())>0) throw ApiException.bad("学生当天已经离校");
        for(var other:db.list("SELECT * FROM pickup_authorization WHERE student_id=? AND status='ACTIVE' AND expires_at>?",sid,sec.now())) if(guardianBound(Db.id(other,"guardianId"),sid)&&contactValid(other)&&w.overlaps(window(other))) throw new ApiException(409,"已有日期与每日时段重叠的接送授权，请先撤销原授权");
        String code=code(); long id=db.insert("INSERT INTO pickup_authorization(student_id,guardian_id,pickup_name,relationship,phone,valid_from,expires_at,code_hash,created_at,updated_at,request_id,request_hash,contact_id,contact_version,mode,start_date,end_date,start_time,end_time,note) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",sid,sec.actor().id(),name,relation,phone,w.from(),w.until(),sec.passwords.encode(code),sec.now(),sec.now(),key,hash,contactId,contact==null?null:contact.get("identityVersion"),mode,w.startDate(),w.endDate(),w.startTime(),w.endTime(),note);
        log("PICKUP",id,sid,w.startDate(),"ACTIVE","已授权 "+name+"（"+relation+"）接送",0,true);
        var row=pickup(id); row.put("pickupCode",code); return row;
    }
    @Transactional public Map<String,Object> revokePickup(long id,Map<String,Object> body,boolean rotate) {
        sec.parent(); rejectIdentityFields(body); var initial=pickup(id); long sid=Db.id(initial,"studentId"); lock(sid); var row=db.one("SELECT * FROM pickup_authorization WHERE id=? FOR UPDATE",id);
        if(!rotate&&row.get("status").equals("REVOKED")) return pickup(id); version(row,body);
        if(!row.get("status").equals("ACTIVE")||!sec.now().isBefore((LocalDateTime)row.get("expiresAt"))||!contactValid(row)) throw ApiException.bad("授权已使用、撤销、过期或接送人已变更");
        String code=rotate?code():""; if(rotate) while(sec.passwords.matches(code,row.get("codeHash").toString())) code=code();
        if(rotate) db.update("UPDATE pickup_authorization SET code_hash=?,failed_attempts=0,last_failed_at=NULL,version=version+1,updated_at=? WHERE id=?",sec.passwords.encode(code),sec.now(),id);
        else db.update("UPDATE pickup_authorization SET status='REVOKED',version=version+1,updated_at=? WHERE id=?",sec.now(),id);
        log("PICKUP",id,sid,window(row).startDate(),rotate?"ROTATED":"REVOKED",rotate?"监护人重置接送码，旧核验失效":Db.required(body,"note",1000),Db.integer(row,"version",0)+1,false);
        var result=pickup(id); if(rotate) result.put("pickupCode",code); return result;
    }
    Map<String,Object> verificationFailure(Map<String,Object> row,String message,int status) {
        long id=Db.id(row,"id"),sid=Db.id(row,"studentId");
        log("PICKUP",id,sid,sec.today(),"VERIFY_REJECTED",message,Db.integer(row,"version",0),false); alerts.rejected(sid,id,message);
        return Map.of("success",false,"message",message,"code","PICKUP_REJECTED","status",status);
    }
    @Transactional public Map<String,Object> verifyPickup(long id,Map<String,Object> body) {
        sec.staff(); rejectIdentityFields(body); var initial=pickup(id); long sid=Db.id(initial,"studentId"); lock(sid); var row=db.one("SELECT * FROM pickup_authorization WHERE id=? FOR UPDATE",id);
        String code=Db.required(body,"code",6),name=Db.required(body,"pickupName",80),last4=Db.required(body,"phoneLast4",4),key=request(body,"verify");
        if(!code.matches("[0-9]{6}")||!last4.matches("[0-9]{4}")||!Boolean.TRUE.equals(body.get("confirmedIdentity"))) throw ApiException.bad("请核对接送人姓名、电话后四位及六位接送码，并确认现场核验");
        String hash=sec.hash(id+"\n"+code+"\n"+name+"\n"+last4); var existing=db.optional("SELECT * FROM pickup_verification WHERE request_id=?",key);
        boolean bound=guardianBound(Db.id(row,"guardianId"),sid);
        if(existing!=null) {
            if(!hash.equals(existing.get("requestHash"))) throw new ApiException(409,"requestId已用于其他核验内容");
            if(!bound||!activeNow(row)||usedToday(id)||existing.get("usedAt")!=null||!((LocalDateTime)existing.get("verifiedAt")).toLocalDate().equals(sec.today())||Db.integer(row,"version",0)!=Db.integer(existing,"authorizationVersion",-1)||!sec.now().isBefore((LocalDateTime)existing.get("expiresAt"))) return verificationFailure(row,"原核验已失效，请刷新并重新核验",400);
            alerts.clearVerified(sid,id); return Map.of("success",true,"verification",db.one("SELECT id,authorization_id,student_id,actor_id,verified_at,expires_at,used_at FROM pickup_verification WHERE id=?",existing.get("id")));
        }
        version(row,body); LocalDateTime now=sec.now();
        if(!activeNow(row)||usedToday(id)) return verificationFailure(row,"接送授权已撤销、使用、过期、接送人变更或不在每日授权时段",400);
        if(!bound) return verificationFailure(row,"授权监护人的绑定已撤销，请重新安排接送",403);
        if(db.count("SELECT COUNT(*) FROM attendance WHERE student_id=? AND date=? AND status='PRESENT'",sid,sec.today())==0) return verificationFailure(row,"学生当前不在校，不能核验离校",400);
        if(Db.integer(row,"failedAttempts",0)>=5&&row.get("lastFailedAt")!=null&&now.isBefore(((LocalDateTime)row.get("lastFailedAt")).plusMinutes(15))) return verificationFailure(row,"接送码错误次数过多，请15分钟后重试或请监护人重置",429);
        String phone=row.get("phone").toString();
        if(!name.equals(row.get("pickupName"))||!phone.endsWith(last4)||!sec.passwords.matches(code,row.get("codeHash").toString())) {
            int attempts=row.get("lastFailedAt")==null||now.isAfter(((LocalDateTime)row.get("lastFailedAt")).plusMinutes(15))?1:Db.integer(row,"failedAttempts",0)+1;
            db.update("UPDATE pickup_authorization SET failed_attempts=?,last_failed_at=? WHERE id=?",attempts,now,id); return verificationFailure(row,"接送信息或接送码不匹配，已拦截离校",400);
        }
        int revision=Db.integer(row,"version",0)+1;
        db.update("UPDATE pickup_authorization SET version=?,failed_attempts=0,last_failed_at=NULL,updated_at=? WHERE id=?",revision,now,id);
        LocalDateTime until=window(row).endDate().equals(sec.today())?(LocalDateTime)row.get("expiresAt"):sec.today().atTime(window(row).endTime());
        if(sec.today().atTime(window(row).endTime()).isBefore(until)) until=sec.today().atTime(window(row).endTime());
        LocalDateTime expires=now.plusMinutes(10).isBefore(until)?now.plusMinutes(10):until;
        long verification=db.insert("INSERT INTO pickup_verification(authorization_id,authorization_version,student_id,actor_id,verified_at,expires_at,request_id,request_hash) VALUES(?,?,?,?,?,?,?,?)",id,revision,sid,sec.actor().id(),now,expires,key,hash);
        log("PICKUP",id,sid,sec.today(),"VERIFIED","老师已核对接送人姓名、电话后四位与接送码",revision,true); alerts.clearVerified(sid,id);
        return Map.of("success",true,"verification",db.one("SELECT id,authorization_id,student_id,actor_id,verified_at,expires_at,used_at FROM pickup_verification WHERE id=?",verification));
    }
    void requireCheckIn(long sid,LocalDate date) {
        if(db.count("SELECT COUNT(*) FROM leave_request WHERE student_id=? AND leave_date=? AND status='APPROVED'",sid,date)>0) throw ApiException.bad("当天请假已批准，请先撤销请假再签到；请假不消课");
    }
    void consumeCheckout(long sid,LocalDate date,String historicalReason) {
        requireCheckIn(sid,date); alerts.requireClear(sid,date);
        if(date.isBefore(sec.today())) {
            sec.admin(); if(historicalReason.isBlank()||historicalReason.length()>1000) throw ApiException.bad("历史离校补录须由校长或老板填写核对原因");
            sec.audit("HISTORICAL_CHECK_OUT","student="+sid+", date="+date+", reason="+historicalReason); return;
        }
        var v=db.optional("SELECT v.*,p.guardian_id FROM pickup_verification v JOIN pickup_authorization p ON p.id=v.authorization_id WHERE v.student_id=? AND v.actor_id=? AND v.used_at IS NULL AND v.verified_at>=? AND v.verified_at<? AND v.expires_at>? AND p.status='ACTIVE' AND p.version=v.authorization_version ORDER BY v.id DESC LIMIT 1",sid,sec.actor().id(),date.atStartOfDay(),date.plusDays(1).atStartOfDay(),sec.now());
        if(v==null) throw ApiException.bad("请先在安全接送中核验当前有效授权，未核验不能离校");
        var authorization=db.one("SELECT * FROM pickup_authorization WHERE id=?",v.get("authorizationId"));
        if(!activeNow(authorization)||usedToday(Db.id(authorization,"id"))) throw ApiException.bad("接送授权已失效或当天已交接，请重新核对");
        if(!guardianBound(Db.id(v,"guardianId"),sid)) throw ApiException.bad("监护人绑定已撤销，请重新核验接送");
        db.insert("INSERT INTO pickup_use(authorization_id,student_id,business_date,verification_id,actor_id,used_at) VALUES(?,?,?,?,?,?)",authorization.get("id"),sid,date,v.get("id"),sec.actor().id(),sec.now());
        db.update("UPDATE pickup_verification SET used_at=? WHERE id=?",sec.now(),v.get("id"));
        boolean single=authorization.get("mode").equals("SINGLE");
        db.update("UPDATE pickup_authorization SET status=?,used_at=?,updated_at=?,version=version+1 WHERE id=?",single?"USED":"ACTIVE",sec.now(),sec.now(),authorization.get("id"));
        log("PICKUP",Db.id(authorization,"id"),sid,date,"USED",single?"核验后确认离校，授权已一次性使用":"核验后确认离校，多次授权当天已使用",Db.integer(authorization,"version",0)+1,true);
    }
    void requirePhotoCheckout(long sid,LocalDate date) {
        if(!date.equals(sec.today())) throw ApiException.bad("老师接送拍照仅登记当天");
        sec.student(sid);
        requireCheckIn(sid,date);
        alerts.requireClear(sid,date);
    }
}
