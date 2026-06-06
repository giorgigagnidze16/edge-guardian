package com.edgeguardian.controller.model.converter;

import com.edgeguardian.controller.model.RolloutStrategy;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class RolloutStrategyConverter extends LowercaseEnumConverter<RolloutStrategy> {

    public RolloutStrategyConverter() {
        super(RolloutStrategy.class);
    }
}
