package cn.wanfu;

import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class FileAccessTokenService {
    final Db db; final Security sec;
    public FileAccessTokenService(Db db,Security sec) { this.db=db; this.sec=sec; }
    String issue(long file) {
        db.update("DELETE FROM file_access_ticket WHERE expires_at<=?",sec.now());
        byte[] bytes=new byte[32]; sec.random.nextBytes(bytes);
        String ticket=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        db.update("INSERT INTO file_access_ticket(ticket_hash,session_hash,file_id,expires_at) VALUES(?,?,?,?)",sec.hash(ticket),sec.actor().sessionHash(),file,sec.now().plusMinutes(2));
        return ticket;
    }
    Security.Actor authenticate(String ticket,long file) {
        if(ticket==null||!ticket.matches("[A-Za-z0-9_-]{43}")) throw new ApiException(401,"附件访问凭证无效或已过期，请刷新页面");
        var row=db.optional("SELECT session_hash FROM file_access_ticket WHERE ticket_hash=? AND file_id=? AND expires_at>?",sec.hash(ticket),file,sec.now());
        if(row==null) throw new ApiException(401,"附件访问凭证无效或已过期，请刷新页面");
        return sec.authenticateHash(row.get("sessionHash").toString());
    }
}
