<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=!messagesPerField.existsError('firstName','lastName','email','username','password','password-confirm'); section>

    <#if section = "header">
        <p class="eg-subtitle">${msg("registerTitle")}</p>
    <#elseif section = "form">

        <form action="${url.registrationAction}" method="post" class="eg-form">
            <div class="eg-row-fields">
                <div class="eg-field">
                    <label for="firstName" class="eg-label">${msg("firstName")}</label>
                    <input id="firstName" name="firstName" type="text"
                           value="${(register.formData.firstName!'')}"
                           class="eg-input <#if messagesPerField.existsError('firstName')>eg-input-error</#if>"
                           placeholder="Jane"
                           autocomplete="given-name">
                    <#if messagesPerField.existsError('firstName')>
                        <span class="eg-field-error">${kcSanitize(messagesPerField.getFirstError('firstName'))?no_esc}</span>
                    </#if>
                </div>
                <div class="eg-field">
                    <label for="lastName" class="eg-label">${msg("lastName")}</label>
                    <input id="lastName" name="lastName" type="text"
                           value="${(register.formData.lastName!'')}"
                           class="eg-input <#if messagesPerField.existsError('lastName')>eg-input-error</#if>"
                           placeholder="Doe"
                           autocomplete="family-name">
                    <#if messagesPerField.existsError('lastName')>
                        <span class="eg-field-error">${kcSanitize(messagesPerField.getFirstError('lastName'))?no_esc}</span>
                    </#if>
                </div>
            </div>

            <div class="eg-field">
                <label for="email" class="eg-label">${msg("email")}</label>
                <input id="email" name="email" type="email"
                       value="${(register.formData.email!'')}"
                       class="eg-input <#if messagesPerField.existsError('email')>eg-input-error</#if>"
                       placeholder="jane@example.com"
                       autocomplete="email">
                <#if messagesPerField.existsError('email')>
                    <span class="eg-field-error">${kcSanitize(messagesPerField.getFirstError('email'))?no_esc}</span>
                </#if>
            </div>

            <#if !realm.registrationEmailAsUsername>
                <div class="eg-field">
                    <label for="username" class="eg-label">${msg("username")}</label>
                    <input id="username" name="username" type="text"
                           value="${(register.formData.username!'')}"
                           class="eg-input <#if messagesPerField.existsError('username')>eg-input-error</#if>"
                           placeholder="janedoe"
                           autocomplete="username">
                    <#if messagesPerField.existsError('username')>
                        <span class="eg-field-error">${kcSanitize(messagesPerField.getFirstError('username'))?no_esc}</span>
                    </#if>
                </div>
            </#if>

            <div class="eg-field">
                <label for="password" class="eg-label">${msg("password")}</label>
                <input id="password" name="password" type="password"
                       class="eg-input <#if messagesPerField.existsError('password')>eg-input-error</#if>"
                       placeholder="&#8226;&#8226;&#8226;&#8226;&#8226;&#8226;&#8226;&#8226;"
                       autocomplete="new-password">
                <#if messagesPerField.existsError('password')>
                    <span class="eg-field-error">${kcSanitize(messagesPerField.getFirstError('password'))?no_esc}</span>
                </#if>
            </div>

            <div class="eg-field">
                <label for="password-confirm" class="eg-label">${msg("passwordConfirm")}</label>
                <input id="password-confirm" name="password-confirm" type="password"
                       class="eg-input <#if messagesPerField.existsError('password-confirm')>eg-input-error</#if>"
                       placeholder="&#8226;&#8226;&#8226;&#8226;&#8226;&#8226;&#8226;&#8226;"
                       autocomplete="new-password">
                <#if messagesPerField.existsError('password-confirm')>
                    <span class="eg-field-error">${kcSanitize(messagesPerField.getFirstError('password-confirm'))?no_esc}</span>
                </#if>
            </div>

            <button type="submit" class="eg-btn eg-btn-primary">${msg("doRegister")}</button>
        </form>

        <p class="eg-footer-text">
            ${msg("backToLogin")?no_esc}
            <a href="${url.loginUrl}" class="eg-link">${msg("doLogIn")}</a>
        </p>

    </#if>

</@layout.registrationLayout>