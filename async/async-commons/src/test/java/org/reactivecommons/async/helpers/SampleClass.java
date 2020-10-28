package org.reactivecommons.async.helpers;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Date;

@RequiredArgsConstructor
@Getter
public class SampleClass {
    private final String id;
    private final String name;
    private final Date date;
}
