package cn.wanfu;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AttendanceLocationService {
    final Db db; final Security sec;
    public AttendanceLocationService(Db db, Security sec) { this.db=db; this.sec=sec; }

    @Transactional public Map<String,Object> configure(long campus, Map<String,Object> body) {
        sec.admin(); sec.campus(campus);
        String address=Db.required(body,"address",300);
        double latitude=value(body,"latitude",-90,90),longitude=value(body,"longitude",-180,180);
        double radius=value(body,"radiusMeters",50,3000);
        if(radius!=Math.rint(radius)) throw ApiException.bad("签到范围须为50至3000之间的整数米");
        db.one("SELECT id FROM campus WHERE id=? FOR UPDATE",campus);
        db.update("UPDATE campus SET check_in_address=?,check_in_latitude=?,check_in_longitude=?,check_in_radius=? WHERE id=?",address,latitude,longitude,(int)radius,campus);
        sec.auditCampus(campus); sec.audit("CAMPUS_CHECK_IN_LOCATION","campus="+campus+", radius="+(int)radius);
        return Map.of("success",true);
    }

    void verify(long campus, Map<String,Object> body) {
        sec.campus(campus);
        if(Db.number(body,"campusId")!=campus) throw ApiException.bad("学生与签到地点不属于同一校区，请分校区签到");
        var place=db.one("SELECT enabled,check_in_address,check_in_latitude,check_in_longitude,check_in_radius FROM campus WHERE id=? FOR UPDATE",campus);
        if(!Boolean.TRUE.equals(place.get("enabled"))) throw ApiException.bad("该校区已停用，不能签到");
        if(place.get("checkInLatitude")==null||place.get("checkInLongitude")==null||place.get("checkInRadius")==null) throw ApiException.bad("校区尚未设置签到地点，请联系校区管理员");
        if(!(body.get("location") instanceof Map<?,?> position)) throw ApiException.bad("请允许定位后重新签到");
        double latitude=value(position,"latitude",-90,90),longitude=value(position,"longitude",-180,180);
        double accuracy=value(position,"accuracy",0,100000);
        double captured=value(position,"capturedAt",0,Long.MAX_VALUE);
        double age=sec.clock.millis()-captured;
        if(age>120000||age< -30000) throw ApiException.bad("定位已过期，请重新获取当前位置");
        int radius=((Number)place.get("checkInRadius")).intValue();
        if(accuracy>radius) throw ApiException.bad("定位精度不足，请开启精确定位后重试");
        double distance=distance(latitude,longitude,((Number)place.get("checkInLatitude")).doubleValue(),((Number)place.get("checkInLongitude")).doubleValue());
        if(distance>radius) throw ApiException.bad("当前位置距签到地点约"+Math.round(distance)+"米，超出允许的"+radius+"米范围");
    }

    static double value(Map<?,?> body,String key,double min,double max) {
        Object raw=body.get(key);
        if(!(raw instanceof Number number)) throw ApiException.bad("缺少或无效的定位参数 "+key);
        double value=number.doubleValue();
        if(!Double.isFinite(value)||value<min||value>max) throw ApiException.bad("定位参数超出范围 "+key);
        return value;
    }
    static double distance(double a,double b,double c,double d) {
        double lat=Math.toRadians(c-a),lon=Math.toRadians(d-b);
        double h=Math.pow(Math.sin(lat/2),2)+Math.cos(Math.toRadians(a))*Math.cos(Math.toRadians(c))*Math.pow(Math.sin(lon/2),2);
        return 6371000*2*Math.asin(Math.sqrt(Math.min(1,h)));
    }
}
