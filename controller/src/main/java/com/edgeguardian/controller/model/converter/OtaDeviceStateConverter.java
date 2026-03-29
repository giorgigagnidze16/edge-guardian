package com.edgeguardian.controller.model.converter;

import com.edgeguardian.controller.model.OtaDeviceState;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class OtaDeviceStateConverter implements AttributeConverter<OtaDeviceState, String> {

    @Override
    public String convertToDatabaseColumn(OtaDeviceState state) {
        return state == null ? null : state.toDbValue();
    }

    @Override
    public OtaDeviceState convertToEntityAttribute(String dbValue) {
        return OtaDeviceState.fromDbValue(dbValue);
    }
}
