package com.codeshield.calculator;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.io.IOUtils;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Calculator Lambda for exercising CodeShield AI, served over HTTPS through a Lambda Function URL.
 *
 * <pre>
 *   GET /                            Basic keypad calculator (HTML) + running library versions
 *   GET /app.js                      Calculator script (separate file so the page can keep a strict CSP)
 *   GET /api/calc?a=12&b=3&op=divide JSON result: {"expression":"12 ÷ 3","result":"4", ...}
 *   GET /api/health                  JSON health: status, library versions, self-tests (503 if any fail)
 * </pre>
 *
 * Direct invocations ({"a":"2","b":"3","op":"add"}) return the result map, so the CI/CD smoke test works.
 *
 * The vulnerabilities live in the outdated DEPENDENCY VERSIONS (see pom.xml), which is what Amazon Inspector
 * detects. Caller input is never logged, never interpolated, never deserialised polymorphically, and is
 * validated as a plain number before use.
 */
public class Handler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final Logger LOG = LogManager.getLogger(Handler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, Object> CONFIG = loadConfig();
    private static final Calculator CALC = new Calculator(
            intConfig("precision", 16), intConfig("maxExponent", 100), operationsConfig());

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        if (!isHttpEvent(event)) {
            // Direct invoke (CLI / CI smoke test)
            Map<String, Object> in = event == null ? Map.of() : event;
            try {
                return CALC.calculate(str(in.getOrDefault("a", "2")), str(in.getOrDefault("b", "2")),
                                      str(in.getOrDefault("op", "add")));
            } catch (Calculator.CalcException e) {
                return Map.of("error", e.getMessage());
            }
        }
        try {
            return route(event);
        } catch (Exception e) {
            LOG.error("Unhandled error", e);
            return json(500, Map.of("error", "Internal server error"));
        }
    }

    private Map<String, Object> route(Map<String, Object> event) {
        String method = httpMethod(event);
        String path   = str(event.get("rawPath"));
        if (path.isEmpty()) {
            path = "/";
        }
        LOG.info("{} {}", method, path.length() > 100 ? path.substring(0, 100) : path);   // never log query values

        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            return json(405, Map.of("error", "Method not allowed", "allowed", "GET"));
        }

        switch (path) {
            case "/":
                return response(200, "text/html; charset=utf-8", renderPage(DependencyCheck.runAll()));
            case "/app.js":
                return response(200, "application/javascript; charset=utf-8", APP_JS);
            case "/api/calc":
                try {
                    return json(200, CALC.calculate(query(event, "a"), query(event, "b"), query(event, "op")));
                } catch (Calculator.CalcException e) {
                    return json(400, Map.of("error", e.getMessage()));
                }
            case "/api/health": {
                List<DependencyCheck.Result> checks = DependencyCheck.runAll();
                boolean ok = checks.stream().allMatch(DependencyCheck.Result::ok);
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("status", ok ? "UP" : "DEGRADED");
                body.put("service", CONFIG.getOrDefault("service", "calculator"));
                body.put("operations", CALC.operations());
                body.put("timestamp", Instant.now().toString());
                body.put("dependencies", checks.stream().map(DependencyCheck.Result::toMap).toList());
                return json(ok ? 200 : 503, body);
            }
            case "/favicon.ico":
                return Map.of("statusCode", 204);
            default:
                return json(404, Map.of("error", "Not found",
                        "routes", List.of("/", "/api/calc?a=<n>&b=<n>&op=<op>", "/api/health")));
        }
    }

    // ── UI ─────────────────────────────────────────────────────────────────────────────────

    private String renderPage(List<DependencyCheck.Result> checks) {
        boolean allOk = checks.stream().allMatch(DependencyCheck.Result::ok);
        StringBuilder rows = new StringBuilder();
        for (DependencyCheck.Result r : checks) {
            rows.append("<tr><td>").append(esc(r.artifact())).append("</td><td><code>").append(esc(r.version()))
                .append("</code></td><td class=\"").append(r.ok() ? "ok" : "fail").append("\">")
                .append(r.ok() ? "&#10004;" : "&#10008; " + esc(r.error())).append("</td></tr>");
        }
        String title = esc(String.valueOf(CONFIG.getOrDefault("title", "Calculator")));

        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>%s</title>
              <style>
                body { font-family: system-ui, -apple-system, Segoe UI, Roboto, sans-serif; background: #eef1f5; margin: 0;
                       display: flex; flex-direction: column; align-items: center; padding: 2rem 1rem; color: #1f2937; }
                h1 { font-size: 1.2rem; margin: 0 0 1rem; }
                .calc { background: #1f2937; border-radius: 16px; padding: 1rem; width: 280px; box-shadow: 0 8px 24px rgba(0,0,0,.15); }
                .display { background: #111827; color: #f9fafb; border-radius: 10px; padding: .75rem; text-align: right; margin-bottom: .75rem; min-height: 4.2rem; }
                .expr { color: #9ca3af; font-size: .85rem; min-height: 1.1rem; overflow-wrap: anywhere; }
                .value { font-size: 2rem; font-weight: 600; overflow-wrap: anywhere; }
                .value.error { font-size: 1rem; color: #fca5a5; }
                .keys { display: grid; grid-template-columns: repeat(4, 1fr); gap: .5rem; }
                button { font-size: 1.2rem; padding: .85rem 0; border: 0; border-radius: 10px; background: #374151; color: #f9fafb; cursor: pointer; }
                button:hover { background: #4b5563; } button:focus-visible { outline: 3px solid #93c5fd; outline-offset: 1px; }
                button.op { background: #f59e0b; color: #111827; } button.op:hover { background: #fbbf24; }
                button.fn { background: #6b7280; } button.eq { background: #10b981; color: #052e16; }
                button.zero { grid-column: span 2; }
                .status { margin-top: 1.25rem; width: 280px; font-size: .8rem; background: #fff; border-radius: 10px; padding: .6rem .8rem; }
                .status h2 { font-size: .85rem; margin: 0 0 .4rem; }
                .badge { font-weight: 700; } .up { color: #15803d; } .down { color: #b91c1c; }
                table { width: 100%%; border-collapse: collapse; } td { padding: .15rem 0; }
                .ok { color: #15803d; text-align: right; } .fail { color: #b91c1c; text-align: right; }
                .muted { color: #6b7280; }
              </style>
            </head>
            <body>
              <h1>%s</h1>
              <div class="calc">
                <div class="display" aria-live="polite">
                  <div class="expr" id="expr"></div>
                  <div class="value" id="value">0</div>
                </div>
                <div class="keys">
                  <button class="fn" data-action="clear" aria-label="Clear">C</button>
                  <button class="fn" data-action="back" aria-label="Delete last digit">&#9003;</button>
                  <button class="op" data-op="modulo" aria-label="Modulo">mod</button>
                  <button class="op" data-op="divide" aria-label="Divide">&divide;</button>
                  <button data-digit="7">7</button><button data-digit="8">8</button><button data-digit="9">9</button>
                  <button class="op" data-op="multiply" aria-label="Multiply">&times;</button>
                  <button data-digit="4">4</button><button data-digit="5">5</button><button data-digit="6">6</button>
                  <button class="op" data-op="subtract" aria-label="Subtract">&minus;</button>
                  <button data-digit="1">1</button><button data-digit="2">2</button><button data-digit="3">3</button>
                  <button class="op" data-op="add" aria-label="Add">+</button>
                  <button class="zero" data-digit="0">0</button>
                  <button data-action="dot" aria-label="Decimal point">.</button>
                  <button class="eq" data-action="equals" aria-label="Equals">=</button>
                </div>
              </div>

              <div class="status">
                <h2>Service <span class="badge %s">&#9679; %s</span></h2>
                <table>%s</table>
                <div class="muted">Library versions running in this deployment.</div>
              </div>
              <script src="/app.js"></script>
            </body>
            </html>
            """.formatted(title, title, allOk ? "up" : "down", allOk ? "UP" : "DEGRADED", rows);
    }

    /** Keypad logic: builds "a op b" and asks the Lambda to compute it via /api/calc. */
    private static final String APP_JS = """
        (function () {
          var exprEl = document.getElementById('expr');
          var valueEl = document.getElementById('value');
          var symbols = { add: '+', subtract: '\\u2212', multiply: '\\u00d7', divide: '\\u00f7', modulo: 'mod' };
          var current = '0', first = null, op = null, fresh = true;

          function show(v, isError) { valueEl.textContent = v; valueEl.classList.toggle('error', !!isError); }
          function showExpr() { exprEl.textContent = first !== null ? first + ' ' + symbols[op] : ''; }

          function digit(d) {
            if (fresh || current === '0') { current = d; fresh = false; } else if (current.length < 30) { current += d; }
            show(current);
          }
          function dot() {
            if (fresh) { current = '0'; fresh = false; }
            if (current.indexOf('.') === -1) { current += '.'; }
            show(current);
          }
          function back() {
            if (fresh) { return; }
            current = current.length > 1 ? current.slice(0, -1) : '0';
            show(current);
          }
          function clear() { current = '0'; first = null; op = null; fresh = true; showExpr(); show('0'); }

          function compute(a, b, operation) {
            var url = '/api/calc?a=' + encodeURIComponent(a) + '&b=' + encodeURIComponent(b) + '&op=' + operation;
            return fetch(url).then(function (r) { return r.json(); });
          }

          function setOp(nextOp) {
            if (first !== null && !fresh) {
              equals(nextOp);
              return;
            }
            first = current; op = nextOp; fresh = true; showExpr();
          }

          function equals(chainOp) {
            if (first === null || op === null) { return; }
            var a = first, b = current, operation = op;
            exprEl.textContent = a + ' ' + symbols[operation] + ' ' + b + ' =';
            compute(a, b, operation).then(function (res) {
              if (res.error) { show(res.error, true); first = null; op = null; fresh = true; current = '0'; return; }
              current = res.result; fresh = true;
              if (chainOp) { first = current; op = chainOp; showExpr(); } else { first = null; op = null; }
              show(res.result);
            }).catch(function () { show('Network error', true); });
          }

          document.querySelector('.keys').addEventListener('click', function (e) {
            var b = e.target.closest('button');
            if (!b) { return; }
            if (b.dataset.digit) { digit(b.dataset.digit); }
            else if (b.dataset.op) { setOp(b.dataset.op); }
            else if (b.dataset.action === 'dot') { dot(); }
            else if (b.dataset.action === 'back') { back(); }
            else if (b.dataset.action === 'clear') { clear(); }
            else if (b.dataset.action === 'equals') { equals(); }
          });

          document.addEventListener('keydown', function (e) {
            var k = e.key, map = { '+': 'add', '-': 'subtract', '*': 'multiply', '/': 'divide', '%': 'modulo' };
            if (/^[0-9]$/.test(k)) { digit(k); }
            else if (k === '.') { dot(); }
            else if (map[k]) { e.preventDefault(); setOp(map[k]); }
            else if (k === 'Enter' || k === '=') { e.preventDefault(); equals(); }
            else if (k === 'Backspace') { back(); }
            else if (k === 'Escape') { clear(); }
          });
        })();
        """;

    // ── Helpers ────────────────────────────────────────────────────────────────────────────

    private static boolean isHttpEvent(Map<String, Object> event) {
        return event != null && event.get("requestContext") instanceof Map<?, ?> rc && rc.get("http") instanceof Map;
    }

    private static String httpMethod(Map<String, Object> event) {
        if (event.get("requestContext") instanceof Map<?, ?> rc && rc.get("http") instanceof Map<?, ?> http) {
            return str(http.get("method")).toUpperCase();
        }
        return "GET";
    }

    private static String query(Map<String, Object> event, String key) {
        return event.get("queryStringParameters") instanceof Map<?, ?> q ? str(q.get(key)) : "";
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static String esc(String s) {
        return StringEscapeUtils.escapeHtml4(s == null ? "" : s);   // commons-text
    }

    private Map<String, Object> json(int status, Object body) {
        try {
            return response(status, "application/json; charset=utf-8", MAPPER.writeValueAsString(body));   // jackson
        } catch (Exception e) {
            LOG.warn("Could not serialise response", e);
            return response(500, "application/json; charset=utf-8", "{\"error\":\"serialisation failed\"}");
        }
    }

    private static Map<String, Object> response(int status, String contentType, String body) {
        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put("Content-Type", contentType);
        headers.put("Cache-Control", "no-store");
        headers.put("X-Content-Type-Options", "nosniff");
        headers.put("Content-Security-Policy",
                "default-src 'none'; script-src 'self'; connect-src 'self'; style-src 'unsafe-inline'; form-action 'self'");
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("statusCode", status);
        resp.put("headers", headers);
        resp.put("body", body);
        return resp;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadConfig() {
        try (InputStream in = Handler.class.getResourceAsStream("/config.yml")) {
            if (in == null) {
                return Map.of();
            }
            String text = IOUtils.toString(in, StandardCharsets.UTF_8);    // commons-io
            Object loaded = new Yaml().load(text);                          // snakeyaml (bundled file only)
            return loaded instanceof Map ? (Map<String, Object>) loaded : Map.of();
        } catch (Exception e) {
            LOG.warn("Could not load config.yml", e);
            return Map.of();
        }
    }

    private static int intConfig(String key, int fallback) {
        return CONFIG.get(key) instanceof Number n ? n.intValue() : fallback;
    }

    @SuppressWarnings("unchecked")
    private static List<String> operationsConfig() {
        return CONFIG.get("operations") instanceof List<?> l
                ? l.stream().map(String::valueOf).toList()
                : List.copyOf(Calculator.SYMBOLS.keySet());
    }
}
