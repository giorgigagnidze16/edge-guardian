package com.edgeguardian.controller.model.converter;

import com.edgeguardian.controller.model.DeploymentState;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class DeploymentStateConverter implements AttributeConverter<DeploymentState, String> {

    @Override
    public String convertToDatabaseColumn(DeploymentState state) {
        return state == null ? null : state.toDbValue();
    }

    @Override
    public DeploymentState convertToEntityAttribute(String dbValue) {
        return DeploymentState.fromDbValue(dbValue);
    }
}
