package cn.wanfu;

import java.sql.*;
import java.time.*;
import java.util.*;
import java.math.BigDecimal;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.support.*;
import org.springframework.stereotype.Component;

@Component
public class Db {
    final JdbcTemplate jdbc;
    public Db(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public List<Map<String,Object>> list(String sql,Object... args) {
        return jdbc.query(sql,(rs,row)-> {
            Map<String,Object> out=new LinkedHashMap<>(); var meta=rs.getMetaData();
            for(int i=1;i<=meta.getColumnCount();i++) {
                String key=meta.getColumnLabel(i).toLowerCase(Locale.ROOT);
                StringBuilder name=new StringBuilder(); boolean upper=false;
                for(char c:key.toCharArray()) { if(c=='_') upper=true; else { name.append(upper?Character.toUpperCase(c):c); upper=false; } }
                Object v=rs.getObject(i);
                if(v instanceof Timestamp t) v=t.toLocalDateTime();
                if(v instanceof java.sql.Date d) v=d.toLocalDate();
                out.put(name.toString(),v);
            }
            return out;
        },args);
    }
    public Map<String,Object> one(String sql,Object... args) { var rows=list(sql,args); if(rows.isEmpty()) throw ApiException.missing(); return rows.get(0); }
    public Map<String,Object> optional(String sql,Object... args) { var rows=list(sql,args); return rows.isEmpty()?null:rows.get(0); }
    public int update(String sql,Object... args) { return jdbc.update(sql,args); }
    public long insert(String sql,Object... args) {
        KeyHolder keys=new GeneratedKeyHolder();
        jdbc.update(c->{var ps=c.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS); for(int i=0;i<args.length;i++) ps.setObject(i+1,args[i]); return ps;},keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }
    public long count(String sql,Object... args) { Long n=jdbc.queryForObject(sql,Long.class,args); return n==null?0:n; }
    public static long id(Map<String,Object> row,String key) { return ((Number)row.get(key)).longValue(); }
    public static int integer(Map<String,Object> b,String key,int fallback) { Object v=b.get(key); return v==null?fallback:new BigDecimal(v.toString()).intValueExact(); }
    public static long number(Map<String,Object> b,String key) { Object v=b.get(key); if(v==null) throw ApiException.bad("缺少 "+key); return new BigDecimal(v.toString()).longValueExact(); }
    public static String text(Map<String,Object> b,String key) { Object v=b.get(key); return v==null?"":v.toString().trim(); }
    public static String required(Map<String,Object> b,String key,int max) { String s=text(b,key); if(s.isBlank()||s.length()>max) throw ApiException.bad(key+"不能为空且长度不能超过"+max); return s; }
    public static BigDecimal decimal(Map<String,Object> b,String key) { BigDecimal d=new BigDecimal(b.getOrDefault(key,0).toString()); if(d.scale()>2||d.abs().compareTo(new BigDecimal("999999999"))>0) throw ApiException.bad("课时最多两位小数且不能超出范围"); return d; }
    public static List<Long> ids(Map<String,Object> b,String key) { Object v=b.get(key); if(!(v instanceof List<?> l)||l.isEmpty()||l.size()>200) throw ApiException.bad(key+"须包含1至200个ID"); return l.stream().map(x->new BigDecimal(x.toString()).longValueExact()).distinct().sorted().toList(); }
    public static List<Long> optionalIds(Map<String,Object> b,String key) { if(!b.containsKey(key)||b.get(key)==null) return List.of(); if(!(b.get(key) instanceof List<?> list)) throw ApiException.bad(key+"须为ID数组"); if(list.isEmpty()) return List.of(); return ids(b,key); }
    public static void choice(String value,String... choices) { if(!Arrays.asList(choices).contains(value)) throw ApiException.bad("无效值: "+value); }
}
