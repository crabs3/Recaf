package software.coley.recaf.android;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/**
 * Runs the shared {@link CoreChecks} of the Android build of Recaf's core on the host JVM.
 */
class AndroidCoreSmokeTest {
	@TempDir
	static Path tempDir;

	@BeforeAll
	static void setup() throws Exception {
		CoreChecks.setup(tempDir);
	}

	@Test
	void everyBeanCanBeCreated() {
		CoreChecks.everyBeanCanBeCreated();
	}

	@Test
	void servicesResolveByInterfaceAndClass() throws Exception {
		CoreChecks.servicesResolveByInterfaceAndClass();
	}

	@Test
	void importsJarThroughZipShim() throws Exception {
		CoreChecks.importsJarThroughZipShim();
	}

	@Test
	void decompilesWithEveryJvmDecompiler() throws Exception {
		CoreChecks.decompilesWithEveryJvmDecompiler();
	}

	@Test
	void runtimeClassesAreSynthesized() throws Exception {
		CoreChecks.runtimeClassesAreSynthesized();
	}

	@Test
	void importsAndDecompilesApk() throws Exception {
		CoreChecks.importsAndDecompilesApk();
	}
}
