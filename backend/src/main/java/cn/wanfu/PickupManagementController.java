package cn.wanfu;

import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class PickupManagementController {
    final PickupAlertService alerts; final Security sec;
    public PickupManagementController(PickupAlertService alerts,Security sec) { this.alerts=alerts; this.sec=sec; }
    @GetMapping("/pickup-alerts") public Object alerts(@RequestParam(required=false) String date,@RequestParam(required=false) Long studentId) { return alerts.list(sec.date(date),studentId); }
    @GetMapping("/pickup-alerts/{id}") public Object alert(@PathVariable long id) { return alerts.detail(id); }
    @PostMapping("/pickup-alerts") public Object create(@RequestBody Map<String,Object> body) { return alerts.create(body); }
    @PostMapping("/pickup-alerts/{id}/handle") public Object handle(@PathVariable long id,@RequestBody Map<String,Object> body) { return alerts.handle(id,body); }
    @GetMapping("/pickup-face/status") public Object faceStatus() {
        sec.actor(); return ResponseEntity.ok().header("Cache-Control","no-store").body(Map.of("providerConfigured",false,"status","DISABLED","captureEnabled",false,"manualVerificationAvailable",true,"message","接送采用接送码与老师现场核对，不采集人脸。"));
    }
    @PostMapping("/pickup-face/verify") public Object verifyFace(@RequestBody(required=false) Map<String,Object> body) {
        sec.actor(); if(body!=null&&body.get("studentId")!=null) sec.student(Db.number(body,"studentId"));
        return ResponseEntity.status(410).header("Cache-Control","no-store").body(Map.of("status","DISABLED","code","FACE_VERIFICATION_DISABLED","message","接送人脸核验已停用，请使用接送码和老师现场核对。"));
    }
}
