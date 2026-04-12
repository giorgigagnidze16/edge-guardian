<#import "template.ftl" as layout>
<@layout.registrationLayout displayInfo=true displayMessage=!messagesPerField.existsError('username'); section>

    <#if section = "header">
        <p class="eg-subtitle">${msg("emailForgotTitle")}</p>
    <#elseif section = "form">

        <form action="${url.loginAction}" method="post" class="eg-form">
            <div class="eg-field">
                <label for="username" class="eg-label">
                    <#if !realm.loginWithEmailAllowed>${msg("username")}
                    <#elseif !realm.registrationEmailAsUsername>${msg("usernameOrEmail")}
                    <#else>${msg("email")}
                    </#if>
                </label>
                <input id="username" name="username" type="text"
                       class="eg-input <#if messagesPerField.existsError('username')>eg-input-error</#if>"
                       autofocus autocomplete="username"
                       placeholder="you@example.com">
                <#if messagesPerField.existsError('username')>
                    <span class="eg-field-error">${kcSanitize(messagesPerField.getFirstError('username'))?no_esc}</span>
                </#if>
            </div>

            <button type="submit" class="eg-btn eg-btn-primary">${msg("doSubmit")}</button>
        </form>

        <p class="eg-footer-text">
            <a href="${url.loginUrl}" class="eg-link">&larr; ${msg("backToLogin")}</a>
        </p>

    <#elseif section = "info">
        <p class="eg-info-text">${msg("emailInstruction")}</p>
    </#if>

</@layout.registrationLayout>