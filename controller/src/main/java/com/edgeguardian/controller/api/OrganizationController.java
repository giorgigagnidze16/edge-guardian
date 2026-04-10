package com.edgeguardian.controller.api;

import com.edgeguardian.controller.dto.*;
import com.edgeguardian.controller.model.AuditLog;
import com.edgeguardian.controller.model.OrgRole;
import com.edgeguardian.controller.model.Organization;
import com.edgeguardian.controller.model.OrganizationMember;
import com.edgeguardian.controller.model.User;
import com.edgeguardian.controller.repository.UserRepository;
import com.edgeguardian.controller.security.TenantPrincipal;
import com.edgeguardian.controller.service.AuditService;
import com.edgeguardian.controller.service.OrganizationService;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/organizations")
@RequiredArgsConstructor
public class OrganizationController {

    private final OrganizationService organizationService;
    private final UserRepository userRepository;
    private final AuditService auditService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrganizationDto create(@RequestBody CreateOrganizationRequest request,
                                  @AuthenticationPrincipal TenantPrincipal principal) {
        Organization org = organizationService.create(
                request.name(), request.slug(), request.description(), principal.userId());
        return OrganizationDto.from(org);
    }

    @GetMapping("/{orgId}")
    public OrganizationDto get(@PathVariable Long orgId,
                               @AuthenticationPrincipal TenantPrincipal principal) {
        organizationService.requireRole(orgId, principal.userId(),
                OrgRole.owner, OrgRole.admin, OrgRole.operator, OrgRole.viewer);
        return organizationService.findById(orgId)
                .map(OrganizationDto::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @PutMapping("/{orgId}")
    public OrganizationDto update(@PathVariable Long orgId,
                                  @RequestBody UpdateOrganizationRequest request,
                                  @AuthenticationPrincipal TenantPrincipal principal) {
        organizationService.requireRole(orgId, principal.userId(), OrgRole.owner, OrgRole.admin);
        return OrganizationDto.from(
                organizationService.update(orgId, request.name(), request.description()));
    }

    @DeleteMapping("/{orgId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long orgId,
                       @AuthenticationPrincipal TenantPrincipal principal) {
        organizationService.requireRole(orgId, principal.userId(), OrgRole.owner);
        organizationService.delete(orgId);
    }

    // --- Members ---

    @GetMapping("/{orgId}/members")
    public List<MemberDto> listMembers(@PathVariable Long orgId,
                                       @AuthenticationPrincipal TenantPrincipal principal) {
        organizationService.requireRole(orgId, principal.userId(),
                OrgRole.owner, OrgRole.admin, OrgRole.operator, OrgRole.viewer);
        List<OrganizationMember> members = organizationService.getMembers(orgId);
        List<Long> userIds = members.stream().map(OrganizationMember::getUserId).toList();
        Map<Long, User> usersById = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        return members.stream()
                .map(m -> MemberDto.from(m, usersById.get(m.getUserId())))
                .toList();
    }

    @PostMapping("/{orgId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public MemberDto addMember(@PathVariable Long orgId,
                               @RequestBody AddMemberRequest request,
                               @AuthenticationPrincipal TenantPrincipal principal) {
        organizationService.requireRole(orgId, principal.userId(), OrgRole.owner, OrgRole.admin);
        OrganizationMember member = organizationService.addMember(
                orgId, request.userId(), OrgRole.valueOf(request.role()));
        User user = userRepository.findById(member.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        return MemberDto.from(member, user);
    }

    @DeleteMapping("/{orgId}/members/{memberId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@PathVariable Long orgId, @PathVariable Long memberId,
                             @AuthenticationPrincipal TenantPrincipal principal) {
        organizationService.requireRole(orgId, principal.userId(), OrgRole.owner, OrgRole.admin);
        organizationService.removeMember(orgId, memberId);
    }

    // --- Audit Log ---

    public record AuditLogDto(Long id, Long userId, String userEmail, String action,
                               String resourceType, String resourceId,
                               Map<String, Object> details, java.time.Instant createdAt) {
        static AuditLogDto from(AuditLog entry, String email) {
            return new AuditLogDto(entry.getId(), entry.getUserId(), email,
                    entry.getAction(), entry.getResourceType(), entry.getResourceId(),
                    entry.getDetails(), entry.getCreatedAt());
        }
    }

    @GetMapping("/{orgId}/audit-log")
    public List<AuditLogDto> listAuditLog(
            @PathVariable Long orgId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @AuthenticationPrincipal TenantPrincipal principal) {
        organizationService.requireRole(orgId, principal.userId(),
                OrgRole.owner, OrgRole.admin, OrgRole.operator, OrgRole.viewer);
        List<AuditLog> entries = auditService.findByOrganization(orgId, PageRequest.of(page, size)).getContent();
        List<Long> userIds = entries.stream().map(AuditLog::getUserId).filter(id -> id != null).distinct().toList();
        Map<Long, User> usersById = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        return entries.stream()
                .map(e -> AuditLogDto.from(e, e.getUserId() != null && usersById.containsKey(e.getUserId())
                        ? usersById.get(e.getUserId()).getEmail() : null))
                .toList();
    }

}
