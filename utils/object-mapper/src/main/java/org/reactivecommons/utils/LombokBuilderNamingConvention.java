package org.reactivecommons.utils;

import org.modelmapper.spi.NamingConvention;
import org.modelmapper.spi.PropertyType;

class LombokBuilderNamingConvention implements NamingConvention {

    static LombokBuilderNamingConvention INSTANCE = new LombokBuilderNamingConvention();

    @Override
    public boolean applies(String propertyName, PropertyType propertyType) {
        return PropertyType.METHOD.equals(propertyType);
    }

    @Override
    public String toString() {
        return "Lombok @Builder Naming Convention";
    }

}