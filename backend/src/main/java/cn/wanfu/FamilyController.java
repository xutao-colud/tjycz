package cn.wanfu;

import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class FamilyController {
    final FamilyService family;final Security sec;
    public FamilyController(FamilyService family,Security sec){this.family=family;this.sec=sec;}
    @GetMapping("/family-services") public Object page(@RequestParam long studentId){return family.page(studentId);}
    @GetMapping("/campuses/{id}/family-services") public Object settings(@PathVariable long id){return family.settings(id);}
    @PutMapping("/campuses/{id}/family-services") public Object configure(@PathVariable long id,@RequestBody Map<String,Object> body){return family.configure(id,body);}
    @GetMapping("/renewal-intents") public Object intents(@RequestParam(required=false)Long studentId){return family.intents(studentId);}
    @PutMapping("/accounts/{id}/renewal-intent") public Object intention(@PathVariable long id,@RequestBody Map<String,Object> body){return family.intention(id,body);}
    @GetMapping("/meal-orders") public Object meals(@RequestParam(required=false)String date,@RequestParam(required=false)Long studentId){return family.meals(sec.date(date),studentId);}
    @PutMapping("/students/{id}/meal-order") public Object meal(@PathVariable long id,@RequestBody Map<String,Object> body){return family.meal(id,body);}
    @PutMapping("/students/{id}/avatar") public Object avatar(@PathVariable long id,@RequestBody Map<String,Object> body){return family.avatar(id,body);}
}
