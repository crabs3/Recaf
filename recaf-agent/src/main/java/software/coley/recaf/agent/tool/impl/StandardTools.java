package software.coley.recaf.agent.tool.impl;

import jakarta.annotation.Nonnull;
import software.coley.recaf.agent.tool.ToolContext;
import software.coley.recaf.agent.tool.ToolProvider;

import java.util.List;

/**
 * The tool groups that make up Recaf's standard agent toolbox.
 *
 * @author Recaf agent tooling
 */
public final class StandardTools {
	private StandardTools() {}

	/**
	 * @param context
	 * 		Context the tools operate in.
	 *
	 * @return All standard tool groups, in the order their tools are presented to a model.
	 */
	@Nonnull
	public static List<ToolProvider> providers(@Nonnull ToolContext context) {
		return List.of(
				new WorkspaceTools(context),
				new ClassTools(context),
				new ScriptTools(context),
				new FindingTools(context)
		);
	}
}
