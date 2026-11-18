package kr.gongchae.bell.alerts;

import java.time.Duration;
import java.util.UUID;

/** Sends one prepared provider request. Implementations never log the payload, the address or the key. */
public interface MailTransport {
    SendOutcome.Result send(byte[] payload, UUID idempotencyKey, Duration timeout);
}
