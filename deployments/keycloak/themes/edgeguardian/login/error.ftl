<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=true; section>

    <#if section = "header">
        <p class="eg-subtitle">${kcSanitize(message.summary)?no_esc}</p>
    <#elseif section = "form">
        <#if skipLink??>
        <#else>
            <#if client?? && client.baseUrl?has_content>
                <a href="${client.baseUrl}" class="eg-btn eg-btn-outline" style="margin-top:1.5rem;display:block;text-align:center;text-decoration:none;">${kcSanitize(msg("backToApplication"))?no_esc}</a>
            </#if>
        </#if>
    </#if>

</@layout.registrationLayout>