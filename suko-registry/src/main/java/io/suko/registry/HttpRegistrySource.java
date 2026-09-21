package io.suko.registry;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
 *   decision is deliberately left to the caller rather than ever being
 *   inferred automatically (e.g. from the hostname being {@code localhost},
 *   which is not a security boundary an attacker couldn't also claim).
 *   {@code suko-cli} currently has no way for a user to opt into this at all
 *   — it always constructs this class with {@code allowInsecure = false} —
 *   so in practice only {@code https} registries are reachable from the CLI
 *   today; {@code allowInsecure = true} is exercised only by this module's
 *   own tests (and any consumer's tests) against a throwaway local server.</li>
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
 *   <li><b>Bounded resources.</b> A connect timeout, a read timeout, and a
 *   maximum response size are all enforced; a response larger than the
 *   limit is rejected without ever buffering more than
 *   {@code maxResponseBytes + 1} bytes in memory, regardless of how large
 *   the actual response body is. The read timeout bounds the <em>entire</em>
 *   request, headers and body together: {@code HttpRequest.Builder#timeout}
 *   only bounds the time until the response headers arrive when the body is
 *   read as a stream (as it is here), so a server that sends headers
 *   immediately and then trickles the body arbitrarily slowly would
 *   otherwise never time out and never hit the size cap either &mdash; a
 *   real denial-of-service vector against whoever points the CLI at a
 *   malicious {@code --registry}. {@link #resolve(String)} therefore reads
 *   the body on a background thread and aborts (by closing the stream) if
 *   the shared deadline is exceeded, regardless of how many bytes have
 *   trickled in by then.</li>
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
                    "Registry base URL must use https, got scheme \"" + scheme + "\" in: " + baseUrl
                            + ". Only https registries are supported today.");
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

        // The deadline covers the whole operation - headers and body - from
        // here. HttpRequest.Builder#timeout below only bounds the time until
        // response headers arrive when the body is consumed as a stream; the
        // body-reading phase enforces the same deadline itself (see
        // readBoundedBody), which is the fix for the full-body-timeout gap.
        Instant deadline = Instant.now().plus(readTimeout);

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

            return readBoundedBody(body, target, deadline);
        }
    }

    /**
     * Reads {@code body} up to {@code maxResponseBytes + 1} bytes (so an
     * oversized response is detected without ever materializing it in
     * full), while also enforcing {@code deadline} for the entire read -
     * not just its start.
     * <p>
     * {@link InputStream#read()} has no built-in per-call timeout, so the
     * read happens on a background thread; the calling thread waits for it
     * with a bound equal to the time remaining until {@code deadline}. If
     * that expires while the reader thread is still blocked (e.g. a server
     * that sent headers immediately and is now trickling the body one byte
     * at a time, indefinitely), {@code body} is closed from this thread,
     * which is what actually unblocks the reader thread's pending
     * {@code read()} call with an {@link IOException} - closing the stream
     * is the only portable way to abort a synchronous blocking read from
     * another thread. This is deliberately not "read everything, then check
     * elapsed time": that would still let a slow-trickle response hold the
     * connection (and this thread) open indefinitely, which is exactly the
     * denial-of-service vector this method exists to close.
     * </p>
     */
    private byte[] readBoundedBody(InputStream body, URI target, Instant deadline) throws IOException {
        ExecutorService reader = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "suko-registry-http-body-reader");
            t.setDaemon(true);
            return t;
        });
        try {
            Future<byte[]> read = reader.submit(() -> body.readNBytes((int) (maxResponseBytes + 1)));

            long remainingMillis = Duration.between(Instant.now(), deadline).toMillis();
            try {
                byte[] content = read.get(Math.max(remainingMillis, 0), TimeUnit.MILLISECONDS);
                if (content.length > maxResponseBytes) {
                    throw new IOException("Response from " + target + " exceeds the maximum allowed size of "
                            + maxResponseBytes + " bytes");
                }
                return content;
            } catch (TimeoutException e) {
                try {
                    body.close();
                } catch (IOException ignored) {
                    // Already timing out; the close is only to unblock the
                    // reader thread, its own failure is not the interesting one.
                }
                read.cancel(true);
                throw new IOException("Timed out reading response body from " + target
                        + " (the read timeout of " + readTimeout + " elapsed while streaming the body)", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof IOException ioException) {
                    throw ioException;
                }
                throw new IOException("Failed reading response body from " + target, cause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while reading response body from " + target, e);
            }
        } finally {
            reader.shutdownNow();
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
