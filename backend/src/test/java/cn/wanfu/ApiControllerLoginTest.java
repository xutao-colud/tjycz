package cn.wanfu;

import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApiControllerLoginTest {
    Db db;
    Security security;
    ApiController controller;
    PasswordTransportService transport;
    Map<String,Object> admin;

    @BeforeEach void nonLocalLogin() {
        db=mock(Db.class);
        var env=new MockEnvironment();
        env.setActiveProfiles("prod");
        security=new Security(db,Clock.system(Application.ZONE),env);
        transport=new PasswordTransportService(Clock.systemUTC());
        admin=Map.of("id",1L,"name","管理员","role","ADMIN","passwordHash",security.passwords.encode("Admin123!"));
        when(db.optional("SELECT * FROM app_user WHERE username=?","admin")).thenReturn(admin);
        controller=new ApiController(db,security,null,null,null,null,null,null,transport,null);
    }

    Map<String,Object> encrypt(Map<String,Object> body) {
        return PasswordTransportTestClient.encrypt(transport.challenge("LOGIN"),"LOGIN",body);
    }

    @Test void sharedDemoCredentialsAreRejectedOutsideLocal() {
        assertThatThrownBy(()->controller.login(encrypt(Map.of("username","admin","password","admin","role","PARENT"))))
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status).isEqualTo(401));
    }

    @Test void validCredentialsKeepStoredRoleRegardlessOfRequestedRoleOutsideLocal() {
        for(Object role:List.of("PARENT","INVALID",1)) {
            Map<?,?> response=(Map<?,?>)controller.login(encrypt(Map.of("username","admin","password","Admin123!","role",role)));
            assertThat(response.get("demo")).isEqualTo(false);
            assertThat(response.get("token")).isInstanceOf(String.class).isNotEqualTo("");
            assertThat(response.get("user")).isEqualTo(Map.of("id",1L,"name","管理员","role","ADMIN","isSuperAdmin",false,"campusIds",List.of(),"campuses",List.of(),"classIds",List.of(),"studentIds",List.of()));
        }
        var teacher=Map.<String,Object>of("id",2L,"name","老师","role","TEACHER","passwordHash",security.passwords.encode("Teacher123!"));
        when(db.optional("SELECT * FROM app_user WHERE username=?","teacher")).thenReturn(teacher);
        Map<?,?> response=(Map<?,?>)controller.login(encrypt(Map.of("username","teacher","password","Teacher123!","role","ADMIN")));
        assertThat(response.get("demo")).isEqualTo(false);
        assertThat(response.get("user")).isEqualTo(Map.of("id",2L,"name","老师","role","TEACHER","isSuperAdmin",false,"campusIds",List.of(),"campuses",List.of(),"classIds",List.of(),"studentIds",List.of()));
    }

    @Test void plaintextIsRejectedEvenAlongsideCiphertextOrWithNullValue() {
        for(Object plain:Arrays.asList("Admin123!",null)) {
            var body=encrypt(Map.of("username","admin","password","Admin123!"));
            body.put("password",plain);
            assertThatThrownBy(()->controller.login(body)).isInstanceOfSatisfying(ApiException.class,e->{
                assertThat(e.status).isEqualTo(400);
                assertThat(e.getMessage()).doesNotContain("Admin123!");
            });
            body.remove("password");
            assertThatThrownBy(()->controller.login(body)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status).isEqualTo(400));
        }
        assertThatThrownBy(()->controller.login(Map.of("username","admin","password","Admin123!")))
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status).isEqualTo(400));
        verify(db,never()).optional(anyString(),any(Object.class));
    }

    @Test void usernameIsTrimmedButPasswordWhitespaceIsPreserved() {
        String password="  Admin123!  ";
        when(db.optional("SELECT * FROM app_user WHERE username=?","admin")).thenReturn(Map.of(
            "id",1L,"name","管理员","role","ADMIN","passwordHash",security.passwords.encode(password)));
        assertThat(((Map<?,?>)controller.login(encrypt(Map.of("username","  admin  ","password",password)))).get("token")).isInstanceOf(String.class);
        assertThatThrownBy(()->controller.login(encrypt(Map.of("username","admin","password",password.trim()))))
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status).isEqualTo(401));
    }
}
