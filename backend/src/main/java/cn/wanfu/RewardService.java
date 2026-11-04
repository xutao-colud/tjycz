package cn.wanfu;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RewardService {
    final Db db;final Security sec;final FinanceService finance;final FileService files;
    public RewardService(Db db,Security sec,FinanceService finance,FileService files){this.db=db;this.sec=sec;this.finance=finance;this.files=files;}
    void version(Map<String,Object> row,Map<String,Object> body){if(!body.containsKey("expectedVersion")||Db.integer(body,"expectedVersion",-1)!=Db.integer(row,"version",0))throw new ApiException(409,"记录已更新，请刷新后重试");}
    List<Map<String,Object>> presets(){return db.list("SELECT p.*,c.name campus_name FROM point_preset p JOIN campus c ON c.id=p.campus_id WHERE "+sec.campusScope("p.campus_id")+(sec.isAdmin()?"":" AND p.enabled=TRUE")+" ORDER BY p.id");}
    @Transactional public Map<String,Object> preset(Long id,Map<String,Object> body){
        sec.admin();long campus=id==null?sec.writeCampus(body):Db.id(db.one("SELECT campus_id FROM point_preset WHERE id=?",id),"campusId");sec.campus(campus);
        String name=Db.required(body,"name",100);int points=Db.integer(body,"points",0);if(points<1||points>100)throw ApiException.bad("固定奖励须为1至100整数积分");
        boolean enabled=!Boolean.FALSE.equals(body.get("enabled"));
        if(id==null)id=db.insert("INSERT INTO point_preset(campus_id,name,points,enabled) VALUES(?,?,?,?)",campus,name,points,enabled);
        else{var row=db.one("SELECT * FROM point_preset WHERE id=? FOR UPDATE",id);version(row,body);db.update("UPDATE point_preset SET name=?,points=?,enabled=?,version=version+1 WHERE id=?",name,points,enabled,id);}
        sec.audit("POINT_PRESET_SAVE","preset="+id);return db.one("SELECT * FROM point_preset WHERE id=?",id);
    }
    @Transactional public Map<String,Object> award(Map<String,Object> body){
        sec.staff();long sid=Db.number(body,"studentId"),preset=Db.number(body,"presetId");sec.student(sid);finance.lockStudent(sid);String request=Db.required(body,"requestId",100);
        var old=db.optional("SELECT * FROM point_award WHERE actor_id=? AND request_id=?",sec.actor().id(),request);
        if(old!=null){if(Db.id(old,"studentId")!=sid||Db.id(old,"presetId")!=preset)throw new ApiException(409,"requestId已用于不同的积分奖励");return old;}
        var rule=db.one("SELECT * FROM point_preset WHERE id=? FOR UPDATE",preset);sec.campus(Db.id(rule,"campusId"));
        if(Db.id(rule,"campusId")!=sec.studentCampus(sid)||!Boolean.TRUE.equals(rule.get("enabled")))throw ApiException.bad("请选择孩子所属校区启用的积分规则");
        int points=Db.integer(rule,"points",0);long id=db.insert("INSERT INTO point_award(student_id,preset_id,actor_id,points,reason,request_id,created_at) VALUES(?,?,?,?,?,?,?)",sid,preset,sec.actor().id(),points,rule.get("name"),request,sec.now());
        finance.points(sid,points,"PRESET_AWARD",rule.get("name").toString(),"point-award:"+id);sec.audit("PRESET_AWARD","student="+sid+", preset="+preset+", points="+points);return db.one("SELECT * FROM point_award WHERE id=?",id);
    }
    Map<String,Object> decorate(Map<String,Object> row){Object image=row.get("imageFileId");row.put("image",image!=null&&files.canRead(((Number)image).longValue())?files.access(((Number)image).longValue()):null);return row;}
    List<Map<String,Object>> prizes(){return db.list("SELECT p.*,c.name campus_name FROM reward_prize p JOIN campus c ON c.id=p.campus_id WHERE "+sec.campusScope("p.campus_id")+(sec.isAdmin()?"":" AND p.enabled=TRUE")+" ORDER BY p.id DESC").stream().map(this::decorate).toList();}
    @Transactional public Map<String,Object> prize(Long id,Map<String,Object> body){
        sec.admin();long campus=id==null?sec.writeCampus(body):Db.id(db.one("SELECT campus_id FROM reward_prize WHERE id=?",id),"campusId");sec.campus(campus);
        String name=Db.required(body,"name",100);int points=Db.integer(body,"pointsCost",0),stock=Db.integer(body,"stock",-1);if(points<1||points>1000000||stock<0||stock>1000000)throw ApiException.bad("兑换积分或库存无效");
        Long photo=body.get("imageFileId")==null?null:Db.number(body,"imageFileId");
        if(photo!=null){if(!files.canRead(photo))throw ApiException.forbidden();var image=db.one("SELECT * FROM stored_file WHERE id=?",photo);if(Db.id(image,"campusId")!=campus)throw ApiException.forbidden();if(!image.get("contentType").toString().startsWith("image/"))throw ApiException.bad("奖品封面必须是图片");if(db.count("SELECT COUNT(*) FROM file_link WHERE file_id=?",photo)>0)throw ApiException.bad("请单独上传奖品图片，不可使用学生私有附件");}
        boolean enabled=!Boolean.FALSE.equals(body.get("enabled"));
        if(id==null)id=db.insert("INSERT INTO reward_prize(campus_id,name,points_cost,stock,image_file_id,enabled) VALUES(?,?,?,?,?,?)",campus,name,points,stock,photo,enabled);
        else{var row=db.one("SELECT * FROM reward_prize WHERE id=? FOR UPDATE",id);version(row,body);db.update("UPDATE reward_prize SET name=?,points_cost=?,stock=?,image_file_id=?,enabled=?,version=version+1 WHERE id=?",name,points,stock,photo,enabled,id);}
        sec.audit("PRIZE_SAVE","prize="+id);return decorate(db.one("SELECT * FROM reward_prize WHERE id=?",id));
    }
    List<Map<String,Object>> redemptions(Long student){
        if(student!=null)sec.student(student);String sql="SELECT r.*,s.name student_name,u.name teacher_name,cl.campus_id FROM prize_redemption r JOIN student s ON s.id=r.student_id JOIN school_class cl ON cl.id=s.class_id JOIN app_user u ON u.id=r.actor_id WHERE s.privacy_erased_at IS NULL AND "+sec.scope("s");
        var rows=student==null?db.list(sql+" ORDER BY r.id DESC"):db.list(sql+" AND s.id=? ORDER BY r.id DESC",student);
        return rows.stream().map(row->{decorate(row);row.put("attachments",files.linked("PRIZE_REDEMPTION",Db.id(row,"id")));return row;}).toList();
    }
    @Transactional public Map<String,Object> redeem(Map<String,Object> body){
        sec.staff();long sid=Db.number(body,"studentId"),prize=Db.number(body,"prizeId");sec.student(sid);finance.lockStudent(sid);String request=Db.required(body,"requestId",100);
        var attachments=Db.optionalIds(body,"attachmentIds");if(attachments.size()>6)throw ApiException.bad("最多6张兑换照片");String hash=sec.hash(sid+":"+prize+":"+new TreeSet<>(attachments));
        var old=db.optional("SELECT * FROM prize_redemption WHERE actor_id=? AND request_id=?",sec.actor().id(),request);
        if(old!=null){if(!old.get("requestHash").equals(hash))throw new ApiException(409,"requestId已用于不同的兑换");return redemptions(sid).stream().filter(r->Db.id(r,"id")==Db.id(old,"id")).findFirst().orElseThrow();}
        var item=db.one("SELECT * FROM reward_prize WHERE id=? FOR UPDATE",prize);sec.campus(Db.id(item,"campusId"));
        if(Db.id(item,"campusId")!=sec.studentCampus(sid))throw ApiException.bad("奖品与孩子须属于同一校区");version(item,body);
        if(!Boolean.TRUE.equals(item.get("enabled"))||Db.integer(item,"stock",0)<1)throw ApiException.bad("奖品已下架或库存不足");
        int points=Db.integer(item,"pointsCost",0);long id=db.insert("INSERT INTO prize_redemption(prize_id,student_id,actor_id,prize_name,points_cost,image_file_id,request_id,request_hash,created_at) VALUES(?,?,?,?,?,?,?,?,?)",prize,sid,sec.actor().id(),item.get("name"),points,item.get("imageFileId"),request,hash,sec.now());
        photos(attachments,id,sid);finance.points(sid,-points,"PRIZE_REDEMPTION","兑换奖品："+item.get("name"),"prize-redemption:"+id);db.update("UPDATE reward_prize SET stock=stock-1,version=version+1 WHERE id=?",prize);
        sec.audit("PRIZE_REDEEM","student="+sid+", prize="+prize+", points="+points);return redemptions(sid).stream().filter(r->Db.id(r,"id")==id).findFirst().orElseThrow();
    }
    void photos(List<Long> attachments,long id,long sid){for(long file:attachments){if(!files.canRead(file))throw ApiException.forbidden();if(!db.one("SELECT content_type FROM stored_file WHERE id=?",file).get("contentType").toString().startsWith("image/"))throw ApiException.bad("兑换凭证请使用图片");}files.link(attachments,"PRIZE_REDEMPTION",id,sid);}
    @Transactional public Object photos(long id,Map<String,Object> body){sec.staff();var row=db.one("SELECT * FROM prize_redemption WHERE id=?",id);long sid=Db.id(row,"studentId");sec.student(sid);finance.lockStudent(sid);var ids=Db.optionalIds(body,"attachmentIds");if(ids.isEmpty()||ids.size()+db.count("SELECT COUNT(*) FROM file_link WHERE kind='PRIZE_REDEMPTION' AND target_id=?",id)>6)throw ApiException.bad("请选择照片，总共最多6张");photos(ids,id,sid);sec.audit("REDEMPTION_PHOTO","redemption="+id);return redemptions(sid).stream().filter(r->Db.id(r,"id")==id).findFirst().orElseThrow();}
}
