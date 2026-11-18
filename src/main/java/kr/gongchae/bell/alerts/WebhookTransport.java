package kr.gongchae.bell.alerts;

import java.time.Duration;

/** Posts one prepared message to a validated Discord webhook. Never logs the URL (it carries the webhook token). */
public interface WebhookTransport {
    SendOutcome.Result send(WebhookUrl url, byte[] payload, Duration timeout);
}
