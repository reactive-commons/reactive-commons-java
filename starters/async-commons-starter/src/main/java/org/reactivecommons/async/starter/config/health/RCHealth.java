package org.reactivecommons.async.starter.config.health;

import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.HashMap;
import java.util.Map;

@Getter
@Builder
@RequiredArgsConstructor
public class RCHealth {
    private final Status status;
    private final Map<String, Object> details;

    public enum Status {
        UP,
        DOWN
    }

    public static class RCHealthBuilder {
        public RCHealthBuilder() {
            this.details = new HashMap<>();
        }

        public RCHealthBuilder up() {
            this.status = Status.UP;
            return this;
        }

        public RCHealthBuilder down() {
            this.status = Status.DOWN;
            return this;
        }

        public RCHealthBuilder withDetail(String key, Object value) {
            this.details.put(key, value);
            return this;
        }

        public RCHealth build() {
            return new RCHealth(this.status, this.details);
        }
    }
}
