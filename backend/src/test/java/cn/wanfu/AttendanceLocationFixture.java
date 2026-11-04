package cn.wanfu;

import java.util.*;

// 旧业务用例补齐定位前置条件；范围拒绝及权限用例在AttendanceLocationIntegrationTest单独验证。
final class AttendanceLocationFixture {
    static Object body(Db db,Security sec,Object input) {
        var body=new LinkedHashMap<String,Object>(); ((Map<?,?>)input).forEach((k,v)->body.put(k.toString(),v));
        long campus=body.containsKey("campusId")?Db.number(body,"campusId"):Db.id(db.one("SELECT cl.campus_id FROM student s JOIN school_class cl ON cl.id=s.class_id WHERE s.id=?",Db.ids(body,"studentIds").get(0)),"campusId");
        db.update("UPDATE campus SET check_in_address='虚构业务验收地点',check_in_latitude=31.23,check_in_longitude=121.47,check_in_radius=200 WHERE id=? AND check_in_radius IS NULL",campus);
        var place=db.one("SELECT check_in_latitude,check_in_longitude FROM campus WHERE id=?",campus);
        body.put("campusId",campus); body.put("location",Map.of("latitude",place.get("checkInLatitude"),"longitude",place.get("checkInLongitude"),"accuracy",5,"capturedAt",sec.clock.millis()));
        return body;
    }
}
