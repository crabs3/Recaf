package software.coley.recaf.agent.state;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * State that outlives individual tool calls: the findings an agent records as it works.
 * <p>
 * Findings are the agent's durable output. Unlike conversation text they survive context compaction and can be
 * listed again, and a user interface can show them as they arrive.
 *
 * @author Recaf agent tooling
 */
public final class AgentState {
	private final List<Finding> findings = new CopyOnWriteArrayList<>();
	private final List<Consumer<List<Finding>>> listeners = new CopyOnWriteArrayList<>();
	private final AtomicInteger nextId = new AtomicInteger(1);

	/**
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
	 *
	 * @return The recorded finding.
	 */
	@Nonnull
	public Finding addFinding(@Nonnull Finding.Severity severity, @Nonnull String title, @Nonnull String detail,
	                          @Nullable String className, @Nullable String member) {
		Finding finding = new Finding(nextId.getAndIncrement(), severity, title, detail, className, member,
				System.currentTimeMillis());
		findings.add(finding);
		notifyListeners();
		return finding;
	}

	/**
	 * @param id
	 * 		Identifier of the finding to remove.
	 *
	 * @return {@code true} when a finding was removed.
	 */
	public boolean removeFinding(int id) {
		boolean removed = findings.removeIf(finding -> finding.id() == id);
		if (removed)
			notifyListeners();
		return removed;
	}

	/**
	 * Removes all findings.
	 */
	public void clearFindings() {
		if (findings.isEmpty())
			return;
		findings.clear();
		notifyListeners();
	}

	/**
	 * @return Snapshot of the recorded findings, oldest first.
	 */
	@Nonnull
	public List<Finding> findings() {
		return new ArrayList<>(findings);
	}

	/**
	 * @param listener
	 * 		Listener to pass the current findings to whenever they change.
	 */
	public void addFindingsListener(@Nonnull Consumer<List<Finding>> listener) {
		listeners.add(listener);
	}

	/**
	 * @param listener
	 * 		Listener to remove.
	 */
	public void removeFindingsListener(@Nonnull Consumer<List<Finding>> listener) {
		listeners.remove(listener);
	}

	private void notifyListeners() {
		List<Finding> snapshot = findings();
		for (Consumer<List<Finding>> listener : listeners) {
			try {
				listener.accept(snapshot);
			} catch (RuntimeException ignored) {
				// A failing observer must not break the agent.
			}
		}
	}
}
