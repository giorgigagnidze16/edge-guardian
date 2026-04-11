package com.edgeguardian.controller.service;

import com.edgeguardian.controller.model.User;
import com.edgeguardian.controller.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
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

    @Transactional
    public User syncFromJwt(Jwt jwt) {
        return userRepository.findByKeycloakId(jwt.getSubject())
                .map(existing -> updateProfile(existing, jwt))
                .orElseGet(() -> registerNewUser(jwt));
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
        String keycloakId = jwt.getSubject();
        String email = jwt.getClaimAsString("email");
        String name = jwt.getClaimAsString("name");

        User user;
        try {
            user = userRepository.save(User.builder()
                    .keycloakId(keycloakId)
                    .email(email)
                    .displayName(name != null ? name : email)
                    .avatarUrl(jwt.getClaimAsString("picture"))
                    .build());
        } catch (DataIntegrityViolationException e) {
            return userRepository.findByKeycloakId(keycloakId)
                    .orElseThrow(() -> new IllegalStateException(
                            "User not found after concurrent insert", e));
        }

        log.info("New user synced from Keycloak: {} ({})", email, keycloakId);
        organizationService.createPersonalOrganization(user);
        return user;
    }
}
