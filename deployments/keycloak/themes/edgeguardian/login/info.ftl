<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=false; section>

    <#if section = "header">
        <#if messageHeader??>
            <p class="eg-subtitle">${messageHeader}</p>
        <#else>
            <p class="eg-subtitle">${message.summary}</p>
        </#if>
    <#elseif section = "form">
        <div class="eg-info-text" style="margin-top:1rem;">
            ${kcSanitize(message.summary)?no_esc}
        </div>

        <#if skipLink??>
        <#else>
            <#if pageRedirectUri?has_content>
                <a href="${pageRedirectUri}" class="eg-btn eg-btn-primary" style="margin-top:1.5rem;display:block;text-align:center;text-decoration:none;">${kcSanitize(msg("backToApplication"))?no_esc}</a>
            <#elseif actionUri?has_content>
                <a href="${actionUri}" class="eg-btn eg-btn-primary" style="margin-top:1.5rem;display:block;text-align:center;text-decoration:none;">${kcSanitize(msg("proceedWithAction"))?no_esc}</a>
            <#elseif (client.baseUrl)?has_content>
                <a href="${client.baseUrl}" class="eg-btn eg-btn-primary" style="margin-top:1.5rem;display:block;text-align:center;text-decoration:none;">${kcSanitize(msg("backToApplication"))?no_esc}</a>
            </#if>
        </#if>
    </#if>

</@layout.registrationLayout>