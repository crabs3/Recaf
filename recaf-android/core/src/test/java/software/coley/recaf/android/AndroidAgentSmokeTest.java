package software.coley.recaf.android;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/**
 * Runs the shared {@link AgentChecks} of the agent tooling on the host JVM, against the Android build of the core.
 */
class AndroidAgentSmokeTest {
	@TempDir
	static Path tempDir;

	@Test
	void toolsWork() throws Exception {
		AgentChecks.toolsWork(tempDir);
	}

	@Test
	void scriptsRun() throws Exception {
		AgentChecks.scriptsRun(tempDir);
	}

	@Test
	void mcpServerAnswers() throws Exception {
		AgentChecks.mcpServerAnswers(tempDir);
	}

	@Test
	void agentLoopRuns() throws Exception {
		AgentChecks.agentLoopRuns(tempDir);
	}

	@Test
	void openAiLoopRuns() throws Exception {
		AgentChecks.openAiLoopRuns(tempDir);
	}
}
