package kr.gongchae.bell.ingestion.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CollectorPropertiesTest {
    private static String key(String configured) {
        return new CollectorProperties(true, configured, 0, 0, false, null, null, null, false).serviceKey();
    }

    @Test
    void bothPortalFormsOfTheKeyBecomeTheSameRawKey() {
        assertThat(key("ab+c/d==")).as("Decoding form, no %").isEqualTo("ab+c/d==");
        assertThat(key("ab%2Bc%2Fd%3D%3D")).as("Encoding form").isEqualTo("ab+c/d==");
        assertThat(key("ab%2bc%2fd%3d%3d")).as("lower-case hex").isEqualTo("ab+c/d==");
    }

    @Test
    void decodesOnceAndKeepsAPlainPlus() {
        assertThat(key("a+b%2F")).as("'+' is not a space").isEqualTo("a+b/");
        assertThat(key("a%252B")).as("one decoding, not two").isEqualTo("a%2B");
        assertThat(key("%ED%95%9C")).as("UTF-8 bytes").isEqualTo("한");
    }

    @Test
    void invalidPercentEscapeStopsStartupWithoutShowingTheKey() {
        for (String bad : new String[]{"secretA%2", "secretB%", "secretC%G1", "secretD%%41", "secretE%FF", "secretF%2-"}) {
            assertThatThrownBy(() -> key(bad)).as(bad)
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("INVALID_SERVICE_KEY");
        }
        assertThat(new CollectorProperties(true, "secret%2B", 0, 0, false, null, null, null, false).toString())
                .doesNotContain("secret");
    }
}
