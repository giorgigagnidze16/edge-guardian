package com.edgeguardian.controller.api;

import com.edgeguardian.controller.dto.MeResponse;
import com.edgeguardian.controller.dto.OrgMembershipDto;
import com.edgeguardian.controller.dto.UserDto;
import com.edgeguardian.controller.model.Organization;
import com.edgeguardian.controller.model.OrganizationMember;
import com.edgeguardian.controller.model.User;
import com.edgeguardian.controller.repository.OrganizationMemberRepository;
import com.edgeguardian.controller.security.TenantPrincipal;
import com.edgeguardian.controller.service.OrganizationService;
import com.edgeguardian.controller.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
public class MeController {

    private final UserService userService;
    private final OrganizationService organizationService;
    private final OrganizationMemberRepository memberRepository;

    @GetMapping
    public MeResponse me(@AuthenticationPrincipal TenantPrincipal principal) {
        User user = userService.findById(principal.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        UserDto userDto = UserDto.from(user);

        List<OrganizationMember> memberships = memberRepository.findByUserId(user.getId());
        List<Organization> orgs = organizationService.findByUser(user.getId());
        Map<Long, Organization> orgMap = orgs.stream()
                .collect(Collectors.toMap(Organization::getId, Function.identity()));

        List<OrgMembershipDto> orgDtos = new ArrayList<>();
        for (OrganizationMember m : memberships) {
            Organization org = orgMap.get(m.getOrganizationId());
            if (org != null) {
                orgDtos.add(new OrgMembershipDto(
                        org.getId(), org.getName(), org.getSlug(), m.getOrgRole().name()));
            }
        }

        return new MeResponse(userDto, orgDtos);
    }
}
