package org.reactivecommons.utils;

import org.modelmapper.ModelMapper;
import org.modelmapper.convention.MatchingStrategies;

public class ObjectMapperI {

    private static final ModelMapper mapper = createObjectModelMapper();
    private static final ModelMapper mMapper = createModelMapper();

    private static ModelMapper createModelMapper(){
        ModelMapper modelMapper = new ModelMapper();
        modelMapper.getConfiguration()
            .setMatchingStrategy(MatchingStrategies.STRICT)
            .setDestinationNamingConvention(LombokBuilderNamingConvention.INSTANCE)
            .setDestinationNameTransformer(LombokBuilderNameTransformer.INSTANCE);
        return modelMapper;
    }

    private static ModelMapper createObjectModelMapper(){
        ModelMapper modelMapper = new ModelMapper();
        modelMapper.getConfiguration()
            .setMatchingStrategy(MatchingStrategies.STRICT);
        return modelMapper;
    }

    public <T> T map(Object src, Class<T> target) {
        return mapper.map(src, target);
    }

    public <T> T mapBuilder(Object src, Class<T> target) {
        return mMapper.map(src, target);
    }

}
