package org.reactivecommons.async.rabbit.converters.json;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@AllArgsConstructor
@NoArgsConstructor
class SampleClass {
    private String id;
    private String name;
    private Date date;
}
