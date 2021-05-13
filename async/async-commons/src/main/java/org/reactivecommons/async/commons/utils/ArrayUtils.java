package org.reactivecommons.async.commons.utils;

import java.util.ArrayList;
import java.util.Arrays;

public class ArrayUtils {

    private ArrayUtils(){}

    public static <E> Object[] prefixArray(E head, E[] tail) {
        final ArrayList<E> objects = new ArrayList<>(1 + tail.length);
        objects.add(head);
        objects.addAll(Arrays.asList(tail));
        return objects.toArray();
    }
}
