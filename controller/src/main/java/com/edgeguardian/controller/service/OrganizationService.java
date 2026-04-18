package com.edgeguardian.controller.service;

import com.edgeguardian.controller.exception.ConflictException;
import com.edgeguardian.controller.exception.NotFoundException;
import com.edgeguardian.controller.model.OrgRole;
import com.edgeguardian.controller.model.Organization;
import com.edgeguardian.controller.model.OrganizationMember;
import com.edgeguardian.controller.model.User;
import com.edgeguardian.controller.repository.OrganizationMemberRepository;
import com.edgeguardian.controller.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Owns organizations and their membership graph.
 *
 * <p>Invariant: the OWNER is assigned at org creation and never changes. To remove the
 * owner, delete the whole organization. Role mutations on other members cannot produce
 * or remove an OWNER.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrganizationService {

    private final OrganizationRepository organizationRepository;
    private final OrganizationMemberRepository memberRepository;

    @Transactional
    public Organization create(String name, String slug, String description, Long ownerUserId) {
        if (organizationRepository.existsBySlug(slug)) {
            throw new ConflictException("Organization slug already exists");
        }
        Organization org = organizationRepository.save(Organization.builder()
                .name(name).slug(slug).description(description)
                .build());
        memberRepository.save(OrganizationMember.builder()
                .organizationId(org.getId()).userId(ownerUserId).orgRole(OrgRole.OWNER)
                .build());
        return org;
    }

    @Transactional(readOnly = true)
    public Optional<Organization> findById(Long id) {
        return organizationRepository.findById(id);
    }

    @Transactional(readOnly = true)
    public List<Organization> findByUser(Long userId) {
        List<Long> orgIds = memberRepository.findByUserId(userId).stream()
                .map(OrganizationMember::getOrganizationId).toList();
        return organizationRepository.findAllById(orgIds);
    }

    @Transactional
    public Organization update(Long orgId, String name, String description) {
        Organization org = requireOrg(orgId);
        org.setName(name);
        if (description != null) org.setDescription(description);
        return organizationRepository.save(org);
    }

    @Transactional
    public void delete(Long orgId) {
        organizationRepository.deleteById(orgId);
    }

    @Transactional
    public void createPersonalOrganization(User user) {
        String slug = generateUniqueSlug(user.getEmail());
        create(user.getDisplayName() + "'s Organization", slug, "Personal organization", user.getId());
        log.info("Personal org created for user {}: {}", user.getEmail(), slug);
    }

    @Transactional(readOnly = true)
    public List<OrganizationMember> getMembers(Long orgId) {
        return memberRepository.findByOrganizationId(orgId);
    }

    /**
     * Adds a non-OWNER member. Ownership is fixed at org creation.
     */
    @Transactional
    public OrganizationMember addMember(Long orgId, Long userId, OrgRole role) {
        rejectOwnerRole(role);
        if (memberRepository.existsByOrganizationIdAndUserId(orgId, userId)) {
            throw new ConflictException("User is already a member");
        }
        return memberRepository.save(OrganizationMember.builder()
                .organizationId(orgId).userId(userId).orgRole(role)
                .build());
    }

    /**
     * Updates a member's role. Cannot target the OWNER or promote anyone <em>to</em> OWNER.
     */
    @Transactional
    public OrganizationMember updateMemberRole(Long memberId, Long orgId, OrgRole role) {
        rejectOwnerRole(role);
        OrganizationMember member = requireMemberInOrg(memberId, orgId);
        if (member.getOrgRole() == OrgRole.OWNER) {
            throw new ConflictException("The organization owner's role cannot be changed");
        }
        member.setOrgRole(role);
        return memberRepository.save(member);
    }

    /**
     * Removes a member. OWNER cannot be removed — to leave, delete the whole organization.
     */
    @Transactional
    public void removeMemberById(Long memberId, Long orgId) {
        OrganizationMember member = requireMemberInOrg(memberId, orgId);
        if (member.getOrgRole() == OrgRole.OWNER) {
            throw new ConflictException(
                    "The organization owner cannot be removed — delete the organization instead");
        }
        memberRepository.delete(member);
    }

    private Organization requireOrg(Long orgId) {
        return organizationRepository.findById(orgId)
                .orElseThrow(() -> new NotFoundException("Organization not found"));
    }

    /**
     * Cross-tenant lookups return "not found" to avoid leaking member existence.
     */
    private OrganizationMember requireMemberInOrg(Long memberId, Long orgId) {
        OrganizationMember member = memberRepository.findById(memberId)
                .orElseThrow(() -> new NotFoundException("Member not found"));
        if (!orgId.equals(member.getOrganizationId())) {
            throw new NotFoundException("Member not found");
        }
        return member;
    }

    private void rejectOwnerRole(OrgRole role) {
        if (role == OrgRole.OWNER) {
            throw new ConflictException("OWNER role cannot be assigned — it is fixed at org creation");
        }
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
