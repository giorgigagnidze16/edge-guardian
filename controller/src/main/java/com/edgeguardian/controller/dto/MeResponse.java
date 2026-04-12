package com.edgeguardian.controller.dto;

import java.util.List;

public record MeResponse(
        UserDto user,
        List<OrgMembershipDto> organizations
) {
}
