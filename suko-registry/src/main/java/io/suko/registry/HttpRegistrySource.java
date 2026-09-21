package io.suko.registry;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Objects;

/**
 * HTTPS implementation of {@link RegistrySource}. This is the network
 * counterpart of {@link FileSystemRegistrySource}: spec D1 ("a relatividade é
 * o que faz os dois caminhos serem o mesmo código") means both
 * implementations accept the exact same shape of input &mdash; a path
 * relative to a base &mdash; and share the same first line of defense via
 * {@link RegistryPaths#requireRelative(String)}.
 * <p>
 * Security properties, all deliberate:
 * </p>
 * <ul>
 *   <li><b>HTTPS only, by default.</b> The base URL's scheme must be
 *   {@code https}. Plain {@code http} is only accepted when the caller
 *   passes {@code allowInsecure = true} to the constructor. <b>The only
 *   legitimate reason to do this is pointing at a local test server (e.g.
 *   {@code localhost}) during development or in tests</b> &mdash; this class
 *   has no way to tell "trusted local test fixture" apart from "attacker on
 *   the same network downgrading a real registry to plaintext", so this
 *   decision is deliberately left to the caller (and ultimately, in the
 *   suko-cli, to an explicit {@code --allow-insecure} flag) rather than ever
 *   being inferred automatically (e.g. from the hostname being
 *   {@code localhost}, which is not a security boundary an attacker
 *   couldn't also claim).</li>
 *   <li><b>Redirects are never followed.</b> The underlying {@link
 *   HttpClient} is configured with {@link HttpClient.Redirect#NEVER}. A
 *   redirect is the HTTP equivalent of the symlink that {@link
 *   FileSystemRegistrySource} already refuses to follow past the base: both
 *   are "the thing I asked for is not actually at the place I asked for it",
 *   and both must fail loudly rather than silently be followed somewhere
 *   else. A 3xx response is therefore surfaced as a plain {@link
 *   IOException}, exactly like any other non-200 status.</li>
 *   <li><b>Path containment is checked lexically, before any network
 *   request is made.</b> {@link #resolve(String)} rejects an absolute path
 *   or a {@code relativePath} that, once resolved against the base URI,
 *   would not stay under the base's scheme, authority and path prefix. This
 *   mirrors {@link FileSystemRegistrySource}'s {@code normalize()} +
 *   {@code startsWith()} check, and runs entirely locally: a
 *   traversal attempt never reaches the server.</li>
 *   <li><b>Bounded resources.</b> A connect timeout, a request timeout, and
 *   a maximum response size are all enforced; a response larger than the
 *   limit is rejected without ever buffering more than
 *   {@code maxResponseBytes + 1} bytes in memory, regardless of how large
 *   the actual response body is.</li>
 * </ul>
 */
public final class HttpRegistrySource implements RegistrySource {

    /** 50 MiB: generous for a component's source files, small enough to bound memory use against a hostile response. */
    static final long DEFAULT_MAX_RESPONSE_BYTES = 50L * 1024 * 1024;
    static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(30);

    private static final String USER_AGENT = "suko-registry-http/1 (+https://github.com/suko-lang/suko)";

    private final URI base;
    private final HttpClient client;
    private final Duration readTimeout;
    private final long maxResponseBytes;

    /**
     * Creates an HTTPS registry source. {@code baseUrl} must use the
     * {@code https} scheme and end with {@code /}.
     *
     * @throws IllegalArgumentException if {@code baseUrl} does not end with
     *                                  {@code /}, is not a valid URI, or
     *                                  does not use the {@code https} scheme
     */
    public HttpRegistrySource(String baseUrl) {
        this(baseUrl, false);
    }

    /**
     * Creates a registry source, optionally allowing a plaintext
     * {@code http} base.
     * <p>
     * <b>{@code allowInsecure = true} must only ever be used to point at a
     * local test server.</b> It exists because suko-registry's own tests
     * (and any consumer's tests) need to stand up a throwaway
     * {@code localhost} HTTP server; it must never be set based on
     * user-supplied configuration for a real registry, and any CLI surface
     * that exposes it must require it to be spelled out explicitly (e.g. an
     * {@code --allow-insecure} flag), never defaulted or inferred.
     * </p>
     *
     * @throws IllegalArgumentException if {@code baseUrl} does not end with
     *                                  {@code /}, is not a valid URI, uses
     *                                  neither {@code https} nor
     *                                  ({@code http} with
     *                                  {@code allowInsecure}), or uses any
     *                                  other scheme
     */
    public HttpRegistrySource(String baseUrl, boolean allowInsecure) {
        this(baseUrl, allowInsecure, DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT, DEFAULT_MAX_RESPONSE_BYTES);
    }

    /**
     * Full constructor, exposed at package visibility so tests can use
     * short timeouts and small size limits instead of waiting on
     * production-sized defaults.
     */
    HttpRegistrySource(String baseUrl, boolean allowInsecure, Duration connectTimeout, Duration readTimeout,
            long maxResponseBytes) {
        Objects.requireNonNull(baseUrl, "baseUrl");
        if (!baseUrl.endsWith("/")) {
            throw new IllegalArgumentException("Registry base URL must end with \"/\", got: " + baseUrl);
        }

        URI parsedBase;
        try {
            parsedBase = new URI(baseUrl);
        } catch (java.net.URISyntaxException e) {
            throw new IllegalArgumentException("Registry base URL is not a valid URI: " + baseUrl, e);
        }

        String scheme = parsedBase.getScheme();
        if ("http".equalsIgnoreCase(scheme)) {
            if (!allowInsecure) {
                throw new IllegalArgumentException(
                        "Registry base URL \"" + baseUrl + "\" uses plain http, which is refused unless "
                                + "explicitly allowed. The only legitimate reason to allow it is a local "
                                + "test server; a real registry must use https.");
            }
        } else if (!"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException(
                    "Registry base URL must use https (or http with allowInsecure for local test servers), got scheme \""
                            + scheme + "\" in: " + baseUrl);
        }

        this.base = parsedBase;
        this.readTimeout = Objects.requireNonNull(readTimeout, "readTimeout");
        if (maxResponseBytes <= 0 || maxResponseBytes >= Integer.MAX_VALUE - 1) {
            throw new IllegalArgumentException("maxResponseBytes must be a small positive value, got: " + maxResponseBytes);
        }
        this.maxResponseBytes = maxResponseBytes;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Objects.requireNonNull(connectTimeout, "connectTimeout"))
                // A redirect is the HTTP equivalent of the symlink escape that
                // FileSystemRegistrySource already refuses to follow: it must be
                // surfaced as a failure, never silently followed.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public byte[] resolve(String relativePath) throws IOException {
        RegistryPaths.requireRelative(relativePath);

        URI target = resolveWithinBase(relativePath);

        HttpRequest request = HttpRequest.newBuilder(target)
                .header("User-Agent", USER_AGENT)
                .timeout(readTimeout)
                .GET()
                .build();

        HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException e) {
            throw new IOException("Timed out requesting registry resource " + target, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while requesting registry resource " + target, e);
        }

        try (InputStream body = response.body()) {
            int status = response.statusCode();
            if (status != 200) {
                String redirectNote = (status >= 300 && status < 400)
                        ? " (redirects are not followed; the registry base must serve content directly, not via a redirect)"
                        : "";
                throw new IOException(
                        "Registry request to " + target + " failed with HTTP status " + status + redirectNote);
            }

            // Bound the number of bytes buffered in memory regardless of how
            // large the actual response body is: read at most
            // maxResponseBytes + 1 bytes, so an oversized response is
            // detected without ever materializing it in full.
            byte[] content = body.readNBytes((int) (maxResponseBytes + 1));
            if (content.length > maxResponseBytes) {
                throw new IOException("Response from " + target + " exceeds the maximum allowed size of "
                        + maxResponseBytes + " bytes");
            }
            return content;
        }
    }

    @Override
    public String base() {
        return base.toString();
    }

    /**
     * Resolves {@code relativePath} against the base URI and verifies,
     * purely lexically (no network access), that the result still lives
     * under the base: same scheme, same authority, and a path that starts
     * with the base's path. This is the network transport's equivalent of
     * {@link FileSystemRegistrySource}'s {@code normalize()} +
     * {@code startsWith()} lexical containment check, and it runs before any
     * request is sent, so a traversal attempt never reaches the server.
     */
    private URI resolveWithinBase(String relativePath) {
        URI candidate = base.resolve(relativePath);

        if (!Objects.equals(candidate.getScheme(), base.getScheme())
                || !Objects.equals(candidate.getAuthority(), base.getAuthority())) {
            throw new IllegalArgumentException(
                    "Path \"" + relativePath + "\" resolves to a different host than the registry base \"" + base + "\"");
        }

        String candidatePath = candidate.getPath() == null ? "" : candidate.getPath();
        String basePath = base.getPath() == null ? "" : base.getPath();
        if (!candidatePath.startsWith(basePath)) {
            throw new IllegalArgumentException(
                    "Path \"" + relativePath + "\" escapes the registry base \"" + base + "\"");
        }

        return candidate;
    }
}
