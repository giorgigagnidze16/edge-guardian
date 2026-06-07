<#import "template.ftl" as layout>
<@layout.registrationLayout displayInfo=true displayMessage=false; section>

    <#if section = "header">
        <p class="eg-subtitle">${msg("emailVerifyTitle")}</p>
    <#elseif section = "form">
        <div class="eg-info-text" style="margin-top:1rem;">
            ${msg("emailVerifyInstruction1", user.email)}
        </div>
    <#elseif section = "info">
        <div class="eg-info-text">
            ${msg("emailVerifyInstruction2")}
            <a href="${url.loginAction}" class="eg-link">${msg("doClickHere")}</a> ${msg("emailVerifyInstruction3")}
        </div>
    </#if>

</@layout.registrationLayout>
