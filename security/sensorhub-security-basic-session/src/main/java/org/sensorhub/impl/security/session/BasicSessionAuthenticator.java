/*******************************************************************************

 The contents of this file are subject to the Mozilla Public License, v. 2.0.
 If a copy of the MPL was not distributed with this file, You can obtain one
 at http://mozilla.org/MPL/2.0/.

 Software distributed under the License is distributed on an "AS IS" basis,
 WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 for the specific language governing rights and limitations under the License.

 The Initial Developer is GeoRobotix Innovative Research Inc. Portions created by the Initial
 Developer are Copyright (C) 2026 the Initial Developer. All Rights Reserved.

 ******************************************************************************/

package org.sensorhub.impl.security.session;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.security.auth.Subject;
import org.eclipse.jetty.security.ServerAuthException;
import org.eclipse.jetty.security.UserAuthentication;
import org.eclipse.jetty.security.authentication.LoginAuthenticator;
import org.eclipse.jetty.server.Authentication;
import org.eclipse.jetty.server.UserIdentity;
import org.sensorhub.api.security.ISecurityManager;
import org.slf4j.Logger;

/**
 * Authenticates one Basic request and replaces it with an opaque, server-side
 * session. This deliberately does not use HttpSession: OSCAR's static files
 * and SensorHub servlets live in separate Jetty contexts.
 */
public class BasicSessionAuthenticator extends LoginAuthenticator {
    private static final String AUTH_METHOD = "BASIC+SESSION";
    private static final String LOGIN_PATH = "/login";
    private static final String LOGOUT_PATH = "/logout";
    private static final String SESSION_PATH = "/session";
    private static final String LANGUAGE_COOKIE_NAME = "osh-language";
    private static final int LANGUAGE_COOKIE_MAX_AGE = 365 * 24 * 60 * 60;
    private static final int MAX_AUTH_HEADER_LENGTH = 16 * 1024;
    private static final long PRUNE_INTERVAL_MILLIS = 60_000L;

    private final Logger log;
    private final ISecurityManager securityManager;
    private final String cookieName;
    private final long idleTimeoutMillis;
    private final long absoluteTimeoutMillis;
    private final boolean secureCookie;
    private final String sameSite;
    private final String defaultRedirectPath;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, SessionRecord> sessions = new ConcurrentHashMap<>();
    private volatile long nextPruneAt;

    public BasicSessionAuthenticator(BasicSessionConfig config, ISecurityManager securityManager, Logger log) {
        if (config.cookieName == null || !config.cookieName.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+"))
            throw new IllegalArgumentException("Invalid session cookie name");
        if (config.idleTimeoutSeconds <= 0 || config.absoluteTimeoutSeconds <= 0)
            throw new IllegalArgumentException("Session timeouts must be positive");
        if (config.sameSite == null || !(config.sameSite.equalsIgnoreCase("Strict")
                || config.sameSite.equalsIgnoreCase("Lax") || config.sameSite.equalsIgnoreCase("None")))
            throw new IllegalArgumentException("SameSite must be Strict, Lax, or None");
        if (config.sameSite.equalsIgnoreCase("None") && !config.secureCookie)
            throw new IllegalArgumentException("SameSite=None requires a secure cookie");
        if (!isSafeRedirectTarget(config.defaultRedirectPath))
            throw new IllegalArgumentException("Default redirect path must be a local absolute path");

        this.cookieName = config.cookieName;
        this.idleTimeoutMillis = Math.multiplyExact((long) config.idleTimeoutSeconds, 1000L);
        this.absoluteTimeoutMillis = Math.multiplyExact((long) config.absoluteTimeoutSeconds, 1000L);
        this.secureCookie = config.secureCookie;
        this.sameSite = Character.toUpperCase(config.sameSite.charAt(0))
                + config.sameSite.substring(1).toLowerCase();
        this.defaultRedirectPath = config.defaultRedirectPath;
        this.securityManager = securityManager;
        this.log = log;
    }

    @Override
    public String getAuthMethod() {
        return AUTH_METHOD;
    }

    @Override
    public void prepareRequest(ServletRequest request) {
        // No request wrapper is required.
    }

    @Override
    public boolean secureResponse(ServletRequest request, ServletResponse response,
                                  boolean mandatory, Authentication.User validatedUser) {
        return true;
    }

    @Override
    public Authentication validateRequest(ServletRequest req, ServletResponse resp, boolean mandatory)
            throws ServerAuthException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) resp;

        try {
            pruneExpiredSessions();

            if (isLogoutRequest(request)) {
                logout(request, response);
                return Authentication.SEND_CONTINUE;
            }

            if (isLoginRequest(request)) {
                handleLogin(request, response);
                return Authentication.SEND_CONTINUE;
            }

            Authentication sessionAuthentication = authenticateSession(request, response);
            if (sessionAuthentication != null) {
                if (isSessionRequest(request)) {
                    renderSession(response, sessionUsername(request));
                    return Authentication.SEND_CONTINUE;
                }
                return sessionAuthentication;
            }

            Authentication basicAuthentication = authenticateBasic(request, response);
            if (basicAuthentication != null)
                return basicAuthentication;

            if (!mandatory)
                return Authentication.NOT_CHECKED;

            if (acceptsHtml(request) && "GET".equalsIgnoreCase(request.getMethod())) {
                redirectToLogin(request, response);
                return Authentication.SEND_CONTINUE;
            }

            response.setHeader("WWW-Authenticate", "Basic realm=\"OpenSensorHub\"");
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return Authentication.SEND_CONTINUE;
        } catch (IOException e) {
            log.error("Cannot complete authentication response", e);
            return Authentication.SEND_FAILURE;
        }
    }

    private Authentication authenticateSession(HttpServletRequest request, HttpServletResponse response) {
        String token = readSessionCookie(request);
        if (token == null)
            return null;

        SessionRecord session = sessions.get(token);
        long now = System.currentTimeMillis();
        if (session == null || session.isExpired(now, idleTimeoutMillis, absoluteTimeoutMillis)
                || securityManager.getUserInfo(session.username) == null) {
            sessions.remove(token);
            expireCookie(response);
            return null;
        }

        session.lastAccessAt = now;
        return new UserAuthentication(AUTH_METHOD, withoutCredentials(session.username));
    }

    private Authentication authenticateBasic(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.regionMatches(true, 0, "Basic ", 0, 6))
            return null;
        if (authHeader.length() > MAX_AUTH_HEADER_LENGTH) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Authorization header is too large");
            return Authentication.SEND_FAILURE;
        }

        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(authHeader.substring(6).trim());
        } catch (IllegalArgumentException e) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Malformed Basic authorization header");
            return Authentication.SEND_FAILURE;
        }

        String credentials = new String(decoded, StandardCharsets.ISO_8859_1);
        int separator = credentials.indexOf(':');
        if (separator <= 0) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Malformed Basic authorization header");
            return Authentication.SEND_FAILURE;
        }

        String username = credentials.substring(0, separator);
        String password = credentials.substring(separator + 1);
        UserIdentity identity = login(username, password, request);
        if (identity == null) {
            log.warn("Failed Basic authentication for user '{}'", username);
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return Authentication.SEND_FAILURE;
        }

        identity = withoutCredentials(username);
        createSession(request, response, username);
        log.debug("Created authentication session for user '{}'", username);
        return new UserAuthentication(AUTH_METHOD, identity);
    }

    private boolean isLogoutRequest(HttpServletRequest request) {
        return isRequestPath(request, LOGOUT_PATH);
    }

    private boolean isLoginRequest(HttpServletRequest request) {
        return isRequestPath(request, LOGIN_PATH);
    }

    private boolean isSessionRequest(HttpServletRequest request) {
        return isRequestPath(request, SESSION_PATH);
    }

    private boolean isRequestPath(HttpServletRequest request, String path) {
        String contextPath = request.getContextPath() == null ? "" : request.getContextPath();
        return path.equals(request.getServletPath()) || (contextPath + path).equals(request.getRequestURI());
    }

    private boolean acceptsHtml(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        return accept != null && accept.contains("text/html");
    }

    private void handleLogin(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String redirectTarget = redirectTarget(request.getParameter("continue"));
        String explicitLang = normalizeSupportedLanguage(request.getParameter("lang"));
        String lang = explicitLang != null ? explicitLang : resolveLoginLanguage(request);
        if ("GET".equalsIgnoreCase(request.getMethod())) {
            if (explicitLang != null)
                setLanguageCookie(response, explicitLang);
            renderLogin(response, false, redirectTarget, lang);
            return;
        }
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            return;
        }

        if (explicitLang != null)
            setLanguageCookie(response, explicitLang);
        String username = request.getParameter("username");
        String password = request.getParameter("password");
        if (username == null || password == null || username.length() > 256 || password.length() > 4096) {
            renderLogin(response, true, redirectTarget, lang);
            return;
        }

        UserIdentity identity = login(username, password, request);
        if (identity == null) {
            log.warn("Failed form authentication for user '{}'", username);
            renderLogin(response, true, redirectTarget, lang);
            return;
        }

        identity = withoutCredentials(username);
        createSession(request, response, username);
        log.debug("Created authentication session for user '{}'", username);
        response.sendRedirect(response.encodeRedirectURL(redirectTarget));
    }

    private void logout(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String token = readSessionCookie(request);
        if (token != null)
            sessions.remove(token);
        expireCookie(response);
        HttpSession servletSession = request.getSession(false);
        try {
            request.logout();
        } catch (ServletException e) {
            log.warn("Cannot clear the servlet authentication state during logout", e);
        }
        if (servletSession != null) {
            try {
                servletSession.invalidate();
            } catch (IllegalStateException e) {
                log.debug("Servlet session was already invalidated during logout");
            }
        }

        if ("GET".equalsIgnoreCase(request.getMethod()))
            response.sendRedirect(LOGIN_PATH);
        else
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }

    private void renderLogin(HttpServletResponse response, boolean failed, String redirectTarget, String lang)
            throws IOException {
        LoginCopy copy = LoginCopy.forLanguage(lang);
        String error = failed ? "<p class=\"error\" role=\"alert\">" + escapeHtml(copy.error) + "</p>" : "";
        String continueInput = "<input type=\"hidden\" name=\"continue\" value=\""
                + escapeHtml(redirectTarget) + "\">";
        String langInput = "<input type=\"hidden\" name=\"lang\" value=\""
                + escapeHtml(copy.lang) + "\">";
        String encodedContinue = URLEncoder.encode(redirectTarget, StandardCharsets.UTF_8.name());
        String langNav = "<nav class=\"langs\" aria-label=\"" + escapeHtml(copy.languageAria) + "\">"
                + renderLanguageLink("en", "English", copy.lang, encodedContinue)
                + "<span aria-hidden=\"true\">&middot;</span>"
                + renderLanguageLink("es", "Espa\u00f1ol", copy.lang, encodedContinue)
                + "<span aria-hidden=\"true\">&middot;</span>"
                + renderLanguageLink("fr", "Fran\u00e7ais", copy.lang, encodedContinue)
                + "<span aria-hidden=\"true\">&middot;</span>"
                + renderLanguageLink("el", "\u0395\u03bb\u03bb\u03b7\u03bd\u03b9\u03ba\u03ac", copy.lang, encodedContinue)
                + "</nav>";
        String page = "<!doctype html><html lang=\"" + escapeHtml(copy.lang) + "\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>" + escapeHtml(copy.title) + "</title><style>body{font:16px system-ui,sans-serif;background:#f4f6f8;"
                + "display:grid;place-items:center;min-height:100vh;margin:0}main{background:white;padding:2rem;"
                + "border-radius:.5rem;box-shadow:0 4px 18px #0002;width:min(24rem,calc(100% - 3rem))}"
                + "label,input,button{display:block;width:100%;box-sizing:border-box}label{margin-top:1rem}"
                + "input,button{font:inherit;padding:.65rem;margin-top:.35rem}button{margin-top:1.5rem;cursor:pointer}"
                + ".error{color:#a40000}.langs{margin-top:1.25rem;padding-top:.85rem;border-top:1px solid #e2e8f0;"
                + "display:flex;flex-wrap:wrap;justify-content:center;gap:.45rem;font-size:.875rem;color:#475569}"
                + ".langs a{color:#0f4c81;text-decoration:none}.langs a[aria-current=\"true\"]{font-weight:700;color:#0f172a;text-decoration:underline}"
                + "</style></head><body><main><h1>OSCAR</h1><p>" + escapeHtml(copy.subtitle) + "</p>"
                + error + "<form method=\"post\">"
                + continueInput + langInput
                + "<label for=\"username\">" + escapeHtml(copy.username) + "</label>"
                + "<input id=\"username\" name=\"username\" type=\"text\" required maxlength=\"256\" autofocus"
                + " autocomplete=\"username\" spellcheck=\"false\" autocapitalize=\"off\" enterkeyhint=\"next\">"
                + "<label for=\"current-password\">" + escapeHtml(copy.password) + "</label>"
                + "<input id=\"current-password\" name=\"password\" type=\"password\" required maxlength=\"4096\""
                + " autocomplete=\"current-password\" enterkeyhint=\"done\">"
                + "<button type=\"submit\">" + escapeHtml(copy.submit) + "</button></form>"
                + langNav + "</main></body></html>";
        byte[] body = page.getBytes(StandardCharsets.UTF_8);
        response.setStatus(failed ? HttpServletResponse.SC_UNAUTHORIZED : HttpServletResponse.SC_OK);
        response.setContentType("text/html; charset=UTF-8");
        response.setContentLength(body.length);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.getOutputStream().write(body);
    }

    private static String renderLanguageLink(String code, String label, String activeLang, String encodedContinue) {
        String currentAttr = code.equals(activeLang) ? " aria-current=\"true\"" : "";
        return "<a href=\"" + LOGIN_PATH + "?lang=" + code + "&amp;continue=" + escapeHtml(encodedContinue)
                + "\" lang=\"" + code + "\"" + currentAttr + ">" + escapeHtml(label) + "</a>";
    }

    private String resolveLoginLanguage(HttpServletRequest request) {
        String fromCookie = normalizeSupportedLanguage(readCookie(request, LANGUAGE_COOKIE_NAME));
        if (fromCookie != null)
            return fromCookie;
        String acceptLanguage = request.getHeader("Accept-Language");
        if (acceptLanguage != null && !acceptLanguage.trim().isEmpty()) {
            try {
                List<Locale.LanguageRange> ranges = Locale.LanguageRange.parse(acceptLanguage);
                for (Locale.LanguageRange range : ranges) {
                    String candidate = normalizeSupportedLanguage(range.getRange());
                    if (candidate != null)
                        return candidate;
                }
            } catch (IllegalArgumentException ignored) {
                for (String token : acceptLanguage.split(",")) {
                    int semi = token.indexOf(';');
                    String tag = semi >= 0 ? token.substring(0, semi) : token;
                    String candidate = normalizeSupportedLanguage(tag);
                    if (candidate != null)
                        return candidate;
                }
            }
        }
        return "en";
    }

    static String normalizeSupportedLanguage(String raw) {
        if (raw == null)
            return null;
        String cleaned = raw.trim().toLowerCase(Locale.ROOT);
        if (cleaned.isEmpty() || "*".equals(cleaned))
            return null;
        int dash = cleaned.indexOf('-');
        if (dash >= 0)
            cleaned = cleaned.substring(0, dash);
        int underscore = cleaned.indexOf('_');
        if (underscore >= 0)
            cleaned = cleaned.substring(0, underscore);
        switch (cleaned) {
            case "en":
            case "es":
            case "fr":
            case "el":
                return cleaned;
            default:
                return null;
        }
    }

    private void setLanguageCookie(HttpServletResponse response, String lang) {
        response.addHeader("Set-Cookie", LANGUAGE_COOKIE_NAME + "=" + lang
                + "; Path=/; Max-Age=" + LANGUAGE_COOKIE_MAX_AGE
                + "; SameSite=Lax" + (secureCookie ? "; Secure" : ""));
    }

    private void renderSession(HttpServletResponse response, String username) throws IOException {
        if (username == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        byte[] body = ("{\"username\":\"" + escapeJson(username) + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json; charset=UTF-8");
        response.setContentLength(body.length);
        response.setHeader("Cache-Control", "no-store");
        response.getOutputStream().write(body);
    }

    private String sessionUsername(HttpServletRequest request) {
        String token = readSessionCookie(request);
        SessionRecord session = token == null ? null : sessions.get(token);
        return session == null ? null : session.username;
    }

    private void redirectToLogin(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String target = request.getRequestURI();
        if (request.getQueryString() != null)
            target += "?" + request.getQueryString();
        String loginUrl = LOGIN_PATH + "?continue="
                + URLEncoder.encode(redirectTarget(target), StandardCharsets.UTF_8.name());
        response.sendRedirect(response.encodeRedirectURL(loginUrl));
    }

    private String redirectTarget(String candidate) {
        return isSafeRedirectTarget(candidate) ? candidate : defaultRedirectPath;
    }

    private static boolean isSafeRedirectTarget(String target) {
        if (target == null || target.isEmpty() || !target.startsWith("/") || target.startsWith("//")
                || target.indexOf('\\') >= 0)
            return false;
        for (int i = 0; i < target.length(); i++) {
            char c = target.charAt(i);
            if (c < 0x20 || c == 0x7f)
                return false;
        }
        return true;
    }

    private static String escapeHtml(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            switch (value.charAt(i)) {
                case '&': escaped.append("&amp;"); break;
                case '"': escaped.append("&quot;"); break;
                case '<': escaped.append("&lt;"); break;
                case '>': escaped.append("&gt;"); break;
                default: escaped.append(value.charAt(i));
            }
        }
        return escaped.toString();
    }

    private static String escapeJson(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': escaped.append("\\\""); break;
                case '\\': escaped.append("\\\\"); break;
                case '\b': escaped.append("\\b"); break;
                case '\f': escaped.append("\\f"); break;
                case '\n': escaped.append("\\n"); break;
                case '\r': escaped.append("\\r"); break;
                case '\t': escaped.append("\\t"); break;
                default:
                    if (c < 0x20)
                        escaped.append(String.format("\\u%04x", (int) c));
                    else
                        escaped.append(c);
            }
        }
        return escaped.toString();
    }

    private String readSessionCookie(HttpServletRequest request) {
        return readCookie(request, cookieName);
    }

    private static String readCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null)
            return null;
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName()))
                return cookie.getValue();
        }
        return null;
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void createSession(HttpServletRequest request, HttpServletResponse response, String username) {
        String previousToken = readSessionCookie(request);
        if (previousToken != null)
            sessions.remove(previousToken);
        String token = newToken();
        long now = System.currentTimeMillis();
        sessions.put(token, new SessionRecord(username, now));
        setSessionCookie(response, token);
    }

    private UserIdentity withoutCredentials(String username) {
        return new CredentialFreeUserIdentity(username,
                new HashSet<>(securityManager.getUserInfo(username).getRoles()));
    }

    private void setSessionCookie(HttpServletResponse response, String value) {
        response.addHeader("Set-Cookie", cookieName + "=" + value + cookieAttributes());
    }

    private void expireCookie(HttpServletResponse response) {
        response.addHeader("Set-Cookie", cookieName + "=; Max-Age=0" + cookieAttributes());
    }

    private String cookieAttributes() {
        return "; Path=/; HttpOnly; SameSite=" + sameSite + (secureCookie ? "; Secure" : "");
    }

    private void pruneExpiredSessions() {
        long now = System.currentTimeMillis();
        if (now < nextPruneAt)
            return;
        nextPruneAt = now + PRUNE_INTERVAL_MILLIS;
        sessions.entrySet().removeIf(entry ->
                entry.getValue().isExpired(now, idleTimeoutMillis, absoluteTimeoutMillis));
    }

    void close() {
        sessions.clear();
    }

    private static final class SessionRecord {
        final String username;
        final long createdAt;
        volatile long lastAccessAt;

        SessionRecord(String username, long now) {
            this.username = username;
            this.createdAt = now;
            this.lastAccessAt = now;
        }

        boolean isExpired(long now, long idleTimeout, long absoluteTimeout) {
            return now - lastAccessAt >= idleTimeout || now - createdAt >= absoluteTimeout;
        }
    }

    private static final class CredentialFreeUserIdentity implements UserIdentity {
        private final Principal principal;
        private final Set<String> roles;
        private final Subject subject;

        CredentialFreeUserIdentity(String username, Set<String> roles) {
            this.principal = new NamedPrincipal(username);
            this.roles = roles;
            this.subject = new Subject();
            this.subject.getPrincipals().add(principal);
            this.subject.setReadOnly();
        }

        @Override
        public Subject getSubject() {
            return subject;
        }

        @Override
        public Principal getUserPrincipal() {
            return principal;
        }

        @Override
        public boolean isUserInRole(String role, Scope scope) {
            return roles.contains(role);
        }
    }

    private static final class LoginCopy {
        final String lang;
        final String title;
        final String subtitle;
        final String error;
        final String username;
        final String password;
        final String submit;
        final String languageAria;

        private LoginCopy(String lang, String title, String subtitle, String error,
                          String username, String password, String submit, String languageAria) {
            this.lang = lang;
            this.title = title;
            this.subtitle = subtitle;
            this.error = error;
            this.username = username;
            this.password = password;
            this.submit = submit;
            this.languageAria = languageAria;
        }

        private static final LoginCopy EN = new LoginCopy(
                "en",
                "OSCAR sign in",
                "Sign in to continue.",
                "Invalid username or password.",
                "Username",
                "Password",
                "Sign in",
                "Language");

        private static final LoginCopy ES = new LoginCopy(
                "es",
                "Iniciar sesi\u00f3n en OSCAR",
                "Inicie sesi\u00f3n para continuar.",
                "Usuario o contrase\u00f1a no v\u00e1lidos.",
                "Usuario",
                "Contrase\u00f1a",
                "Iniciar sesi\u00f3n",
                "Idioma");

        private static final LoginCopy FR = new LoginCopy(
                "fr",
                "Connexion OSCAR",
                "Connectez-vous pour continuer.",
                "Nom d\u2019utilisateur ou mot de passe incorrect.",
                "Utilisateur",
                "Mot de passe",
                "Se connecter",
                "Langue");

        private static final LoginCopy EL = new LoginCopy(
                "el",
                "\u03a3\u03cd\u03bd\u03b4\u03b5\u03c3\u03b7 OSCAR",
                "\u03a3\u03c5\u03bd\u03b4\u03b5\u03b8\u03b5\u03af\u03c4\u03b5 \u03b3\u03b9\u03b1 \u03c3\u03c5\u03bd\u03ad\u03c7\u03b5\u03b9\u03b1.",
                "\u039c\u03b7 \u03ad\u03b3\u03ba\u03c5\u03c1\u03bf \u03cc\u03bd\u03bf\u03bc\u03b1 \u03c7\u03c1\u03ae\u03c3\u03c4\u03b7 \u03ae \u03ba\u03c9\u03b4\u03b9\u03ba\u03cc\u03c2.",
                "\u038c\u03bd\u03bf\u03bc\u03b1 \u03c7\u03c1\u03ae\u03c3\u03c4\u03b7",
                "\u039a\u03c9\u03b4\u03b9\u03ba\u03cc\u03c2 \u03c0\u03c1\u03cc\u03c3\u03b2\u03b1\u03c3\u03b7\u03c2",
                "\u03a3\u03cd\u03bd\u03b4\u03b5\u03c3\u03b7",
                "\u0393\u03bb\u03ce\u03c3\u03c3\u03b1");

        static LoginCopy forLanguage(String lang) {
            if ("es".equals(lang))
                return ES;
            if ("fr".equals(lang))
                return FR;
            if ("el".equals(lang))
                return EL;
            return EN;
        }
    }

    private static final class NamedPrincipal implements Principal {
        private final String name;

        NamedPrincipal(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String toString() {
            return name;
        }
    }
}
