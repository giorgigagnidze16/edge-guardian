package com.edgeguardian.controller.model.converter;

import com.edgeguardian.controller.model.OtaDeviceState;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class OtaDeviceStateConverter extends LowercaseEnumConverter<OtaDeviceState> {

    public OtaDeviceStateConverter() {
        super(OtaDeviceState.class);
    }
}
