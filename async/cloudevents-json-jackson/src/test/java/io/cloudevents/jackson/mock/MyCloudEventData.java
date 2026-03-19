package io.cloudevents.jackson.mock;

import io.cloudevents.CloudEventData;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

public record MyCloudEventData(int value) implements CloudEventData {

    @Override
    public byte[] toBytes() {
        return Integer.toString(value).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MyCloudEventData that = (MyCloudEventData) o;
        return value() == that.value();
    }

    @Override
    public int hashCode() {
        return Objects.hash(value());
    }

    public static MyCloudEventData fromStringBytes(byte[] bytes) {
        return new MyCloudEventData(Integer.parseInt(new String(bytes)));
    }

}
