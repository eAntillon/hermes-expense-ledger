package dev.eantillon.expenseledger.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.encoder.EncoderBase;
import dev.eantillon.expenseledger.util.Json;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public final class JsonLineEncoder extends EncoderBase<ILoggingEvent> {

    @Override
    public byte[] encode(ILoggingEvent event) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("timestamp", Instant.ofEpochMilli(event.getTimeStamp()).toString());
        record.put("level", event.getLevel().toString());
        record.put("logger", event.getLoggerName());
        record.put("thread", event.getThreadName());
        record.put("message", event.getFormattedMessage());
        if (!event.getMDCPropertyMap().isEmpty()) {
            record.put("context", event.getMDCPropertyMap());
        }
        if (event.getThrowableProxy() != null) {
            record.put("exception", ThrowableProxyUtil.asString(event.getThrowableProxy()));
        }
        return (Json.stringify(record) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public byte[] headerBytes() {
        return null;
    }

    @Override
    public byte[] footerBytes() {
        return null;
    }
}
