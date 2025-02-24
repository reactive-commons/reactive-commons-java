package org.reactivecommons.async.commons.communications;

import org.reactivecommons.api.domain.RawMessage;

import java.util.Map;

/**
 * Simple Internal Message representation
 *
 * @author Daniel Bustamante Ospina
 */
public interface Message extends RawMessage {

    byte[] getBody();

    Properties getProperties();

    interface Properties {
        String getContentType();

        default String getContentEncoding() {
            return null;
        }

        long getContentLength();

        Map<String, Object> getHeaders();

        default String getKey() {
            return null;
        }

        default String getTopic() {
            return null;
        }
    }
}
