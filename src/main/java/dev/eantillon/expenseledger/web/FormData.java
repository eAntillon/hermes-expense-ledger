package dev.eantillon.expenseledger.web;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

final class FormData {

    private static final int MAX_BYTES = 16 * 1024;

    private FormData() {
    }

    static Map<String, String> read(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readNBytes(MAX_BYTES + 1);
        if (body.length > MAX_BYTES) {
            throw new IllegalArgumentException("Form exceeds 16 KiB");
        }
        Map<String, String> values = new LinkedHashMap<>();
        String encoded = new String(body, StandardCharsets.UTF_8);
        for (String part : encoded.split("&")) {
            if (part.isEmpty()) {
                continue;
            }
            String[] pair = part.split("=", 2);
            String key = decode(pair[0]);
            String value = pair.length == 2 ? decode(pair[1]) : "";
            values.put(key, value);
        }
        return Map.copyOf(values);
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
