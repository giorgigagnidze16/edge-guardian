package com.edgeguardian.controller.service;

import com.edgeguardian.controller.model.User;
import com.edgeguardian.controller.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final OrganizationService organizationService;

    /**
     * Resolves or creates the local {@link User} mirror for a Keycloak JWT.
     *
     * <p>Lookup order:
     * <ol>
     *   <li>by Keycloak subject — normal sign-in;
     *   <li>by email — rebinds the Keycloak id when the IdP was reset (same human, new
     *       {@code sub}) so the unique-email constraint doesn't collide;
     *   <li>otherwise register a new user and bootstrap their personal org.
     * </ol>
     */
    @Transactional
    public User syncFromJwt(Jwt jwt) {
        String keycloakId = jwt.getSubject();
        String email = jwt.getClaimAsString("email");

        Optional<User> byKeycloak = userRepository.findByKeycloakId(keycloakId);
        if (byKeycloak.isPresent()) {
            return updateProfile(byKeycloak.get(), jwt);
        }

        Optional<User> byEmail = email != null ? userRepository.findByEmail(email) : Optional.empty();
        if (byEmail.isPresent()) {
            User existing = byEmail.get();
            log.info("Rebinding Keycloak id for {}: {} -> {}",
                    email, existing.getKeycloakId(), keycloakId);
            existing.setKeycloakId(keycloakId);
            return updateProfile(existing, jwt);
        }

        return registerNewUser(jwt);
    }

    @Transactional(readOnly = true)
    public Optional<User> findById(Long id) {
        return userRepository.findById(id);
    }

    private User updateProfile(User user, Jwt jwt) {
        boolean changed = false;

        String email = jwt.getClaimAsString("email");
        if (email != null && !email.equals(user.getEmail())) {
            user.setEmail(email);
            changed = true;
        }

        String name = jwt.getClaimAsString("name");
        if (name != null && !name.equals(user.getDisplayName())) {
            user.setDisplayName(name);
            changed = true;
        }

        String picture = jwt.getClaimAsString("picture");
        if (picture != null && !picture.equals(user.getAvatarUrl())) {
            user.setAvatarUrl(picture);
            changed = true;
        }

        return changed ? userRepository.save(user) : user;
    }

    private User registerNewUser(Jwt jwt) {
        String email = jwt.getClaimAsString("email");
        String name = jwt.getClaimAsString("name");
        User user = userRepository.save(User.builder()
                .keycloakId(jwt.getSubject())
                .email(email)
                .displayName(name != null ? name : email)
                .avatarUrl(jwt.getClaimAsString("picture"))
                .build());
        log.info("New user synced from Keycloak: {} ({})", email, user.getKeycloakId());
        organizationService.createPersonalOrganization(user);
        return user;
    }
}
