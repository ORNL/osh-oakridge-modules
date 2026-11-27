/*******************************************************************************

 The contents of this file are subject to the Mozilla Public License, v. 2.0.
 If a copy of the MPL was not distributed with this file, You can obtain one
 at http://mozilla.org/MPL/2.0/.

 Software distributed under the License is distributed on an "AS IS" basis,
 WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 for the specific language governing rights and limitations under the License.

 The Initial Developer is Botts Innovative Research Inc. Portions created by the Initial
 Developer are Copyright (C) 2026 the Initial Developer. All Rights Reserved.

 ******************************************************************************/

package com.botts.impl.service.oscar.diagnostics;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Authenticated, read-only diagnostics servlet mounted at
 * {@code /sensorhub/oscar/diagnostics/*}.
 *
 * <ul>
 *   <li>{@code GET /tls-status}: returns public X.509 expiry status for the
 *       HTTPS gateway certificate so operators receive early renewal alerts on
 *       the Status of Health view without exposing any private key material.</li>
 * </ul>
 */
public final class SystemDiagnosticsServlet extends HttpServlet {

    public static final String ENDPOINT_WILDCARD = "/oscar/diagnostics/*";
    public static final String PUBLIC_SERVLET_PREFIX = "/sensorhub/oscar/diagnostics";

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final GatewayTlsCertInspector tlsInspector;
    private final Logger logger;

    public SystemDiagnosticsServlet(Logger logger) {
        this(new GatewayTlsCertInspector(), logger);
    }

    public SystemDiagnosticsServlet(GatewayTlsCertInspector tlsInspector, Logger logger) {
        this.tlsInspector = Objects.requireNonNull(tlsInspector, "tlsInspector");
        this.logger = logger;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String user = resolveUsername(req);
        if (user == null || user.isBlank()) {
            sendJsonError(resp, HttpServletResponse.SC_UNAUTHORIZED, "Authentication required");
            return;
        }
        String sub = normalizeSubpath(req);
        if ("/tls-status".equals(sub)) {
            GatewayTlsCertInspector.GatewayTlsStatus status = tlsInspector.inspect();
            sendJson(resp, HttpServletResponse.SC_OK, status.toMap());
            return;
        }
        sendJsonError(resp, HttpServletResponse.SC_NOT_FOUND, "Unknown diagnostics endpoint: " + sub);
    }

    private static String resolveUsername(HttpServletRequest req) {
        if (req == null) {
            return null;
        }
        if (req.getUserPrincipal() != null && req.getUserPrincipal().getName() != null
                && !req.getUserPrincipal().getName().isBlank()) {
            return req.getUserPrincipal().getName().trim();
        }
        String remote = req.getRemoteUser();
        return remote != null && !remote.isBlank() ? remote.trim() : null;
    }

    private static String normalizeSubpath(HttpServletRequest req) {
        String pathInfo = req.getPathInfo();
        if (pathInfo == null || pathInfo.isBlank()) {
            return "/";
        }
        String normalized = pathInfo.replace('\\', '/');
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static void sendJson(HttpServletResponse resp, int statusCode, Object payload) throws IOException {
        byte[] bytes = GSON.toJson(payload).getBytes(StandardCharsets.UTF_8);
        resp.setStatus(statusCode);
        resp.setContentType("application/json; charset=UTF-8");
        resp.setHeader("Cache-Control", "no-store");
        resp.setHeader("X-Content-Type-Options", "nosniff");
        resp.setContentLength(bytes.length);
        resp.getOutputStream().write(bytes);
    }

    private static void sendJsonError(HttpServletResponse resp, int statusCode, String message) throws IOException {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("error", message);
        sendJson(resp, statusCode, err);
    }
}
