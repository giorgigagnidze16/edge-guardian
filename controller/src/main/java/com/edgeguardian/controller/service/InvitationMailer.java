package com.edgeguardian.controller.service;

import com.edgeguardian.controller.config.MailProperties;
import com.edgeguardian.controller.model.OrgRole;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * Sends invitation emails. Delivery is best-effort: a mail failure is logged
 * and swallowed so it never blocks the invitation itself (the pending record is
 * already persisted and resolves on first login regardless).
 */
@Slf4j
@Service
@EnableConfigurationProperties(MailProperties.class)
public class InvitationMailer {

    private final JavaMailSender mailSender;
    private final MailProperties props;

    public InvitationMailer(JavaMailSender mailSender, MailProperties props) {
        this.mailSender = mailSender;
        this.props = props;
    }

    public void sendInvitation(String toEmail, String organizationName, OrgRole role) {
        if (!props.enabled()) {
            log.debug("Mail disabled; skipping invitation email to {}", toEmail);
            return;
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
            helper.setFrom(props.from());
            helper.setTo(toEmail);
            helper.setSubject("You've been invited to " + organizationName + " on EdgeGuardian");
            helper.setText(body(organizationName, role), true);
            mailSender.send(message);
            log.info("Invitation email sent to {} for org {}", toEmail, organizationName);
        } catch (Exception e) {
            log.warn("Failed to send invitation email to {}: {}", toEmail, e.getMessage());
        }
    }

    private String body(String organizationName, OrgRole role) {
        String url = props.appUrl();
        String roleName = role.name().charAt(0) + role.name().substring(1).toLowerCase();
        return """
                <div style="font-family:system-ui,Segoe UI,Arial,sans-serif;max-width:480px;margin:auto">
                  <h2 style="margin:0 0 8px">You've been invited to EdgeGuardian</h2>
                  <p>You've been invited to join <strong>%s</strong> as <strong>%s</strong>.</p>
                  <p>Sign in with this email address to accept — your access is granted automatically on your first sign-in.</p>
                  <p style="margin:24px 0">
                    <a href="%s" style="background:#2563eb;color:#fff;padding:10px 18px;border-radius:6px;text-decoration:none">
                      Open EdgeGuardian
                    </a>
                  </p>
                  <p style="color:#6b7280;font-size:13px">If you weren't expecting this, you can ignore this email.</p>
                </div>
                """.formatted(escape(organizationName), roleName, url);
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
