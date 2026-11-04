package cn.wanfu;

import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class AttendanceSafetyController {
    final AttendanceSafetyService safety; final Security sec;
    public AttendanceSafetyController(AttendanceSafetyService safety,Security sec) { this.safety=safety; this.sec=sec; }
    @GetMapping("/leaves") public Object leaves(@RequestParam(required=false) String date,@RequestParam(required=false) Long studentId) { return safety.leaves(sec.date(date),studentId); }
    @GetMapping("/leaves/{id}") public Object leave(@PathVariable long id) { return safety.leave(id); }
    @PostMapping("/leaves") public Object createLeave(@RequestBody Map<String,Object> b) { return safety.createLeave(b); }
    @PostMapping("/leaves/{id}/review") public Object review(@PathVariable long id,@RequestBody Map<String,Object> b) { return safety.reviewLeave(id,b); }
    @PostMapping("/leaves/{id}/cancel") public Object cancel(@PathVariable long id,@RequestBody Map<String,Object> b) { return safety.cancelLeave(id,b); }
    @GetMapping("/pickup-contacts") public Object contacts(@RequestParam long studentId) { return safety.contacts(studentId); }
    @PostMapping("/pickup-contacts") public Object createContact(@RequestBody Map<String,Object> b) { return safety.createContact(b); }
    @PatchMapping("/pickup-contacts/{id}") public Object updateContact(@PathVariable long id,@RequestBody Map<String,Object> b) { return safety.updateContact(id,b); }
    @DeleteMapping("/pickup-contacts/{id}") public Object deleteContact(@PathVariable long id,@RequestBody Map<String,Object> b) { return safety.deleteContact(id,b); }
    @PostMapping("/pickup-contacts/{id}/default") public Object defaultContact(@PathVariable long id,@RequestBody Map<String,Object> b) { return safety.setDefaultContact(id,b); }
    @GetMapping("/pickups") public Object pickups(@RequestParam(required=false) String date,@RequestParam(required=false) Long studentId) { return safety.pickups(date==null||date.isBlank()?null:sec.date(date),studentId); }
    @GetMapping("/pickups/{id}") public Object pickup(@PathVariable long id) { return safety.pickup(id); }
    @PostMapping("/pickups") public Object createPickup(@RequestBody Map<String,Object> b) { return safety.createPickup(b); }
    @PostMapping("/pickups/{id}/revoke") public Object revoke(@PathVariable long id,@RequestBody Map<String,Object> b) { return safety.revokePickup(id,b,false); }
    @PostMapping("/pickups/{id}/rotate-code") public Object rotate(@PathVariable long id,@RequestBody Map<String,Object> b) { return safety.revokePickup(id,b,true); }
    @PostMapping("/pickups/{id}/verify") public ResponseEntity<?> verify(@PathVariable long id,@RequestBody Map<String,Object> b) {
        var result=safety.verifyPickup(id,b); return ResponseEntity.status(result.containsKey("status")?((Number)result.get("status")).intValue():200).body(result);
    }
}
