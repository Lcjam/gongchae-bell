package kr.gongchae.bell.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import kr.gongchae.bell.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/** Default configuration (alerts off): nothing is collected, the policy shows its unset values as such. */
class AlertsDisabledIntegrationTest extends IntegrationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    @Test
    void withAlertsOffNothingIsCollectedAndThePolicySaysWhatIsStillUnset() throws Exception {
        mvc.perform(get("/subscriptions/new")).andExpect(status().isOk()).andExpect(content().string(containsString("알림 신청은 준비 중입니다")));
        // agy R-3: the link pages say the same instead of showing a form whose POST would be refused
        mvc.perform(get("/subscriptions/confirm").param("t", "x")).andExpect(status().isServiceUnavailable())
                .andExpect(content().string(containsString("알림 신청은 준비 중입니다")));
        mvc.perform(get("/subscriptions/unsubscribe").param("t", "x")).andExpect(status().isServiceUnavailable())
                .andExpect(content().string(containsString("알림 신청은 준비 중입니다")));
        mvc.perform(post("/subscriptions/email").header("Origin", "http://localhost:8080").param("email", "a@example.com")
                .param("notifyNew", "true").param("consent", "true").param("age14", "true")).andExpect(status().isServiceUnavailable());
        assertThat(jdbc.sql("SELECT count(*) FROM subscription").query(Integer.class).single()).isZero();
        mvc.perform(get("/privacy")).andExpect(status().isOk())
                .andExpect(content().string(containsString("미정 (운영 시작 전 설정)")))
                .andExpect(content().string(containsString("개인정보를 수집하지 않습니다")));
        mvc.perform(get("/jobs")).andExpect(status().isOk())
                .andExpect(content().string(containsString("이 조건으로 알림 받기 (준비 중)")))
                .andExpect(content().string(not(containsString("/subscriptions/new?"))));
    }
}
