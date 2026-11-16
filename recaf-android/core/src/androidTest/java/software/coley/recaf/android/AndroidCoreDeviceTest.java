package software.coley.recaf.android;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;

/**
 * Runs the shared {@link CoreChecks} of the Android build of Recaf's core on a device or emulator.
 */
@RunWith(AndroidJUnit4.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class AndroidCoreDeviceTest {
	@BeforeClass
	public static void setup() throws Exception {
		CoreChecks.setup(InstrumentationRegistry.getInstrumentation().getTargetContext().getFilesDir().toPath());
	}

	@Test
	public void a_everyBeanCanBeCreated() {
		CoreChecks.everyBeanCanBeCreated();
	}

	@Test
	public void b_servicesResolveByInterfaceAndClass() throws Exception {
		CoreChecks.servicesResolveByInterfaceAndClass();
	}

	@Test
	public void c_importsJarThroughZipShim() throws Exception {
		CoreChecks.importsJarThroughZipShim();
	}

	@Test
	public void d_decompilesWithEveryJvmDecompiler() throws Exception {
		CoreChecks.decompilesWithEveryJvmDecompiler();
	}

	@Test
	public void e_runtimeClassesAreSynthesized() throws Exception {
		CoreChecks.runtimeClassesAreSynthesized();
	}

	@Test
	public void f_importsAndDecompilesApk() throws Exception {
		CoreChecks.importsAndDecompilesApk();
	}

	@Test
	public void g_codeAndLargeFilesAreFetchedOnDemand() throws Exception {
		CoreChecks.codeAndLargeFilesAreFetchedOnDemand(false);
	}
}
