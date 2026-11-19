package kr.gongchae.bell.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class HeartbeatTest {
    static final String SECRET = "/ping/0b9c-secret-check-id";
    HttpServer server;
    final List<String> requests = new CopyOnWriteArrayList<>();
    volatile int status = 200;
    final Logger logger = (Logger) LoggerFactory.getLogger(Heartbeat.class);
    final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", e -> {
            requests.add(e.getRequestMethod() + " " + e.getRequestURI());
            if (status == 302) e.getResponseHeaders().add("Location", SECRET + "/elsewhere");
            e.sendResponseHeaders(status, -1);
            e.close();
        });
        server.start();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach void stop() {
        logger.detachAppender(logs);
        server.stop(0);
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + SECRET;
    }

    List<String> messages() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test void blankUrlIsOffAndSendsNothing() {
        new Heartbeat("collector", null).ping();
        new Heartbeat("collector", "  ").ping();
        assertThat(requests).isEmpty();
        assertThat(messages()).isEmpty();
    }

    @Test void successSendsOneGetToTheCheck() {
        new Heartbeat("collector", url()).ping();
        assertThat(requests).containsExactly("GET " + SECRET);
        assertThat(messages()).isEmpty();
    }

    @Test void failuresAreLoggedWithoutTheUrlAndNeverThrow() {
        var heartbeat = new Heartbeat("collector", url());
        status = 500;
        heartbeat.ping();
        status = 302;
        heartbeat.ping();
        server.stop(0);
        heartbeat.ping();
        // The redirect is not followed: two requests reached the server, the third found it gone.
        assertThat(requests).hasSize(2);
        assertThat(messages()).containsExactly("HEARTBEAT_FAILED collector HTTP_500", "HEARTBEAT_FAILED collector HTTP_302",
                "HEARTBEAT_FAILED collector ConnectException");
        assertThat(String.join("\n", messages())).doesNotContain("secret");
    }

    @Test void invalidUrlStopsStartupWithoutEchoingIt() {
        for (String bad : new String[]{"ftp://hc-ping.example" + SECRET, "hc-ping.example" + SECRET, "https://" + SECRET + " x"}) {
            assertThatThrownBy(() -> new Heartbeat("collector", bad))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("INVALID_HEARTBEAT_URL");
        }
    }
}
