package software.coley.recaf.android;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.file.Path;

/**
 * Runs the shared {@link AgentChecks} of the agent tooling on a device or emulator.
 */
@RunWith(AndroidJUnit4.class)
public class AndroidAgentDeviceTest {
	private static Path workDir() {
		return InstrumentationRegistry.getInstrumentation().getTargetContext().getFilesDir().toPath();
	}

	@Test
	public void toolsWork() throws Exception {
		AgentChecks.toolsWork(workDir());
	}

	@Test
	public void scriptsRun() throws Exception {
		AgentChecks.scriptsRun(workDir());
	}

	@Test
	public void mcpServerAnswers() throws Exception {
		AgentChecks.mcpServerAnswers(workDir());
	}

	@Test
	public void agentLoopRuns() throws Exception {
		AgentChecks.agentLoopRuns(workDir());
	}

	@Test
	public void openAiLoopRuns() throws Exception {
		AgentChecks.openAiLoopRuns(workDir());
	}
}
