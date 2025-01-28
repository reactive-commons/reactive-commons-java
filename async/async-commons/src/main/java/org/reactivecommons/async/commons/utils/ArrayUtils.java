package org.reactivecommons.async.commons.utils;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;

import java.util.ArrayList;
import java.util.Arrays;


@AllArgsConstructor(access = AccessLevel.PRIVATE)
public final class ArrayUtils {

    public static <E> Object[] prefixArray(E head, E[] tail) {
        final ArrayList<E> objects = new ArrayList<>(1 + tail.length);
        objects.add(head);
        objects.addAll(Arrays.asList(tail));
        return objects.toArray();
    }
}
