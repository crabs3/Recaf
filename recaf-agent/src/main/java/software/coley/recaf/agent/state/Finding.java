package software.coley.recaf.agent.state;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

/**
 * Something noteworthy an agent discovered while analyzing a workspace.
 *
 * @param id
 * 		Identifier, unique within the {@link AgentState}.
 * @param severity
 * 		How much the finding matters.
 * @param title
 * 		Short summary.
 * @param detail
 * 		Explanation with the supporting evidence.
 * @param className
 * 		Internal name of the class the finding is about, if any.
 * @param member
 * 		Name and descriptor of the member the finding is about, if any.
 * @param timestamp
 * 		Time the finding was recorded, in milliseconds since the epoch.
 *
 * @author Recaf agent tooling
 */
public record Finding(int id, @Nonnull Severity severity, @Nonnull String title, @Nonnull String detail,
                      @Nullable String className, @Nullable String member, long timestamp) {
	/**
	 * Severity of a finding.
	 */
	public enum Severity {
		INFO, LOW, MEDIUM, HIGH, CRITICAL;

		/**
		 * @param text
		 * 		Severity name in any case.
		 *
		 * @return Matching severity, {@link #INFO} when unrecognized.
		 */
		@Nonnull
		public static Severity parse(@Nullable String text) {
			if (text != null)
				for (Severity severity : values())
					if (severity.name().equalsIgnoreCase(text.trim()))
						return severity;
			return INFO;
		}
	}
}
