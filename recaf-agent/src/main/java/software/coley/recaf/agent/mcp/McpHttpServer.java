package software.coley.recaf.agent.mcp;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Serves an {@link McpServer} over the Model Context Protocol's Streamable HTTP transport.
 * <p>
 * This is a deliberately small HTTP/1.1 server on plain sockets. The JDK's built-in HTTP server does not exist on
 * Android, and the transport needs very little: a single endpoint that takes a JSON-RPC message per {@code POST}
 * and answers with JSON. The server never initiates messages, so the optional {@code GET} event stream of the
 * transport is declined with {@code 405} as the specification allows.
 * <p>
 * Security model:
 * <ul>
 *     <li>By default the server only listens on the loopback interface.</li>
 *     <li>If a token is set, every request to the endpoint must carry it as {@code Authorization: Bearer <token>}.
 *     Listening beyond loopback without a token is refused, since the tools can read and rewrite the workspace.</li>
 *     <li>Requests carrying an {@code Origin} header are only accepted from loopback origins, which keeps web pages
 *     from reaching the server through the browser <i>(DNS rebinding)</i>.</li>
 * </ul>
 *
 * @author Recaf agent tooling
 */
public final class McpHttpServer implements AutoCloseable {
	public static final String ENDPOINT = "/mcp";
	public static final int DEFAULT_PORT = 8765;
	private static final int MAX_HEADER_BYTES = 32 * 1024;
	private static final int MAX_BODY_BYTES = 32 * 1024 * 1024;
	private static final int IDLE_TIMEOUT_MILLIS = 60_000;
	private static final int MAX_CONNECTIONS = 32;
	private static final Logger logger = Logging.get(McpHttpServer.class);
	private final McpServer server;
	private final String token;
	private final Set<Socket> connections = ConcurrentHashMap.newKeySet();
	private final AtomicInteger threadCounter = new AtomicInteger();
	private ServerSocket serverSocket;
	private ExecutorService workers;
	private Thread acceptThread;
	private volatile boolean running;

	/**
	 * @param server
	 * 		Protocol handler to serve.
	 * @param token
	 * 		Bearer token clients must present, or {@code null} to not require one.
	 */
	public McpHttpServer(@Nonnull McpServer server, @Nullable String token) {
		this.server = server;
		this.token = token == null || token.isBlank() ? null : token;
	}

	/**
	 * Starts listening.
	 *
	 * @param port
	 * 		Port to listen on. Pass {@code 0} to let the system pick a free one.
	 * @param loopbackOnly
	 *        {@code true} to only accept connections from this device.
	 *        {@code false} to accept connections from the network, which requires a token.
	 *
	 * @throws IOException
	 * 		When the port cannot be bound.
	 * @throws IllegalStateException
	 * 		When asked to listen on the network without a token, or when already running.
	 */
	public synchronized void start(int port, boolean loopbackOnly) throws IOException {
		if (running)
			throw new IllegalStateException("The MCP server is already running");
		if (!loopbackOnly && token == null)
			throw new IllegalStateException("Refusing to expose the MCP server to the network without an access token");

		ServerSocket socket = new ServerSocket();
		socket.setReuseAddress(true);
		// The IPv4 loopback address, spelled out: 'InetAddress.getLoopbackAddress()' is '::1' on Android, where
		// a server bound to it is out of reach for clients connecting to 127.0.0.1, 'adb forward' among them.
		InetAddress address = loopbackOnly ? InetAddress.getByAddress("localhost", new byte[]{127, 0, 0, 1}) : null;
		socket.bind(address == null ? new InetSocketAddress(port) : new InetSocketAddress(address, port));
		serverSocket = socket;
		workers = new ThreadPoolExecutor(0, MAX_CONNECTIONS, 30, TimeUnit.SECONDS, new SynchronousQueue<>(), runnable -> {
			Thread thread = new Thread(runnable, "recaf-mcp-" + threadCounter.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		});
		running = true;
		acceptThread = new Thread(this::acceptLoop, "recaf-mcp-accept");
		acceptThread.setDaemon(true);
		acceptThread.start();
		logger.info("MCP server listening on port {} ({})", getPort(), loopbackOnly ? "this device only" : "network");
	}

	/**
	 * @return {@code true} while the server is accepting connections.
	 */
	public boolean isRunning() {
		return running;
	}

	/**
	 * @return The port the server listens on, or {@code -1} when it is not running.
	 */
	public synchronized int getPort() {
		return serverSocket == null ? -1 : serverSocket.getLocalPort();
	}

	/**
	 * @return {@code true} when clients must present a token.
	 */
	public boolean requiresToken() {
		return token != null;
	}

	/**
	 * Stops listening and closes open connections.
	 */
	@Override
	public synchronized void close() {
		if (!running)
			return;
		running = false;
		closeQuietly(serverSocket);
		for (Socket connection : connections)
			closeQuietly(connection);
		connections.clear();
		workers.shutdownNow();
		serverSocket = null;
		logger.info("MCP server stopped");
	}

	private void acceptLoop() {
		ServerSocket socket = serverSocket;
		while (running) {
			try {
				Socket connection = socket.accept();
				connections.add(connection);
				try {
					workers.execute(() -> serve(connection));
				} catch (RuntimeException rejected) {
					// All workers are busy. Tell the client rather than leaving it hanging.
					try {
						respond(connection.getOutputStream(), 503, "Service Unavailable", "text/plain",
								bytes("Too many connections"), Map.of(), false);
					} catch (IOException ignored) {
						// Nothing more to do for this client.
					}
					connections.remove(connection);
					closeQuietly(connection);
				}
			} catch (IOException ex) {
				if (running)
					logger.warn("MCP server failed to accept a connection", ex);
			}
		}
	}

	private void serve(@Nonnull Socket connection) {
		try (connection) {
			connection.setSoTimeout(IDLE_TIMEOUT_MILLIS);
			connection.setTcpNoDelay(true);
			InputStream in = new BufferedInputStream(connection.getInputStream());
			OutputStream out = connection.getOutputStream();
			// Serve requests on this connection until the client closes it or stops talking.
			while (running) {
				Request request;
				try {
					request = readRequest(in);
				} catch (HttpException ex) {
					respond(out, ex.status, ex.reason, "text/plain", bytes(ex.getMessage()), Map.of(), false);
					return;
				}
				if (request == null)
					return;
				boolean keepAlive = !"close".equalsIgnoreCase(request.header("connection"));
				handle(request, out, keepAlive);
				if (!keepAlive)
					return;
			}
		} catch (SocketTimeoutException | SocketException | EOFException ignored) {
			// Idle or dropped connection.
		} catch (IOException ex) {
			logger.debug("MCP connection failed", ex);
		} finally {
			connections.remove(connection);
		}
	}

	private void handle(@Nonnull Request request, @Nonnull OutputStream out, boolean keepAlive) throws IOException {
		String path = request.path;
		int query = path.indexOf('?');
		if (query >= 0)
			path = path.substring(0, query);

		// Unauthenticated liveness probe, reveals nothing about the workspace.
		if (path.equals("/health")) {
			respond(out, 200, "OK", "text/plain", bytes("ok"), Map.of(), keepAlive);
			return;
		}
		if (!path.equals(ENDPOINT) && !path.equals(ENDPOINT + "/")) {
			respond(out, 404, "Not Found", "text/plain", bytes("The MCP endpoint is " + ENDPOINT), Map.of(), keepAlive);
			return;
		}
		if (!isAllowedOrigin(request.header("origin"))) {
			respond(out, 403, "Forbidden", "text/plain", bytes("Origin not allowed"), Map.of(), keepAlive);
			return;
		}
		if (!isAuthorized(request.header("authorization"))) {
			respond(out, 401, "Unauthorized", "text/plain", bytes("A valid bearer token is required"),
					Map.of("WWW-Authenticate", "Bearer"), keepAlive);
			return;
		}

		switch (request.method) {
			case "POST" -> {
				String response = server.handle(new String(request.body, StandardCharsets.UTF_8));
				if (response == null)
					respond(out, 202, "Accepted", null, new byte[0], Map.of(), keepAlive);
				else
					respond(out, 200, "OK", "application/json", bytes(response), Map.of(), keepAlive);
			}
			// The server has nothing to push and keeps no sessions.
			case "GET", "DELETE" -> respond(out, 405, "Method Not Allowed", "text/plain",
					bytes("This server only accepts POST"), Map.of("Allow", "POST"), keepAlive);
			default -> respond(out, 405, "Method Not Allowed", "text/plain",
					bytes("Unsupported method"), Map.of("Allow", "POST"), keepAlive);
		}
	}

	private boolean isAuthorized(@Nullable String authorization) {
		if (token == null)
			return true;
		if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7))
			return false;
		// Constant-time comparison, the token is the only thing between the network and the workspace.
		return MessageDigest.isEqual(bytes(authorization.substring(7).trim()), bytes(token));
	}

	/**
	 * @param origin
	 * 		Value of the {@code Origin} header, if any.
	 *
	 * @return {@code true} for requests without an origin <i>(not sent by a browser)</i> and for loopback origins.
	 */
	static boolean isAllowedOrigin(@Nullable String origin) {
		if (origin == null || origin.isBlank())
			return true;
		try {
			String host = URI.create(origin.trim()).getHost();
			if (host == null)
				return false;
			host = host.toLowerCase(Locale.ROOT);
			return host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]") || host.equals("::1");
		} catch (IllegalArgumentException ex) {
			return false;
		}
	}

	/**
	 * @return The next request on the connection, or {@code null} when the client closed it cleanly.
	 */
	@Nullable
	private static Request readRequest(@Nonnull InputStream in) throws IOException, HttpException {
		String requestLine = readLine(in, MAX_HEADER_BYTES);
		if (requestLine == null)
			return null;
		// Tolerate a stray blank line between requests.
		if (requestLine.isEmpty()) {
			requestLine = readLine(in, MAX_HEADER_BYTES);
			if (requestLine == null)
				return null;
		}
		String[] parts = requestLine.split(" ");
		if (parts.length != 3 || !parts[2].startsWith("HTTP/1."))
			throw new HttpException(400, "Bad Request", "Malformed request line");

		Map<String, String> headers = new LinkedHashMap<>();
		int headerBytes = requestLine.length();
		String line;
		while ((line = readLine(in, MAX_HEADER_BYTES)) != null && !line.isEmpty()) {
			headerBytes += line.length();
			if (headerBytes > MAX_HEADER_BYTES)
				throw new HttpException(431, "Request Header Fields Too Large", "Headers too large");
			int colon = line.indexOf(':');
			if (colon <= 0)
				throw new HttpException(400, "Bad Request", "Malformed header");
			headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
		}
		if (line == null)
			throw new EOFException();

		byte[] body;
		String transferEncoding = headers.get("transfer-encoding");
		if (transferEncoding != null && transferEncoding.toLowerCase(Locale.ROOT).contains("chunked")) {
			body = readChunked(in);
		} else {
			int length = 0;
			String contentLength = headers.get("content-length");
			if (contentLength != null) {
				try {
					length = Integer.parseInt(contentLength);
				} catch (NumberFormatException ex) {
					throw new HttpException(400, "Bad Request", "Malformed Content-Length");
				}
			}
			if (length < 0 || length > MAX_BODY_BYTES)
				throw new HttpException(413, "Content Too Large", "Request body too large");
			body = readFully(in, length);
		}
		return new Request(parts[0].toUpperCase(Locale.ROOT), parts[1], headers, body);
	}

	@Nonnull
	private static byte[] readChunked(@Nonnull InputStream in) throws IOException, HttpException {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		while (true) {
			String sizeLine = readLine(in, 1024);
			if (sizeLine == null)
				throw new EOFException();
			int extension = sizeLine.indexOf(';');
			if (extension >= 0)
				sizeLine = sizeLine.substring(0, extension);
			int size;
			try {
				size = Integer.parseInt(sizeLine.trim(), 16);
			} catch (NumberFormatException ex) {
				throw new HttpException(400, "Bad Request", "Malformed chunk size");
			}
			if (size < 0 || body.size() + (long) size > MAX_BODY_BYTES)
				throw new HttpException(413, "Content Too Large", "Request body too large");
			if (size == 0) {
				// Skip trailers up to the terminating blank line.
				String trailer;
				while ((trailer = readLine(in, MAX_HEADER_BYTES)) != null && !trailer.isEmpty()) ;
				return body.toByteArray();
			}
			body.write(readFully(in, size));
			readLine(in, 16); // CRLF after the chunk data
		}
	}

	@Nonnull
	private static byte[] readFully(@Nonnull InputStream in, int length) throws IOException {
		byte[] data = new byte[length];
		int offset = 0;
		while (offset < length) {
			int read = in.read(data, offset, length - offset);
			if (read < 0)
				throw new EOFException();
			offset += read;
		}
		return data;
	}

	/**
	 * @return The next CRLF or LF terminated line without its terminator, or {@code null} at end of stream.
	 */
	@Nullable
	private static String readLine(@Nonnull InputStream in, int maxLength) throws IOException, HttpException {
		ByteArrayOutputStream line = new ByteArrayOutputStream(128);
		int b;
		while ((b = in.read()) >= 0) {
			if (b == '\n')
				break;
			if (b != '\r')
				line.write(b);
			if (line.size() > maxLength)
				throw new HttpException(431, "Request Header Fields Too Large", "Line too long");
		}
		if (b < 0 && line.size() == 0)
			return null;
		return line.toString(StandardCharsets.ISO_8859_1);
	}

	private static void respond(@Nonnull OutputStream out, int status, @Nonnull String reason, @Nullable String contentType,
	                            @Nonnull byte[] body, @Nonnull Map<String, String> extraHeaders, boolean keepAlive) throws IOException {
		StringBuilder head = new StringBuilder(160);
		head.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n");
		if (contentType != null)
			head.append("Content-Type: ").append(contentType).append(contentType.startsWith("text/") ? "; charset=utf-8" : "").append("\r\n");
		head.append("Content-Length: ").append(body.length).append("\r\n");
		head.append("Connection: ").append(keepAlive ? "keep-alive" : "close").append("\r\n");
		for (Map.Entry<String, String> header : extraHeaders.entrySet())
			head.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
		head.append("\r\n");
		out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
		out.write(body);
		out.flush();
	}

	@Nonnull
	private static byte[] bytes(@Nonnull String text) {
		return text.getBytes(StandardCharsets.UTF_8);
	}

	private static void closeQuietly(@Nullable java.io.Closeable closeable) {
		if (closeable == null)
			return;
		try {
			closeable.close();
		} catch (IOException ignored) {
			// Closing is best effort.
		}
	}

	/**
	 * Parsed HTTP request.
	 *
	 * @param method
	 * 		Upper-case request method.
	 * @param path
	 * 		Request target.
	 * @param headers
	 * 		Headers keyed by lower-case name.
	 * @param body
	 * 		Request body.
	 */
	private record Request(@Nonnull String method, @Nonnull String path,
	                       @Nonnull Map<String, String> headers, @Nonnull byte[] body) {
		@Nullable
		String header(@Nonnull String name) {
			return headers.get(name);
		}
	}

	/**
	 * A request that cannot be served, with the status to answer it with.
	 */
	private static final class HttpException extends Exception {
		private final int status;
		private final String reason;

		private HttpException(int status, @Nonnull String reason, @Nonnull String message) {
			super(message);
			this.status = status;
			this.reason = reason;
		}
	}
}
