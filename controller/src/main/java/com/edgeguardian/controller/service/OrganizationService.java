package com.edgeguardian.controller.service;

import com.edgeguardian.controller.model.OrgRole;
import com.edgeguardian.controller.model.Organization;
import com.edgeguardian.controller.model.OrganizationMember;
import com.edgeguardian.controller.model.User;
import com.edgeguardian.controller.repository.OrganizationMemberRepository;
import com.edgeguardian.controller.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrganizationService {

    private final OrganizationRepository organizationRepository;
    private final OrganizationMemberRepository memberRepository;

    @Transactional
    public Organization create(String name, String slug, String description, Long ownerUserId) {
        if (organizationRepository.existsBySlug(slug)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Organization slug already exists");
        }
        Organization org = Organization.builder()
                .name(name)
                .slug(slug)
                .description(description)
                .build();
        org = organizationRepository.save(org);

        OrganizationMember membership = OrganizationMember.builder()
                .organizationId(org.getId())
                .userId(ownerUserId)
                .orgRole(OrgRole.OWNER)
                .build();
        memberRepository.save(membership);

        return org;
    }

    @Transactional(readOnly = true)
    public Optional<Organization> findById(Long id) {
        return organizationRepository.findById(id);
    }

    @Transactional(readOnly = true)
    public List<Organization> findByUser(Long userId) {
        List<OrganizationMember> memberships = memberRepository.findByUserId(userId);
        List<Long> orgIds = memberships.stream().map(OrganizationMember::getOrganizationId).toList();
        return organizationRepository.findAllById(orgIds);
    }

    @Transactional
    public Organization update(Long orgId, String name, String description) {
        Organization org = organizationRepository.findById(orgId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Organization not found"));
        org.setName(name);
        if (description != null) org.setDescription(description);
        return organizationRepository.save(org);
    }

    @Transactional
    public void delete(Long orgId) {
        organizationRepository.deleteById(orgId);
    }

    @Transactional(readOnly = true)
    public List<OrganizationMember> getMembers(Long orgId) {
        return memberRepository.findByOrganizationId(orgId);
    }

    @Transactional
    public OrganizationMember addMember(Long orgId, Long userId, OrgRole role) {
        if (memberRepository.existsByOrganizationIdAndUserId(orgId, userId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "User is already a member");
        }
        OrganizationMember member = OrganizationMember.builder()
                .organizationId(orgId)
                .userId(userId)
                .orgRole(role)
                .build();
        return memberRepository.save(member);
    }

    @Transactional
    public OrganizationMember updateMemberRole(Long orgId, Long userId, OrgRole role) {
        OrganizationMember member = memberRepository.findByOrganizationIdAndUserId(orgId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found"));
        member.setOrgRole(role);
        return memberRepository.save(member);
    }

    // Returns 404 on cross-tenant access to avoid leaking member existence.
    @Transactional
    public void removeMemberById(Long memberId, Long expectedOrgId) {
        OrganizationMember member = memberRepository.findById(memberId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found"));
        if (!expectedOrgId.equals(member.getOrganizationId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found");
        }
        memberRepository.delete(member);
    }

    @Transactional
    public void createPersonalOrganization(User user) {
        String slug = generateUniqueSlug(user.getEmail());
        Organization org = create(
                user.getDisplayName() + "'s Organization",
                slug,
                "Personal organization",
                user.getId());
        log.info("Personal org created for user {}: {}", user.getEmail(), slug);
    }

    private String generateUniqueSlug(String email) {
        String base = email.split("@")[0]
                .toLowerCase()
                .replaceAll("[^a-z0-9]", "-")
                .replaceAll("-+", "-");
        String slug = base;
        int counter = 1;
        while (organizationRepository.existsBySlug(slug)) {
            slug = base + "-" + counter++;
        }
        return slug;
    }
}
