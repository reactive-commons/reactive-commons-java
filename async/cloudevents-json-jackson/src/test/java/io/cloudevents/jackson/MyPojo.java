package io.cloudevents.jackson;

import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Objects;

@Getter
@NoArgsConstructor
public class MyPojo {
    public int a;
    public String b;

    public MyPojo(int a, String b) {
        this.a = a;
        this.b = b;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MyPojo myPojo = (MyPojo) o;
        return getA() == myPojo.getA() &&
            Objects.equals(b, myPojo.b);
    }

    @Override
    public int hashCode() {
        return Objects.hash(getA(), b);
    }
}
