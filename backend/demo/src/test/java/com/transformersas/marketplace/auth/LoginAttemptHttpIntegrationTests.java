package com.transformersas.marketplace.auth;

import com.transformersas.marketplace.support.AbstractIntegrationTest;
import com.transformersas.marketplace.auth.infrastructure.security.AccountUserDetailsService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"app.auth.login-attempts.enabled=true", "app.auth.login-attempts.origin-verified=true",
        "app.auth.login-attempts.trusted-proxy-addresses=172.30.238.10", "server.forward-headers-strategy=native"})
class LoginAttemptHttpIntegrationTests extends AbstractIntegrationTest {
    @org.springframework.test.context.DynamicPropertySource
    static void proxy(org.springframework.test.context.DynamicPropertyRegistry properties) {
        properties.add("server.tomcat.remoteip.internal-proxies", () -> java.util.regex.Pattern.quote("172.30.238.10"));
    }
    @MockitoSpyBean AccountUserDetailsService users;
    @BeforeEach void account() { jdbc.update("DELETE FROM login_attempts"); createAccount("login@example.test","COMPRADOR"); clearInvocations(users); }
    MvcResult login(String email,String password,String ip,boolean forwarded,boolean csrf,int status) throws Exception {
        var response=mvc.perform(get("/api/auth/csrf")).andReturn().getResponse();
        var token=json.readTree(response.getContentAsString());
        var request=post("/api/auth/login").cookie(response.getCookie("SESSION")).param("email",email).param("password",password)
                .with(r->{r.setRemoteAddr(ip);if(forwarded)r.setAttribute("org.apache.tomcat.request.forwarded",Boolean.TRUE);return r;});
        if(csrf)request.header(token.get("headerName").asString(),token.get("token").asString());
        return mvc.perform(request).andExpect(status().is(status)).andReturn();
    }
    @Test void existingAndMissingAccountsHaveSameThresholdAndWaitResponse() throws Exception {
        String first=null;
        for(String email:new String[]{"login@example.test","missing@example.test"}) {
            for(int i=0;i<5;i++) login(email,"wrong","8.8.8.8",true,true,401);
            clearInvocations(users);
            var blocked=login(email,PASSWORD,"8.8.8.8",true,true,429).getResponse();
            assertThat(blocked.getHeader("Retry-After")).isNotNull();
            if(first==null)first=blocked.getContentAsString();else assertThat(blocked.getContentAsString()).isEqualTo(first);
            verify(users,never()).loadUserByUsername(anyString());
        }
    }
    @Test void csrfAndUntrustedOriginNeverConsultCredentialsOrCreateCounter() throws Exception {
        login("login@example.test",PASSWORD,"8.8.8.8",true,false,403);
        login("login@example.test",PASSWORD,"172.30.238.1",true,true,503);
        login("login@example.test",PASSWORD,"8.8.8.8",false,true,503);
        verify(users,never()).loadUserByUsername(anyString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM login_attempts",Integer.class)).isZero();
    }
    @Test void verifiedSuccessResetsButEmailPostCheckDoesNot() throws Exception {
        for(int i=0;i<4;i++)login(" LOGIN@example.test ","wrong","8.8.8.8",true,true,401);
        jdbc.update("UPDATE user_accounts SET email_verified_at=NULL WHERE email='login@example.test'");
        var unverified=login("login@example.test",PASSWORD,"8.8.8.8",true,true,403).getResponse();
        assertThat(unverified.getContentAsString()).contains("EMAIL_NOT_VERIFIED");
        assertThat(jdbc.queryForObject("SELECT failure_4 FROM login_attempts",Long.class)).isNotNull();
        jdbc.update("UPDATE user_accounts SET email_verified_at=CURRENT_TIMESTAMP(6) WHERE email='login@example.test'");
        var success=login("login@example.test",PASSWORD,"8.8.8.8",true,true,204).getResponse();
        Cookie cookie=success.getCookie("SESSION");
        mvc.perform(get("/api/auth/me").cookie(cookie)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT failure_1 FROM login_attempts",Long.class)).isNull();
    }
}
