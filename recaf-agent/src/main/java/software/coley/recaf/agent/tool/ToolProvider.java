package software.coley.recaf.agent.tool;

import jakarta.annotation.Nonnull;

import java.util.List;

/**
 * A group of related tools.
 *
 * @author Recaf agent tooling
 */
public interface ToolProvider {
	/**
	 * @return The tools of this group.
	 */
	@Nonnull
	List<AgentTool> tools();
}
