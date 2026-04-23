package com.edgeguardian.controller.api;

import com.edgeguardian.controller.dto.AddMemberRequest;
import com.edgeguardian.controller.dto.AuditLogDto;
import com.edgeguardian.controller.dto.MemberDto;
import com.edgeguardian.controller.dto.OrganizationDto;
import com.edgeguardian.controller.dto.UpdateMemberRoleRequest;
import com.edgeguardian.controller.dto.UpdateOrganizationRequest;
import com.edgeguardian.controller.model.AuditLog;
import com.edgeguardian.controller.model.OrgRole;
import com.edgeguardian.controller.model.OrganizationMember;
import com.edgeguardian.controller.model.User;
import com.edgeguardian.controller.repository.UserRepository;
import com.edgeguardian.controller.security.TenantPrincipal;
import com.edgeguardian.controller.service.AuditService;
import com.edgeguardian.controller.service.OrganizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping(ApiPaths.ORGANIZATION_BASE)
@RequiredArgsConstructor
public class OrganizationController {

    private final AuditService auditService;
    private final UserRepository userRepository;
    private final OrganizationService organizationService;

    @GetMapping
    @PreAuthorize("@orgSecurity.hasMinRole(authentication, 'VIEWER')")
    public OrganizationDto get(@AuthenticationPrincipal TenantPrincipal principal) {
        return organizationService.findById(principal.organizationId())
                .map(OrganizationDto::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @PutMapping
    @PreAuthorize("@orgSecurity.hasMinRole(authentication, 'ADMIN')")
    public OrganizationDto update(@RequestBody UpdateOrganizationRequest request,
                                  @AuthenticationPrincipal TenantPrincipal principal) {
        return OrganizationDto.from(
                organizationService.update(principal.organizationId(), request.name(), request.description()));
    }

    @DeleteMapping
    @PreAuthorize("@orgSecurity.hasMinRole(authentication, 'OWNER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal TenantPrincipal principal) {
        organizationService.delete(principal.organizationId());
    }

    @GetMapping("/members")
    @PreAuthorize("@orgSecurity.hasMinRole(authentication, 'VIEWER')")
    public List<MemberDto> listMembers(@AuthenticationPrincipal TenantPrincipal principal) {
        List<OrganizationMember> members = organizationService.getMembers(principal.organizationId());
        List<Long> userIds = members.stream().map(OrganizationMember::getUserId).toList();
        Map<Long, User> usersById = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        return members.stream()
                .map(m -> MemberDto.from(m, usersById.get(m.getUserId())))
                .toList();
    }

    @PostMapping("/members")
    @PreAuthorize("@orgSecurity.hasMinRole(authentication, 'ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public MemberDto addMember(@RequestBody AddMemberRequest request,
                               @AuthenticationPrincipal TenantPrincipal principal) {
        OrganizationMember member = organizationService.addMember(
                principal.organizationId(), request.userId(), OrgRole.valueOf(request.role()));
        User user = userRepository.findById(member.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        return MemberDto.from(member, user);
    }

    @PatchMapping("/members/{memberId}")
    @PreAuthorize("@orgSecurity.hasMinRole(authentication, 'ADMIN')")
    public MemberDto updateMemberRole(@PathVariable Long memberId,
                                      @RequestBody UpdateMemberRoleRequest request,
                                      @AuthenticationPrincipal TenantPrincipal principal) {
        OrganizationMember updated = organizationService.updateMemberRole(
                memberId, principal.organizationId(), OrgRole.valueOf(request.role()));
        User user = userRepository.findById(updated.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        return MemberDto.from(updated, user);
    }

    @DeleteMapping("/members/{memberId}")
    @PreAuthorize("@orgSecurity.hasMinRole(authentication, 'ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@PathVariable Long memberId,
                             @AuthenticationPrincipal TenantPrincipal principal) {
        organizationService.removeMemberById(memberId, principal.organizationId());
    }

    @GetMapping("/audit-log")
    @PreAuthorize("@orgSecurity.hasMinRole(authentication, 'VIEWER')")
    public List<AuditLogDto> listAuditLog(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @AuthenticationPrincipal TenantPrincipal principal) {
        List<AuditLog> entries = auditService.findByOrganization(
                principal.organizationId(), PageRequest.of(page, size)).getContent();
        List<Long> userIds = entries.stream().map(AuditLog::getUserId).filter(id -> id != null).distinct().toList();
        Map<Long, User> usersById = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        return entries.stream()
                .map(e -> AuditLogDto.from(e, e.getUserId() != null && usersById.containsKey(e.getUserId())
                        ? usersById.get(e.getUserId()).getEmail() : null))
                .toList();
    }
}
