package dev.vspex.jira.mark.cloud;

import static dev.vspex.jira.mark.remote.JiraFailure.Code.*;

import dev.vspex.jira.mark.json.Json;
import dev.vspex.jira.mark.remote.JiraFailure;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** Bounded same-origin HTTP transport. Only idempotent reads may be retried. */
final class CloudTransport {
  private static final int MAX_BODY = 2_000_000;
  private final CloudConnection connection;
  private final int timeoutMillis;

  CloudTransport(CloudConnection connection, Duration timeout) {
    this.connection = connection;
    if (timeout.isZero() || timeout.isNegative() || timeout.toMillis() > 60_000) {
      throw new IllegalArgumentException(
          "Request timeout must be between 1 millisecond and 60 seconds");
    }
    timeoutMillis = (int) Math.max(1, timeout.toMillis());
  }

  Map<String, Object> get(String path) {
    return request("GET", path, null);
  }

  Map<String, Object> post(String path, Map<String, Object> body) {
    return request("POST", path, Json.write(body));
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> request(String method, String path, String body) {
    if (!path.startsWith("/rest/api/3/")) {
      throw new IllegalArgumentException("Invalid API path");
    }
    boolean mutation = method.equals("POST");
    byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
    if (bytes.length > MAX_BODY) {
      throw new JiraFailure(INVALID_INPUT, "Jira request exceeds the size limit");
    }
    for (int attempt = 0; attempt < 3; attempt++) {
      HttpURLConnection request = null;
      try {
        request =
            (HttpURLConnection)
                java.net.URI.create(connection.baseUrl() + path).toURL().openConnection();
        request.setInstanceFollowRedirects(false);
        request.setRequestMethod(method);
        request.setConnectTimeout(timeoutMillis);
        request.setReadTimeout(timeoutMillis);
        request.setRequestProperty("Accept", "application/json");
        request.setRequestProperty("Authorization", connection.authorization());
        if (mutation) {
          request.setDoOutput(true);
          request.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
          // Fixed streaming prevents transparent authentication/redirect resubmission.
          request.setFixedLengthStreamingMode(bytes.length);
          try (var output = request.getOutputStream()) {
            output.write(bytes);
          }
        }
        int status = request.getResponseCode();
        if (!mutation && attempt < 2 && (status == 429 || status >= 500)) {
          String retryAfter = request.getHeaderField("Retry-After");
          long delay = retryAfter == null ? 100L * (attempt + 1) : retryDelay(retryAfter);
          request.disconnect();
          if (delay < 0 || delay > 2000) {
            throw new JiraFailure(
                REMOTE_UNAVAILABLE, "Jira requested a longer retry delay; retry later");
          }
          Thread.sleep(delay);
          continue;
        }
        if (status == 401) {
          throw new JiraFailure(AUTHENTICATION, "Jira authentication failed");
        }
        if (status == 403) {
          throw new JiraFailure(PERMISSION_DENIED, "Jira denied access to this operation");
        }
        if (mutation && (status == 408 || status >= 500 || status >= 300 && status < 400)) {
          throw uncertain();
        }
        if (status < 200 || status >= 300) {
          throw new JiraFailure(
              status == 400 || status == 404 || status == 422
                  ? REMOTE_VALIDATION
                  : REMOTE_UNAVAILABLE,
              "Jira rejected the operation (HTTP "
                  + status
                  + "); verify permissions, field mappings, and choices");
        }
        if (mutation && status != 201) {
          throw uncertain();
        }
        String contentType = request.getContentType();
        if (contentType == null
            || !contentType.toLowerCase(java.util.Locale.ROOT).contains("application/json")) {
          throw new JiraFailure(
              mutation ? UNCERTAIN_CREATION : INVALID_RESPONSE,
              mutation ? uncertain().getMessage() : "Jira returned an unexpected response type");
        }
        try (var input = request.getInputStream();
            var output = new ByteArrayOutputStream()) {
          long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
          byte[] buffer = new byte[8192];
          while (true) {
            long remaining = (deadline - System.nanoTime()) / 1_000_000;
            if (remaining <= 0) {
              throw new IOException();
            }
            request.setReadTimeout((int) remaining);
            int count = input.read(buffer);
            if (count < 0) {
              break;
            }
            if (output.size() + count > MAX_BODY) {
              throw new IOException();
            }
            output.write(buffer, 0, count);
          }
          try {
            Object parsed = Json.parse(output.toString(StandardCharsets.UTF_8));
            if (!(parsed instanceof Map<?, ?>)) {
              throw new IllegalArgumentException();
            }
            return (Map<String, Object>) parsed;
          } catch (IllegalArgumentException failure) {
            throw failure;
          } catch (RuntimeException failure) {
            throw new IllegalArgumentException();
          }
        }
      } catch (IOException failure) {
        if (mutation) {
          throw uncertain();
        }
        if (attempt == 2) {
          throw new JiraFailure(
              REMOTE_UNAVAILABLE, "Cannot read Jira; check the connection and retry");
        }
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
        throw new JiraFailure(CANCELLED, "Jira operation was interrupted");
      } catch (IllegalArgumentException failure) {
        if (mutation) {
          throw uncertain();
        }
        throw new JiraFailure(INVALID_RESPONSE, "Jira returned invalid JSON");
      } finally {
        if (request != null) {
          request.disconnect();
        }
      }
    }
    throw new JiraFailure(REMOTE_UNAVAILABLE, "Cannot read Jira");
  }

  private static long retryDelay(String value) {
    try {
      return Math.multiplyExact(Long.parseLong(value), 1000);
    } catch (NumberFormatException | ArithmeticException failure) {
      return -1;
    }
  }

  static JiraFailure uncertain() {
    return new JiraFailure(
        UNCERTAIN_CREATION,
        "Jira may have created the issue. Check Jira before retrying; the request was not resubmitted");
  }
}
