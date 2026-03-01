package com.edgeguardian.controller.service;

import com.edgeguardian.controller.model.OrgRole;
import com.edgeguardian.controller.model.Organization;
import com.edgeguardian.controller.model.OrganizationMember;
import com.edgeguardian.controller.model.User;
import com.edgeguardian.controller.repository.OrganizationMemberRepository;
import com.edgeguardian.controller.repository.OrganizationRepository;
import com.edgeguardian.controller.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Syncs user from Keycloak JWT on first login, auto-creates personal org.
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final OrganizationMemberRepository memberRepository;

    public UserService(UserRepository userRepository,
                       OrganizationRepository organizationRepository,
                       OrganizationMemberRepository memberRepository) {
        this.userRepository = userRepository;
        this.organizationRepository = organizationRepository;
        this.memberRepository = memberRepository;
    }

    /**
     * Sync user from Keycloak JWT. Creates user + personal org on first login.
     */
    @Transactional
    public User syncFromJwt(Jwt jwt) {
        String keycloakId = jwt.getSubject();
        String email = jwt.getClaimAsString("email");
        String name = jwt.getClaimAsString("name");
        String picture = jwt.getClaimAsString("picture");

        Optional<User> existing = userRepository.findByKeycloakId(keycloakId);
        if (existing.isPresent()) {
            User user = existing.get();
            // Update fields that may have changed in Keycloak
            if (name != null) user.setDisplayName(name);
            if (picture != null) user.setAvatarUrl(picture);
            if (email != null) user.setEmail(email);
            return userRepository.save(user);
        }

        // Create new user
        User user = User.builder()
                .keycloakId(keycloakId)
                .email(email)
                .displayName(name != null ? name : email)
                .avatarUrl(picture)
                .build();
        user = userRepository.save(user);
        log.info("New user synced from Keycloak: {} ({})", email, keycloakId);

        // Auto-create personal organization
        String slug = generateSlug(email);
        Organization personalOrg = Organization.builder()
                .name(user.getDisplayName() + "'s Organization")
                .slug(slug)
                .description("Personal organization")
                .build();
        personalOrg = organizationRepository.save(personalOrg);

        OrganizationMember membership = OrganizationMember.builder()
                .organizationId(personalOrg.getId())
                .userId(user.getId())
                .orgRole(OrgRole.owner)
                .build();
        memberRepository.save(membership);

        log.info("Personal org created for user {}: {}", email, slug);
        return user;
    }

    @Transactional(readOnly = true)
    public Optional<User> findByKeycloakId(String keycloakId) {
        return userRepository.findByKeycloakId(keycloakId);
    }

    @Transactional(readOnly = true)
    public Optional<User> findById(Long id) {
        return userRepository.findById(id);
    }

    private String generateSlug(String email) {
        String base = email.split("@")[0]
                .toLowerCase()
                .replaceAll("[^a-z0-9]", "-")
                .replaceAll("-+", "-");
        // Ensure uniqueness
        String slug = base;
        int counter = 1;
        while (organizationRepository.existsBySlug(slug)) {
            slug = base + "-" + counter++;
        }
        return slug;
    }
}
