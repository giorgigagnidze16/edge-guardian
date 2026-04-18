package com.edgeguardian.controller.service;

import com.edgeguardian.controller.AbstractIntegrationTest;
import com.edgeguardian.controller.exception.ConflictException;
import com.edgeguardian.controller.exception.NotFoundException;
import com.edgeguardian.controller.model.OrgRole;
import com.edgeguardian.controller.model.OrganizationMember;
import com.edgeguardian.controller.model.User;
import com.edgeguardian.controller.repository.OrganizationMemberRepository;
import com.edgeguardian.controller.repository.OrganizationRepository;
import com.edgeguardian.controller.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(OrganizationService.class)
class OrganizationServiceIT extends AbstractIntegrationTest {

    @Autowired
    private OrganizationService organizationService;
    @Autowired
    private OrganizationRepository organizationRepository;
    @Autowired
    private OrganizationMemberRepository memberRepository;
    @Autowired
    private UserRepository userRepository;

    private Long orgId;
    private Long ownerUserId;
    private Long memberUserId;
    private Long memberRecordId;

    private static void assertConflict(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(ConflictException.class);
    }

    private static void assertNotFound(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(NotFoundException.class);
    }

    @BeforeEach
    void setUp() {
        long stamp = System.nanoTime();
        ownerUserId = userRepository.save(User.builder()
                .keycloakId("kc-owner-" + stamp).email("owner-" + stamp + "@test")
                .displayName("Owner").build()).getId();
        memberUserId = userRepository.save(User.builder()
                .keycloakId("kc-mem-" + stamp).email("mem-" + stamp + "@test")
                .displayName("Mem").build()).getId();

        orgId = organizationService.create("Acme", "acme-" + stamp, null, ownerUserId).getId();
        memberRecordId = organizationService.addMember(orgId, memberUserId, OrgRole.VIEWER).getId();
    }

    @Test
    void create_producesExactlyOneOwner() {
        long owners = memberRepository.findByOrganizationId(orgId).stream()
                .filter(m -> m.getOrgRole() == OrgRole.OWNER).count();
        assertThat(owners).isEqualTo(1);
    }

    @Test
    void addMember_asOwner_rejected() {
        assertConflict(() -> organizationService.addMember(orgId, memberUserId, OrgRole.OWNER));
    }

    @Test
    void removeMember_owner_rejected() {
        Long ownerMemberId = memberRepository.findByOrganizationIdAndUserId(orgId, ownerUserId)
                .orElseThrow().getId();
        assertConflict(() -> organizationService.removeMemberById(ownerMemberId, orgId));
        assertThat(memberRepository.findById(ownerMemberId)).isPresent();
    }

    @Test
    void removeMember_nonOwner_succeeds() {
        organizationService.removeMemberById(memberRecordId, orgId);
        assertThat(memberRepository.findById(memberRecordId)).isEmpty();
    }

    @Test
    void removeMember_crossOrg_returns404() {
        long stamp = System.nanoTime();
        Long otherOrg = organizationService.create("Other", "other-" + stamp, null, ownerUserId).getId();
        assertNotFound(() -> organizationService.removeMemberById(memberRecordId, otherOrg));
    }

    @Test
    void updateMemberRole_promoteToOwner_rejected() {
        assertConflict(() -> organizationService.updateMemberRole(memberRecordId, orgId, OrgRole.OWNER));
    }

    @Test
    void updateMemberRole_demoteOwner_rejected() {
        Long ownerMemberId = memberRepository.findByOrganizationIdAndUserId(orgId, ownerUserId)
                .orElseThrow().getId();
        assertConflict(() -> organizationService.updateMemberRole(ownerMemberId, orgId, OrgRole.ADMIN));
    }

    @Test
    void updateMemberRole_nonOwner_succeeds() {
        OrganizationMember updated = organizationService.updateMemberRole(
                memberRecordId, orgId, OrgRole.ADMIN);
        assertThat(updated.getOrgRole()).isEqualTo(OrgRole.ADMIN);
    }

    @Test
    void delete_cascadesMembers() {
        organizationService.delete(orgId);
        organizationRepository.flush(); // trigger DB-level ON DELETE CASCADE
        assertThat(organizationRepository.existsById(orgId)).isFalse();
        assertThat(memberRepository.findByOrganizationId(orgId)).isEmpty();
    }
}
