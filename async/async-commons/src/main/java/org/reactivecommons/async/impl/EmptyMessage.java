package org.reactivecommons.async.impl;

import org.reactivecommons.async.impl.communications.Message;

import java.nio.charset.Charset;
import java.util.Collections;
import java.util.Map;

public class EmptyMessage implements Message {

    @Override
    public byte[] getBody() {
        return new byte[0];
    }

    @Override
    public Properties getProperties() {
        return new Properties() {
            @Override
            public String getContentType() {
                return "application/octet-stream";
            }

            @Override
            public String getContentEncoding() {
                return Charset.defaultCharset().name();
            }

            @Override
            public long getContentLength() {
                return 0;
            }

            @Override
            public Map<String, Object> getHeaders() {
                return Collections.emptyMap();
            }
        };
    }
}
