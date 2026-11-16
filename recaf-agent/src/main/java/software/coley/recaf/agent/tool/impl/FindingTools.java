package software.coley.recaf.agent.tool.impl;

import jakarta.annotation.Nonnull;
import software.coley.recaf.agent.state.Finding;
import software.coley.recaf.agent.tool.AgentTool;
import software.coley.recaf.agent.tool.ToolContext;
import software.coley.recaf.agent.tool.ToolException;
import software.coley.recaf.agent.tool.ToolProvider;
import software.coley.recaf.agent.tool.ToolSchema;
import software.coley.recaf.agent.tool.support.Lookups;

import java.util.List;

/**
 * Tools for recording what an analysis turned up.
 *
 * @author Recaf agent tooling
 */
public final class FindingTools implements ToolProvider {
	private final ToolContext context;

	/**
	 * @param context
	 * 		Context the tools operate in.
	 */
	public FindingTools(@Nonnull ToolContext context) {
		this.context = context;
	}

	@Nonnull
	@Override
	public List<AgentTool> tools() {
		return List.of(recordFinding(), listFindings(), removeFinding());
	}

	@Nonnull
	private AgentTool recordFinding() {
		return AgentTool.builder("record_finding")
				.description("Record a noteworthy discovery about the analyzed program, such as suspicious behavior, " +
						"a hardcoded secret, an entry point, or an insight into how an obfuscated part works. " +
						"Findings are shown to the user as they are recorded and persist for the whole session, " +
						"so record things when you establish them rather than only summarizing at the end. " +
						"Put the evidence in 'detail': what the code does and where.")
				.schema(ToolSchema.object()
						.choice("severity", "How much this matters. Use 'info' for neutral observations about structure or behavior.",
								"info", "low", "medium", "high", "critical")
						.string("title", "One-line summary of the finding.")
						.string("detail", "Explanation with supporting evidence, such as the relevant code and what it does.")
						.optString("class", "Name of the class the finding is about, if it is about one.")
						.optString("member", "Name and descriptor of the field or method the finding is about, if it is about one. " +
								"For example 'decrypt(Ljava/lang/String;)Ljava/lang/String;'."))
				// Not marked as mutating: findings are the agent's notes, they leave the workspace as it is.
				.handler(args -> {
					String className = args.optString("class", null);
					if (className != null && !className.isBlank())
						className = Lookups.internalName(className);
					Finding finding = context.state().addFinding(
							Finding.Severity.parse(args.string("severity")),
							args.string("title"),
							args.string("detail"),
							className,
							args.optString("member", null));
					return "Recorded finding #" + finding.id() + ".";
				})
				.build();
	}

	@Nonnull
	private AgentTool listFindings() {
		return AgentTool.builder("list_findings")
				.description("List the findings recorded so far in this session, most severe first. " +
						"Use it to recall what has been established before writing a summary or continuing earlier work.")
				.handler(args -> {
					List<Finding> findings = context.state().findings();
					if (findings.isEmpty())
						return "No findings have been recorded yet.";
					findings.sort((a, b) -> {
						int bySeverity = b.severity().compareTo(a.severity());
						return bySeverity != 0 ? bySeverity : Integer.compare(a.id(), b.id());
					});
					StringBuilder sb = new StringBuilder(findings.size() + " findings:\n");
					for (Finding finding : findings) {
						sb.append("\n#").append(finding.id()).append(" [").append(finding.severity().name().toLowerCase()).append("] ")
								.append(finding.title()).append('\n');
						if (finding.className() != null) {
							sb.append("  at ").append(finding.className());
							if (finding.member() != null)
								sb.append('.').append(finding.member());
							sb.append('\n');
						}
						sb.append("  ").append(finding.detail().replace("\n", "\n  ")).append('\n');
					}
					return sb.toString().stripTrailing();
				})
				.build();
	}

	@Nonnull
	private AgentTool removeFinding() {
		return AgentTool.builder("remove_finding")
				.description("Remove a recorded finding that turned out to be wrong or redundant.")
				.schema(ToolSchema.object().integer("id", "Number of the finding, as shown by list_findings."))
				.handler(args -> {
					int id = args.integer("id");
					if (!context.state().removeFinding(id))
						throw new ToolException("There is no finding #" + id + ".");
					return "Removed finding #" + id + ".";
				})
				.build();
	}
}
