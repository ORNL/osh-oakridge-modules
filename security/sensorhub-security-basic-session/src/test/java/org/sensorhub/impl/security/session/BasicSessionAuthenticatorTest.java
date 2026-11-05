package org.sensorhub.impl.security.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Authentication;
import org.junit.Test;
import org.sensorhub.api.security.ISecurityManager;
import org.slf4j.LoggerFactory;


public class BasicSessionAuthenticatorTest
{
    @Test
    public void rendersDefaultEnglishLoginWithAutofillAndStrictCsp() throws Exception
    {
        BasicSessionAuthenticator auth = createAuthenticator();
        ResponseCapture resp = new ResponseCapture();
        HttpServletRequest req = createRequest("GET", "/login", Map.of(), null, null);

        Authentication result = auth.validateRequest(req, resp.proxy(), true);

        assertEquals(Authentication.SEND_CONTINUE, result);
        assertEquals(200, resp.status);
        assertEquals(
            "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'",
            resp.headers.get("Content-Security-Policy"));

        String html = resp.bodyUtf8();
        assertTrue(html.contains("<html lang=\"en\">"));
        assertTrue(html.contains("<title>OSCAR sign in</title>"));
        assertTrue(html.contains("<p>Sign in to continue.</p>"));
        assertTrue(html.contains("<label for=\"username\">Username</label>"));
        assertTrue(html.contains("id=\"username\""));
        assertTrue(html.contains("autocomplete=\"username\""));
        assertTrue(html.contains("spellcheck=\"false\""));
        assertTrue(html.contains("<label for=\"current-password\">Password</label>"));
        assertTrue(html.contains("id=\"current-password\""));
        assertTrue(html.contains("autocomplete=\"current-password\""));
        assertTrue(html.contains("<button type=\"submit\">Sign in</button>"));
    }


    @Test
    public void explicitLangQuerySetsCookieAndRendersGreekLoginPreservingContinue() throws Exception
    {
        BasicSessionAuthenticator auth = createAuthenticator();
        ResponseCapture resp = new ResponseCapture();
        HttpServletRequest req = createRequest(
            "GET",
            "/login",
            Map.of("lang", "el", "continue", "/sensorhub/admin"),
            null,
            null);

        auth.validateRequest(req, resp.proxy(), true);

        assertEquals(200, resp.status);
        String setCookie = resp.setCookies.isEmpty() ? null : resp.setCookies.get(0);
        assertNotNull("Expected osh-language Set-Cookie header", setCookie);
        assertTrue(setCookie.startsWith("osh-language=el; Path=/;"));
        assertTrue(setCookie.contains("Secure"));

        String html = resp.bodyUtf8();
        assertTrue(html.contains("<html lang=\"el\">"));
        assertTrue(html.contains("<title>Σύνδεση OSCAR</title>"));
        assertTrue(html.contains("Όνομα χρήστη"));
        assertTrue(html.contains("Κωδικός πρόσβασης"));
        assertTrue(html.contains("<button type=\"submit\">Σύνδεση</button>"));
        assertTrue(html.contains("href=\"/login?lang=es&amp;continue=%2Fsensorhub%2Fadmin\""));
    }


    @Test
    public void resolvesCookieAndAcceptLanguageAndRendersLocalizedError() throws Exception
    {
        BasicSessionAuthenticator auth = createAuthenticator();

        ResponseCapture esResp = new ResponseCapture();
        HttpServletRequest esReq = createRequest(
            "GET",
            "/login",
            Map.of(),
            new Cookie[] {new Cookie("osh-language", "es")},
            "en-US,en;q=0.9");
        auth.validateRequest(esReq, esResp.proxy(), true);
        assertTrue(esResp.bodyUtf8().contains("<html lang=\"es\">"));
        assertTrue(esResp.bodyUtf8().contains("<title>Iniciar sesión en OSCAR</title>"));

        ResponseCapture frResp = new ResponseCapture();
        HttpServletRequest frReq = createRequest(
            "POST",
            "/login",
            Map.of("username", "operator"),
            null,
            "de-DE,fr-FR;q=0.9,en;q=0.8");
        auth.validateRequest(frReq, frResp.proxy(), true);
        assertEquals(401, frResp.status);
        assertTrue(frResp.bodyUtf8().contains("<html lang=\"fr\">"));
        assertTrue(frResp.bodyUtf8().contains("Nom d’utilisateur ou mot de passe incorrect."));
    }


    private static BasicSessionAuthenticator createAuthenticator()
    {
        BasicSessionConfig config = new BasicSessionConfig();
        ISecurityManager securityManager = (ISecurityManager) Proxy.newProxyInstance(
            ISecurityManager.class.getClassLoader(),
            new Class<?>[] {ISecurityManager.class},
            (proxy, method, args) -> null);
        return new BasicSessionAuthenticator(
            config,
            securityManager,
            LoggerFactory.getLogger(BasicSessionAuthenticatorTest.class));
    }


    private static HttpServletRequest createRequest(
            String method,
            String path,
            Map<String, String> params,
            Cookie[] cookies,
            String acceptLanguage)
    {
        return (HttpServletRequest) Proxy.newProxyInstance(
            HttpServletRequest.class.getClassLoader(),
            new Class<?>[] {HttpServletRequest.class},
            (proxy, m, args) -> {
                switch (m.getName())
                {
                    case "getMethod":
                        return method;
                    case "getContextPath":
                        return "";
                    case "getServletPath":
                    case "getRequestURI":
                        return path;
                    case "getParameter":
                        return params.get(args[0]);
                    case "getCookies":
                        return cookies;
                    case "getHeader":
                        if ("Accept-Language".equalsIgnoreCase((String) args[0]))
                            return acceptLanguage;
                        if ("Accept".equalsIgnoreCase((String) args[0]))
                            return "text/html";
                        return null;
                    default:
                        return null;
                }
            });
    }


    private static final class ResponseCapture
    {
        int status = 200;
        final Map<String, String> headers = new HashMap<>();
        final List<String> setCookies = new ArrayList<>();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();

        HttpServletResponse proxy()
        {
            ServletOutputStream servletOut = new ServletOutputStream()
            {
                @Override
                public boolean isReady()
                {
                    return true;
                }

                @Override
                public void setWriteListener(WriteListener writeListener)
                {
                }

                @Override
                public void write(int b)
                {
                    out.write(b);
                }
            };

            return (HttpServletResponse) Proxy.newProxyInstance(
                HttpServletResponse.class.getClassLoader(),
                new Class<?>[] {HttpServletResponse.class},
                (p, m, args) -> {
                    switch (m.getName())
                    {
                        case "setStatus":
                            status = (Integer) args[0];
                            return null;
                        case "sendError":
                            status = (Integer) args[0];
                            return null;
                        case "setHeader":
                            headers.put((String) args[0], (String) args[1]);
                            return null;
                        case "addHeader":
                            if ("Set-Cookie".equalsIgnoreCase((String) args[0]))
                                setCookies.add((String) args[1]);
                            headers.put((String) args[0], (String) args[1]);
                            return null;
                        case "getOutputStream":
                            return servletOut;
                        case "encodeRedirectURL":
                            return args[0];
                        default:
                            return null;
                    }
                });
        }

        String bodyUtf8()
        {
            return out.toString(StandardCharsets.UTF_8);
        }
    }
}
