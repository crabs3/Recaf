package software.coley.recaf.agent.cli;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import software.coley.recaf.Bootstrap;
import software.coley.recaf.Recaf;
import software.coley.recaf.agent.claude.AgentConfig;
import software.coley.recaf.agent.claude.AgentListener;
import software.coley.recaf.agent.claude.AgentTurn;
import software.coley.recaf.agent.claude.ClaudeAgent;
import software.coley.recaf.agent.claude.ClaudeModel;
import software.coley.recaf.agent.mcp.McpHttpServer;
import software.coley.recaf.agent.mcp.McpServer;
import software.coley.recaf.agent.prompt.Playbook;
import software.coley.recaf.agent.prompt.Playbooks;
import software.coley.recaf.agent.state.Finding;
import software.coley.recaf.agent.tool.AgentToolbox;
import software.coley.recaf.agent.tool.ToolResult;
import software.coley.recaf.cdi.InitializationEvent;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Command line entry point for the agent tooling on desktop: runs an automated analysis of a file with the
 * Claude agent, serves the tools to MCP clients, or both.
 * <pre>
 * recaf-agent app.jar --analyze                 Full static analysis, report on standard output
 * recaf-agent app.apk --analyze android-audit   One of the ready-made analysis tasks
 * recaf-agent app.jar --ask "What does it do on startup?"
 * recaf-agent app.jar --mcp --port 8765         Serve the tools over MCP until interrupted
 * </pre>
 * The API key is read from the {@code ANTHROPIC_API_KEY} environment variable.
 *
 * @author Recaf agent tooling
 */
public final class AgentCli implements AutoCloseable {
	private static final String USAGE = """
			Usage: recaf-agent <input file> [options]

			Analysis with the Claude agent (needs ANTHROPIC_API_KEY):
			  --analyze [task]        Run a ready-made analysis and print its report. Tasks: %s
			                          Default task: analyze
			  --ask <prompt>          Run the agent with your own prompt
			  --model <id>            Model to use. Default: %s
			  --effort <level>        low, medium, high, xhigh or max. Default: %s
			  --base-url <url>        API endpoint, for gateways. Default: ANTHROPIC_BASE_URL, else Anthropic's API
			  --allow-changes         Let the agent modify the workspace. Without it the agent only reads
			  --export <file name>    After the agent finishes, export the workspace into the export directory

			MCP server:
			  --mcp                   Serve the tools over the Model Context Protocol until interrupted
			  --port <number>         Port to listen on. Default: %d
			  --token <token>         Access token clients must send. Default: a new random token
			  --network               Accept connections from other hosts, not just this one

			General:
			  --export-dir <dir>      Directory exported files are written to. Default: ./recaf-exports
			  --unrestricted-scripts  Allow Recaf's native Java scripts, which run without a sandbox
			  --help                  Show this text
			""";
	private final Options options;
	private final PrintStream out;
	private AgentToolbox toolbox;
	private McpHttpServer mcpServer;

	/**
	 * @param options
	 * 		Parsed command line.
	 * @param out
	 * 		Stream that reports and status lines are printed to.
	 */
	public AgentCli(@Nonnull Options options, @Nonnull PrintStream out) {
		this.options = options;
		this.out = out;
	}

	public static void main(String[] args) {
		Options options;
		try {
			options = Options.parse(args);
		} catch (IllegalArgumentException ex) {
			System.err.println(ex.getMessage());
			System.err.println();
			System.err.println(usage());
			System.exit(2);
			return;
		}
		if (options.help) {
			System.out.println(usage());
			return;
		}

		int exitCode = 0;
		try (AgentCli cli = new AgentCli(options, System.out)) {
			cli.start();
			if (options.prompt != null)
				exitCode = cli.runAgent() ? 0 : 1;
			if (options.mcp) {
				// Serve until the process is interrupted.
				CountDownLatch stopped = new CountDownLatch(1);
				Runtime.getRuntime().addShutdownHook(new Thread(stopped::countDown, "recaf-agent-shutdown"));
				stopped.await();
			}
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		} catch (Exception ex) {
			System.err.println("Failed: " + ex.getMessage());
			exitCode = 1;
		}
		System.exit(exitCode);
	}

	/**
	 * Starts Recaf, opens the input and, when asked for, starts the MCP server.
	 *
	 * @throws IOException
	 * 		When the input cannot be opened or the MCP port cannot be bound.
	 */
	public void start() throws IOException {
		// The core's own services are all that is needed, no user interface.
		Bootstrap.enableCoreOnlyDiscovery();
		Recaf recaf = Bootstrap.get();
		recaf.getContainer().getBeanContainer().getEvent().fire(new InitializationEvent());

		Path input = options.input.toAbsolutePath().normalize();
		// The command line user chose the input, so files can be opened from anywhere.
		toolbox = AgentToolbox.create(recaf, options.exportDir.toAbsolutePath().normalize(), List.of());
		toolbox.context().setUnrestrictedScripts(options.unrestrictedScripts);
		ToolResult opened = toolbox.call("open_workspace", args("path", input.toString()));
		if (opened.error())
			throw new IOException(opened.text());
		out.println(opened.text());

		if (options.mcp) {
			mcpServer = new McpHttpServer(new McpServer(toolbox), options.token);
			mcpServer.start(options.port, !options.network);
			String url = "http://" + (options.network ? "<this host>" : "127.0.0.1") + ":" + mcpServer.getPort() + McpHttpServer.ENDPOINT;
			out.println("MCP server listening at " + url);
			out.println("Access token: " + options.token);
			out.println("Claude Code: claude mcp add --transport http recaf " + url +
					" --header \"Authorization: Bearer " + options.token + "\"");
		}
	}

	/**
	 * Runs the agent with the prompt from the command line and prints what it writes.
	 *
	 * @return {@code true} when the agent finished its work.
	 */
	public boolean runAgent() {
		String apiKey = options.apiKey;
		if (apiKey == null || apiKey.isBlank()) {
			out.println("Set the ANTHROPIC_API_KEY environment variable to run the agent.");
			return false;
		}
		AgentConfig config = new AgentConfig(apiKey, options.baseUrl, options.model, options.effort, AgentConfig.DEFAULT_MAX_TOKENS, true,
				options.allowChanges ? AgentConfig.ApprovalMode.ALLOW_CHANGES : AgentConfig.ApprovalMode.READ_ONLY);
		ClaudeAgent agent = new ClaudeAgent(toolbox, config, new AgentListener() {
			@Override
			public void onText(@Nonnull String text) {
				out.print(text);
				out.flush();
			}

			@Override
			public void onToolCallReady(@Nonnull String callId, @Nonnull String toolName, @Nonnull String argumentsJson) {
				String arguments = argumentsJson.length() > 160 ? argumentsJson.substring(0, 160) + "…" : argumentsJson;
				System.err.println("  > " + toolName + " " + arguments);
			}

			@Override
			public void onModelFallback(@Nonnull String fromModel, @Nonnull String toModel) {
				System.err.println("  ! " + fromModel + " declined this request, " + toModel + " answered instead");
			}
		}, null);

		AgentTurn turn = agent.send(options.prompt);
		out.println();
		List<Finding> findings = toolbox.context().state().findings();
		if (!findings.isEmpty())
			out.println(toolbox.call("list_findings", args()).text());
		AgentListener.Usage usage = agent.usage();
		System.err.printf("Tokens: %d in (%d from cache), %d out%s%n",
				usage.inputTokens() + usage.cacheReadTokens() + usage.cacheWriteTokens(), usage.cacheReadTokens(), usage.outputTokens(),
				usage.estimatedCost() == null ? "" : String.format(", about $%.2f", usage.estimatedCost()));
		if (turn.outcome() != AgentTurn.Outcome.COMPLETED) {
			out.println("The agent did not finish (" + turn.outcome() + ")" + (turn.detail() == null ? "." : ": " + turn.detail()));
			return false;
		}
		if (options.exportName != null)
			out.println(toolbox.call("export_workspace", args("file_name", options.exportName, "overwrite", "true")).text());
		return true;
	}

	/**
	 * @return Port the MCP server listens on, or {@code -1} when it is not running.
	 */
	public int mcpPort() {
		return mcpServer == null ? -1 : mcpServer.getPort();
	}

	/**
	 * @return The toolbox, once {@link #start()} has run.
	 */
	@Nullable
	public AgentToolbox toolbox() {
		return toolbox;
	}

	@Override
	public void close() {
		if (mcpServer != null)
			mcpServer.close();
	}

	@Nonnull
	private static String usage() {
		List<String> tasks = new ArrayList<>();
		for (Playbook playbook : Playbooks.all())
			tasks.add(playbook.id());
		return String.format(USAGE, String.join(", ", tasks), ClaudeModel.DEFAULT.id(), AgentConfig.DEFAULT_EFFORT, McpHttpServer.DEFAULT_PORT);
	}

	@Nonnull
	private static com.google.gson.JsonObject args(@Nonnull String... keyValues) {
		com.google.gson.JsonObject json = new com.google.gson.JsonObject();
		for (int i = 0; i < keyValues.length; i += 2)
			json.addProperty(keyValues[i], keyValues[i + 1]);
		return json;
	}

	/**
	 * Parsed command line.
	 */
	public static final class Options {
		Path input;
		Path exportDir = Paths.get("recaf-exports");
		String prompt;
		String model = ClaudeModel.DEFAULT.id();
		String effort = AgentConfig.DEFAULT_EFFORT;
		String apiKey = System.getenv("ANTHROPIC_API_KEY");
		String baseUrl = System.getenv("ANTHROPIC_BASE_URL");
		String exportName;
		String token;
		int port = McpHttpServer.DEFAULT_PORT;
		boolean mcp, network, allowChanges, unrestrictedScripts, help;

		/**
		 * @param apiKey
		 * 		API key to use instead of the one in the environment.
		 *
		 * @return These options.
		 */
		@Nonnull
		public Options withApiKey(@Nullable String apiKey) {
			this.apiKey = apiKey;
			return this;
		}

		/**
		 * @param args
		 * 		Command line arguments.
		 *
		 * @return Parsed options.
		 *
		 * @throws IllegalArgumentException
		 * 		When the arguments make no sense. The message says why.
		 */
		@Nonnull
		public static Options parse(@Nonnull String[] args) {
			Options options = new Options();
			for (int i = 0; i < args.length; i++) {
				String arg = args[i];
				switch (arg) {
					case "--help", "-h" -> options.help = true;
					case "--mcp" -> options.mcp = true;
					case "--network" -> options.network = true;
					case "--allow-changes" -> options.allowChanges = true;
					case "--unrestricted-scripts" -> options.unrestrictedScripts = true;
					case "--port" -> options.port = parsePort(value(args, ++i, arg));
					case "--token" -> options.token = value(args, ++i, arg);
					case "--model" -> options.model = value(args, ++i, arg);
					case "--effort" -> options.effort = value(args, ++i, arg);
					case "--base-url" -> options.baseUrl = value(args, ++i, arg);
					case "--export" -> options.exportName = value(args, ++i, arg);
					case "--export-dir" -> options.exportDir = Paths.get(value(args, ++i, arg));
					case "--ask" -> options.prompt = value(args, ++i, arg);
					case "--analyze" -> {
						// The task name is optional: '--analyze' alone, or followed by another option, means the default.
						String task = "analyze";
						if (i + 1 < args.length && Playbooks.byId(args[i + 1]) != null)
							task = args[++i];
						options.prompt = Playbooks.byId(task).prompt();
					}
					default -> {
						if (arg.startsWith("--"))
							throw new IllegalArgumentException("Unknown option: " + arg);
						if (options.input != null)
							throw new IllegalArgumentException("Only one input file can be opened, got '" + options.input + "' and '" + arg + "'");
						options.input = Paths.get(arg);
					}
				}
			}
			if (options.help)
				return options;
			if (options.input == null)
				throw new IllegalArgumentException("No input file given");
			if (!options.mcp && options.prompt == null)
				throw new IllegalArgumentException("Nothing to do: pass --analyze, --ask or --mcp");
			if (options.mcp && (options.token == null || options.token.isBlank())) {
				byte[] random = new byte[24];
				new SecureRandom().nextBytes(random);
				options.token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
			}
			return options;
		}

		@Nonnull
		private static String value(@Nonnull String[] args, int index, @Nonnull String option) {
			if (index >= args.length)
				throw new IllegalArgumentException("Option " + option + " needs a value");
			return args[index];
		}

		private static int parsePort(@Nonnull String text) {
			try {
				int port = Integer.parseInt(text);
				if (port >= 0 && port <= 65535)
					return port;
			} catch (NumberFormatException ignored) {
				// reported below
			}
			throw new IllegalArgumentException("Not a port number: " + text);
		}
	}
}
