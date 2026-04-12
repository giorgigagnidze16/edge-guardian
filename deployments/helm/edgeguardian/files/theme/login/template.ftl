<#macro registrationLayout bodyClass="" displayInfo=false displayMessage=true displayRequiredFields=false showAnotherWayIfPresent=true>
<!DOCTYPE html>
<html>
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>${msg("loginTitle",(realm.displayName!'EdgeGuardian'))}</title>
    <link rel="preconnect" href="https://fonts.googleapis.com">
    <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
    <link href="https://fonts.googleapis.com/css2?family=Plus+Jakarta+Sans:wght@400;500;600;700&family=JetBrains+Mono:wght@400;500&display=swap" rel="stylesheet">
    <link rel="stylesheet" href="${url.resourcesPath}/css/styles.css">
    <script>
        (function() {
            var m = document.cookie.match(/(?:^|;\s*)eg-theme=([^;]*)/);
            var t = m ? m[1] : null;
            if (t === 'dark' || (!t && window.matchMedia('(prefers-color-scheme: dark)').matches)) {
                document.documentElement.classList.add('dark');
            } else {
                document.documentElement.classList.remove('dark');
            }
        })();
    </script>
</head>
<body class="${bodyClass}">
    <div class="eg-page">
        <div class="eg-topbar">
            <a href="http://localhost:3000" class="eg-back-link">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="19" y1="12" x2="5" y2="12"/><polyline points="12 19 5 12 12 5"/></svg>
                Back to home
            </a>
        </div>

        <div class="eg-content">
            <div class="eg-logo-wrap">
                <div class="eg-logo-glow"></div>
                <svg class="eg-logo" width="56" height="56" viewBox="0 0 48 48" fill="none" xmlns="http://www.w3.org/2000/svg">
                    <path d="M24 4 L40 12 V28 L24 44 L8 28 V12 Z" stroke="currentColor" stroke-width="2.5" stroke-linejoin="round" fill="none"/>
                    <path d="M24 20 L15 32 M24 20 L33 32 M24 20 V10" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
                    <circle cx="24" cy="20" r="3.5" fill="currentColor"/>
                    <circle cx="24" cy="10" r="2" fill="currentColor"/>
                    <circle cx="15" cy="32" r="2" fill="currentColor"/>
                    <circle cx="33" cy="32" r="2" fill="currentColor"/>
                </svg>
            </div>

            <h1 class="eg-title">Edge<span>Guardian</span></h1>

            <#if displayMessage && message?has_content && (message.type != 'warning' || !isAppInitiatedAction??)>
                <div class="eg-alert eg-alert-${message.type}">
                    ${kcSanitize(message.summary)?no_esc}
                </div>
            </#if>

            <#nested "header">
            <#nested "form">

            <#if displayInfo>
                <div class="eg-info">
                    <#nested "info">
                </div>
            </#if>
        </div>
    </div>
</body>
</html>
</#macro>