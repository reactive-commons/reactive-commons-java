package org.reactivecommons.utils;

import org.modelmapper.internal.util.Strings;
import org.modelmapper.spi.NameTransformer;
import org.modelmapper.spi.NameableType;

class LombokBuilderNameTransformer implements NameTransformer {

    public static final NameTransformer INSTANCE = new LombokBuilderNameTransformer();

    @Override
    public String transform(final String name, final NameableType nameableType) {
        return Strings.decapitalize(name);
    }

    @Override
    public String toString() {
        return "Lombok @Builder Mutator";
    }
}