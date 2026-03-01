package com.edgeguardian.controller.api;

import com.edgeguardian.controller.dto.*;
import com.edgeguardian.controller.model.OrgRole;
import com.edgeguardian.controller.model.Organization;
import com.edgeguardian.controller.model.OrganizationMember;
import com.edgeguardian.controller.security.TenantContext;
import com.edgeguardian.controller.service.OrganizationService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/v1/organizations")
public class OrganizationController {

    private final OrganizationService organizationService;

    public OrganizationController(OrganizationService organizationService) {
        this.organizationService = organizationService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrganizationDto create(@RequestBody CreateOrganizationRequest request) {
        Long userId = requireUserId();
        Organization org = organizationService.create(
                request.name(), request.slug(), request.description(), userId);
        return OrganizationDto.from(org);
    }

    @GetMapping("/{orgId}")
    public OrganizationDto get(@PathVariable Long orgId) {
        Long userId = requireUserId();
        organizationService.requireRole(orgId, userId,
                OrgRole.owner, OrgRole.admin, OrgRole.operator, OrgRole.viewer);
        return organizationService.findById(orgId)
                .map(OrganizationDto::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @PutMapping("/{orgId}")
    public OrganizationDto update(@PathVariable Long orgId,
                                  @RequestBody UpdateOrganizationRequest request) {
        Long userId = requireUserId();
        organizationService.requireRole(orgId, userId, OrgRole.owner, OrgRole.admin);
        return OrganizationDto.from(
                organizationService.update(orgId, request.name(), request.description()));
    }

    @DeleteMapping("/{orgId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long orgId) {
        Long userId = requireUserId();
        organizationService.requireRole(orgId, userId, OrgRole.owner);
        organizationService.delete(orgId);
    }

    // --- Members ---

    @GetMapping("/{orgId}/members")
    public List<MemberDto> listMembers(@PathVariable Long orgId) {
        Long userId = requireUserId();
        organizationService.requireRole(orgId, userId,
                OrgRole.owner, OrgRole.admin, OrgRole.operator, OrgRole.viewer);
        return organizationService.getMembers(orgId).stream()
                .map(MemberDto::from)
                .toList();
    }

    @PostMapping("/{orgId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public MemberDto addMember(@PathVariable Long orgId,
                               @RequestBody AddMemberRequest request) {
        Long userId = requireUserId();
        organizationService.requireRole(orgId, userId, OrgRole.owner, OrgRole.admin);
        OrganizationMember member = organizationService.addMember(
                orgId, request.userId(), OrgRole.valueOf(request.role()));
        return MemberDto.from(member);
    }

    @DeleteMapping("/{orgId}/members/{memberId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@PathVariable Long orgId, @PathVariable Long memberId) {
        Long userId = requireUserId();
        organizationService.requireRole(orgId, userId, OrgRole.owner, OrgRole.admin);
        organizationService.removeMember(orgId, memberId);
    }

    private Long requireUserId() {
        Long userId = TenantContext.getUserId();
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not authenticated");
        }
        return userId;
    }
}
