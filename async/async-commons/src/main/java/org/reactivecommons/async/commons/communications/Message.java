package org.reactivecommons.async.commons.communications;

import java.util.Map;

/**
 * Simple Internal Message representation
 * @author Daniel Bustamante Ospina
 */
public interface Message {

    byte[] getBody();
    Properties getProperties();

    interface Properties {
        String getContentType();
        String getContentEncoding();
        long getContentLength();
        Map<String, Object> getHeaders();
    }
}
