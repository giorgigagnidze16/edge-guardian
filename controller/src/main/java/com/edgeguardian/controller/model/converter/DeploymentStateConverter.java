package com.edgeguardian.controller.model.converter;

import com.edgeguardian.controller.model.DeploymentState;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class DeploymentStateConverter extends LowercaseEnumConverter<DeploymentState> {

    public DeploymentStateConverter() {
        super(DeploymentState.class);
    }
}
