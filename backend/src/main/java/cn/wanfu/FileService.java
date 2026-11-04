package cn.wanfu;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class FileService {
    final Db db; final Security sec; final FileAccessTokenService tickets; final PrivacyService privacy; final Path root;
    public FileService(Db db,Security sec,FileAccessTokenService tickets,PrivacyService privacy,@Value("${app.upload-dir}") String root) { this.db=db; this.sec=sec; this.tickets=tickets; this.privacy=privacy; this.root=Path.of(root).toAbsolutePath().normalize(); }
    Map<String,Object> publicFile(Map<String,Object> row) {
        return Map.of("id",row.get("id"),"name",row.get("name"),"url","/api/files/"+row.get("id")+"?ticket="+tickets.issue(Db.id(row,"id")),"contentType",row.get("contentType"),"size",row.get("size"),"expiresAt",sec.now().plusMinutes(2));
    }
    boolean canRead(long id) {
        var f=db.one("SELECT * FROM stored_file WHERE id=?",id);
        if(Boolean.TRUE.equals(f.get("privacyBlocked"))) return false;
        if(db.count("SELECT COUNT(*) FROM campus WHERE id=? AND "+sec.campusScope("id"),f.get("campusId"))==0) return false;
        var linked=db.list("SELECT DISTINCT student_id FROM file_link WHERE file_id=?",id);
        if(!linked.isEmpty()) {
            String visible=sec.actor().role().equals("PARENT")?
                " AND (l.kind NOT IN ('SUMMARY_PHOTO','SUMMARY_HISTORY') OR (l.kind='SUMMARY_PHOTO' AND EXISTS(SELECT 1 FROM daily_summary d WHERE d.id=l.target_id AND d.student_id=l.student_id AND d.status='PUBLISHED')))":"";
            return linked.stream().allMatch(row->privacy.canUseMedia(Db.id(row,"studentId"))) && db.count("SELECT COUNT(*) FROM file_link l JOIN student s ON s.id=l.student_id WHERE l.file_id=? AND s.privacy_erased_at IS NULL AND "+sec.scope("s")+visible,id)>0;
        }
        if(db.count("SELECT COUNT(*) FROM campus_family_service WHERE payment_qr_file_id=? AND "+sec.campusScope("campus_id"),id)>0) return true;
        if(db.count("SELECT COUNT(*) FROM reward_prize WHERE image_file_id=? AND "+sec.campusScope("campus_id"),id)>0||db.count("SELECT COUNT(*) FROM prize_redemption r JOIN reward_prize p ON p.id=r.prize_id WHERE r.image_file_id=? AND "+sec.campusScope("p.campus_id"),id)>0)return true;
        return Db.id(f,"ownerId")==sec.actor().id();
    }
    void validate(List<Long> ids) { for(long id:ids) if(!canRead(id)) throw ApiException.forbidden(); }
    Map<String,Object> access(long id) { if(!canRead(id)) throw ApiException.forbidden(); return publicFile(db.one("SELECT * FROM stored_file WHERE id=?",id)); }
    void link(List<Long> ids,String kind,long target,long student) {
        sec.student(student); db.one("SELECT id FROM student WHERE id=? FOR UPDATE",student); sec.student(student);
        if(!ids.isEmpty()) privacy.requireMedia(student); validate(ids); long campus=sec.studentCampus(student);
        for(long id:ids) { if(Db.id(db.one("SELECT campus_id FROM stored_file WHERE id=?",id),"campusId")!=campus) throw ApiException.forbidden(); if(db.count("SELECT COUNT(*) FROM campus_family_service WHERE payment_qr_file_id=?",id)>0||db.count("SELECT COUNT(*) FROM reward_prize WHERE image_file_id=?",id)>0||db.count("SELECT COUNT(*) FROM prize_redemption WHERE image_file_id=?",id)>0) throw ApiException.bad("校区收款码或奖品封面不能用作学生附件，请单独上传"); }
        for(long id:ids) {
            if(!Set.of("SUMMARY_PHOTO","SUMMARY_HISTORY").contains(kind)&&db.count("SELECT COUNT(*) FROM file_link WHERE file_id=? AND kind IN ('SUMMARY_PHOTO','SUMMARY_HISTORY')",id)>0) throw ApiException.bad("小结作品请在小结中使用");
            if(db.count("SELECT COUNT(*) FROM file_link WHERE file_id=? AND kind=? AND target_id=?",id,kind,target)==0) db.update("INSERT INTO file_link(file_id,kind,target_id,student_id) VALUES(?,?,?,?)",id,kind,target,student);
        }
    }
    List<Map<String,Object>> linked(String kind,long id) { return db.list("SELECT f.*,l.student_id linked_student_id FROM stored_file f JOIN file_link l ON l.file_id=f.id JOIN student s ON s.id=l.student_id WHERE l.kind=? AND l.target_id=? AND "+sec.scope("s")+" ORDER BY f.id",kind,id).stream().filter(f->privacy.canUseMedia(Db.id(f,"linkedStudentId"))&&canRead(Db.id(f,"id"))).map(this::publicFile).toList(); }
    @Transactional public Map<String,Object> upload(MultipartFile file) throws IOException {
        long campus=sec.writeCampus(Map.of());
        if(file.isEmpty()||file.getSize()>20L*1024*1024) throw ApiException.bad("文件不能为空且须小于等于20MB");
        byte[] prefix; try(var in=file.getInputStream()) { prefix=in.readNBytes(32); }
        String type=detected(prefix,file.getContentType());
        String name=Optional.ofNullable(file.getOriginalFilename()).orElse("upload").replace('\\','/'); name=name.substring(name.lastIndexOf('/')+1).replaceAll("[\\r\\n\\x00]","");
        if(name.isBlank()||name.length()>255) throw ApiException.bad("文件名无效");
        Files.createDirectories(root); String key=UUID.randomUUID().toString(); Path target=root.resolve(key);
        try {
            try(var in=file.getInputStream()) { Files.copy(in,target); }
            long id=db.insert("INSERT INTO stored_file(owner_id,name,storage_key,content_type,size,created_at,campus_id) VALUES(?,?,?,?,?,?,?)",sec.actor().id(),name,key,type,file.getSize(),sec.now(),campus);
            sec.auditCampus(campus); sec.audit("FILE_UPLOAD","file="+id+", type="+type+", size="+file.getSize());
            return publicFile(db.one("SELECT * FROM stored_file WHERE id=?",id));
        } catch(Exception e) { Files.deleteIfExists(target); throw e; }
    }
    String detected(byte[] b,String declared) {
        if(b.length>=8&&b[0]==(byte)0x89&&b[1]=='P'&&b[2]=='N'&&b[3]=='G'&&b[4]==13&&b[5]==10&&b[6]==26&&b[7]==10) return "image/png";
        if(b.length>=3&&b[0]==(byte)0xff&&b[1]==(byte)0xd8&&b[2]==(byte)0xff) return "image/jpeg";
        if(b.length>=6&&new String(b,0,6,java.nio.charset.StandardCharsets.US_ASCII).matches("GIF8[79]a")) return "image/gif";
        if(b.length>=12&&new String(b,0,4).equals("RIFF")) { String sub=new String(b,8,4); if(sub.equals("WEBP")) return "image/webp"; if(sub.equals("WAVE")) return "audio/wav"; }
        if(b.length>=4&&new String(b,0,4).equals("OggS")) return "audio/ogg";
        if(b.length>=3&&(new String(b,0,3).equals("ID3")||(b[0]==(byte)0xff&&(b[1]&0xe0)==0xe0))) return "audio/mpeg";
        if(b.length>=12&&new String(b,4,4).equals("ftyp")) return declared!=null&&declared.startsWith("audio/")?"audio/mp4":"video/mp4";
        if(b.length>=4&&b[0]==0x1a&&b[1]==0x45&&b[2]==(byte)0xdf&&b[3]==(byte)0xa3) return declared!=null&&declared.startsWith("audio/")?"audio/webm":"video/webm";
        throw ApiException.bad("仅允许有效的图片、音频或视频文件（不接受SVG/HTML）");
    }
    byte[] bytes(long id)throws IOException {
        if(!canRead(id))throw ApiException.forbidden();var row=db.one("SELECT storage_key FROM stored_file WHERE id=?",id);Path path=root.resolve(row.get("storageKey").toString()).normalize();if(!path.startsWith(root)||!Files.isRegularFile(path))throw ApiException.missing();return Files.readAllBytes(path);
    }
    Map<String,Object> generatedPdf(String name,byte[] bytes,long student)throws IOException {
        sec.student(student);privacy.requireMedia(student);Files.createDirectories(root);String key=UUID.randomUUID().toString();Path path=root.resolve(key);
        try{Files.write(path,bytes);long id=db.insert("INSERT INTO stored_file(owner_id,name,storage_key,content_type,size,created_at,campus_id) VALUES(?,?,?,'application/pdf',?,?,?)",sec.actor().id(),name,key,bytes.length,sec.now(),sec.studentCampus(student));link(List.of(id),"MISTAKE_BOOK",id,student);return access(id);}catch(Exception error){Files.deleteIfExists(path);throw error;}
    }
    Map<String,Object> generatedImage(String name,byte[] bytes,long student)throws IOException {
        sec.student(student);privacy.requireMedia(student);
        if(bytes.length==0||bytes.length>20L*1024*1024||!detected(bytes,null).startsWith("image/"))throw ApiException.bad("识别图片无效或过大");
        Files.createDirectories(root);Path path=root.resolve(UUID.randomUUID().toString());
        try {
            Files.write(path,bytes);
            if(org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive())
                org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){public void afterCompletion(int status){if(status!=STATUS_COMMITTED)try{Files.deleteIfExists(path);}catch(IOException error){org.slf4j.LoggerFactory.getLogger(FileService.class).warn("Failed to remove rolled-back scan image {}",path.getFileName());}}});
            long id=db.insert("INSERT INTO stored_file(owner_id,name,storage_key,content_type,size,created_at,campus_id) VALUES(?,?,?,'image/png',?,?,?)",sec.actor().id(),name,path.getFileName().toString(),bytes.length,sec.now(),sec.studentCampus(student));
            link(List.of(id),"SCAN_IMAGE",id,student);return access(id);
        } catch(Exception error){Files.deleteIfExists(path);throw error;}
    }
    public ResponseEntity<Resource> download(long id) {
        if(!canRead(id)) throw ApiException.forbidden(); var row=db.one("SELECT * FROM stored_file WHERE id=?",id);
        Path p=root.resolve(row.get("storageKey").toString()).normalize(); if(!p.startsWith(root)||!Files.isRegularFile(p)) throw ApiException.missing();
        sec.auditCampus(Db.id(row,"campusId")); sec.audit("FILE_READ","file="+id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(row.get("contentType").toString())).header("X-Content-Type-Options","nosniff").header("Cache-Control","private, no-store").header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.inline().filename(row.get("name").toString(),java.nio.charset.StandardCharsets.UTF_8).build().toString()).body(new FileSystemResource(p));
    }
}
