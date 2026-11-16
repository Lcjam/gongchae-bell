package kr.gongchae.bell.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AlertsSecurityTest {
    static final String KEY_A = Base64.getEncoder().encodeToString(new byte[32]);
    static final String KEY_B = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());
    static final String KEY_C = Base64.getEncoder().encodeToString("fedcba9876543210fedcba9876543210".getBytes());

    static AlertsProperties.Privacy privacy() {
        return new AlertsProperties.Privacy("개인정보 담당(시험)", "test-contact", "test-v1", "2026-11-16", "test-hosting", "test-basis", "test-retention");
    }

    static AlertsProperties enabled() {
        return new AlertsProperties(true, KEY_A, KEY_B, KEY_C, "k1", "erasure.log",
                new AlertsProperties.Mail("re_test_fixture", "공채벨 <alerts@gongchae.example>", null), privacy());
    }

    @Test
    void enabledAlertsRefuseToStartWithoutEveryKeyAndNoticeValueAndNeverPrintThem() {
        assertThat(new AlertsProperties(false, null, null, null, null, null, null, null).enabled()).isFalse();
        var p = enabled();
        assertThat(p.toString()).doesNotContain(KEY_A).doesNotContain("re_test_fixture");
        assertThatThrownBy(() -> new AlertsProperties(true, null, KEY_B, KEY_C, "k1", "e", p.mail(), privacy())).hasMessage("ALERTS_TOKEN_KEY_INVALID");
        assertThatThrownBy(() -> new AlertsProperties(true, "c2hvcnQ=", KEY_B, KEY_C, "k1", "e", p.mail(), privacy())).hasMessage("ALERTS_TOKEN_KEY_INVALID");
        assertThatThrownBy(() -> new AlertsProperties(true, KEY_A, KEY_B, "not base64!", "k1", "e", p.mail(), privacy())).hasMessage("ALERTS_WEBHOOK_KEY_INVALID");
        assertThatThrownBy(() -> new AlertsProperties(true, KEY_A, KEY_B, Base64.getEncoder().encodeToString(new byte[16]), "k1", "e", p.mail(), privacy()))
                .hasMessage("ALERTS_WEBHOOK_KEY_INVALID");
        assertThatThrownBy(() -> new AlertsProperties(true, KEY_A, KEY_B, KEY_C, null, "e", p.mail(), privacy())).hasMessage("ALERTS_WEBHOOK_KEY_ID_REQUIRED");
        assertThatThrownBy(() -> new AlertsProperties(true, KEY_A, KEY_B, KEY_C, "k1", " ", p.mail(), privacy())).hasMessage("ALERTS_ERASURE_LOG_REQUIRED");
        assertThatThrownBy(() -> new AlertsProperties(true, KEY_A, KEY_B, KEY_C, "k1", "e", new AlertsProperties.Mail(null, "x", null), privacy()))
                .hasMessage("ALERTS_MAIL_REQUIRED");
        var noContact = new AlertsProperties.Privacy("officer", " ", "v1", "2026-11-16", "h", "b", "r");
        assertThatThrownBy(() -> new AlertsProperties(true, KEY_A, KEY_B, KEY_C, "k1", "e", p.mail(), noContact)).hasMessage("ALERTS_PRIVACY_NOTICE_REQUIRED");
    }

    @Test
    void capabilitiesVerifyOnlyForTheirPurposeSubscriptionAndVersion() {
        var crypto = new AlertsCrypto(enabled());
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        String manage = crypto.capability(AlertsCrypto.Purpose.MANAGE, a, 1);
        assertThat(crypto.verify(AlertsCrypto.Purpose.MANAGE, a, 1, manage)).isTrue();
        assertThat(crypto.verify(AlertsCrypto.Purpose.UNSUBSCRIBE, a, 1, manage)).as("other purpose").isFalse();
        assertThat(crypto.verify(AlertsCrypto.Purpose.MANAGE, a, 2, manage)).as("token_version raised").isFalse();
        assertThat(crypto.verify(AlertsCrypto.Purpose.MANAGE, b, 1, b + manage.substring(36))).as("another subscription's id").isFalse();
        String tampered = manage.substring(0, manage.length() - 2) + (manage.endsWith("AA") ? "BB" : "AA");
        assertThat(crypto.verify(AlertsCrypto.Purpose.MANAGE, a, 1, tampered)).isFalse();
        assertThat(AlertsCrypto.claimedId("not-a-token")).isEmpty();
        assertThat(AlertsCrypto.claimedId(manage)).contains(a);
        assertThat(crypto.newConfirmToken()).hasSize(43).isNotEqualTo(crypto.newConfirmToken());
    }

    @Test
    void webhookIsSealedWithTheSubscriptionIdAsAadAndNeedsTheConfiguredKey() {
        var crypto = new AlertsCrypto(enabled());
        UUID id = UUID.randomUUID();
        String url = "https://discord.com/api/webhooks/123/abc";
        byte[] sealed = crypto.encryptWebhook(id, url);
        assertThat(new String(sealed)).doesNotContain("abc");
        assertThat(crypto.decryptWebhook(id, sealed, "k1")).isEqualTo(url);
        assertThat(crypto.encryptWebhook(id, url)).as("fresh nonce").isNotEqualTo(sealed);
        assertThatThrownBy(() -> crypto.decryptWebhook(UUID.randomUUID(), sealed, "k1")).hasMessage("WEBHOOK_DECRYPT_FAILED");
        assertThatThrownBy(() -> crypto.decryptWebhook(id, sealed, "k2")).hasMessage("WEBHOOK_KEY_UNKNOWN");
        var disabled = new AlertsCrypto(new AlertsProperties(false, null, null, null, null, null, null, null));
        assertThatThrownBy(() -> disabled.encryptWebhook(id, url)).hasMessage("ALERTS_DISABLED");
        assertThat(crypto.destination("DISCORD", WebhookUrl.parse("https://discordapp.com/api/webhooks/123/abc").orElseThrow().normalized()))
                .isEqualTo(crypto.destination("DISCORD", WebhookUrl.parse(url).orElseThrow().normalized()));
    }

    @Test
    void onlyExactDiscordWebhookUrlsAreAccepted() {
        assertThat(WebhookUrl.parse("https://discord.com/api/webhooks/123456/Ab-c_d.e")).isPresent();
        assertThat(WebhookUrl.parse(" https://DISCORDAPP.com/api/webhooks/1/t ").map(WebhookUrl::normalized))
                .contains("https://discord.com/api/webhooks/1/t");
        assertThat(WebhookUrl.parse("https://discord.com/api/webhooks/1/secret-token").orElseThrow().toString()).doesNotContain("secret");
        for (String bad : new String[]{
                "http://discord.com/api/webhooks/1/t",                 // not https
                "https://discord.com.evil.example/api/webhooks/1/t",   // look-alike host
                "https://evil-discord.com/api/webhooks/1/t",
                "https://discord.com@evil.example/api/webhooks/1/t",   // userinfo
                "https://user:pw@discord.com/api/webhooks/1/t",
                "https://162.159.135.232/api/webhooks/1/t",            // IP literal
                "https://[::1]/api/webhooks/1/t",
                "https://discord.com:443/api/webhooks/1/t",            // explicit port
                "https://discord.com/api/webhooks/abc/t",              // id must be digits
                "https://discord.com/api/webhooks/1/t/extra",
                "https://discord.com/api/webhooks/1/t?wait=false",     // query
                "https://discord.com/api/webhooks/1/t#x",
                "https://dіscord.com/api/webhooks/1/t",                // Cyrillic i
                "https://discord.com/api/v10/webhooks/1/t",
                "", "discord.com/api/webhooks/1/t"}) {
            assertThat(WebhookUrl.parse(bad)).as(bad).isEmpty();
        }
    }

    @Test
    void everyResolvedAddressMustBePublic() throws Exception {
        assertThat(guard("162.159.135.232").allows("discord.com")).isTrue();
        assertThat(guard("2606:4700::6810:84e5").allows("discord.com")).isTrue();
        for (String internal : new String[]{"127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.0.10", "169.254.169.254", "0.0.0.0",
                "100.64.0.1", "198.18.0.1", "255.255.255.255", "224.0.0.1", "::1", "fe80::1", "fc00::1", "fd12::1", "::ffff:10.0.0.1"}) {
            assertThat(guard(internal).allows("discord.com")).as(internal).isFalse();
        }
        assertThat(guard("162.159.135.232", "10.0.0.1").allows("discord.com")).as("one private answer is enough to refuse").isFalse();
        assertThat(new AddressGuard(host -> { throw new UnknownHostException(); }).allows("discord.com")).isFalse();
        assertThat(new AddressGuard(host -> new InetAddress[0]).allows("discord.com")).isFalse();
    }

    private static AddressGuard guard(String... literals) throws Exception {
        InetAddress[] addresses = new InetAddress[literals.length];
        for (int i = 0; i < literals.length; i++) addresses[i] = InetAddress.getByName(literals[i]);
        return new AddressGuard(host -> addresses);
    }
}
