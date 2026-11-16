package software.coley.recaf.android;

import software.coley.android.xml.XmlDecoder;
import jakarta.enterprise.inject.Instance;
import software.coley.recaf.Bootstrap;
import software.coley.recaf.Recaf;
import software.coley.recaf.info.AndroidClassInfo;
import software.coley.recaf.info.BinaryXmlFileInfo;
import software.coley.recaf.info.FileInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.path.FilePathNode;
import software.coley.recaf.services.Service;
import software.coley.recaf.services.decompile.DecompileResult;
import software.coley.recaf.services.decompile.DecompilerManager;
import software.coley.recaf.services.decompile.JvmDecompiler;
import software.coley.recaf.services.inheritance.InheritanceGraph;
import software.coley.recaf.services.inheritance.InheritanceGraphService;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.services.workspace.io.ResourceImporter;
import software.coley.recaf.util.android.AndroidRes;
import software.coley.recaf.workspace.model.BasicWorkspace;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.resource.RuntimeWorkspaceResource;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Checks of the Android build of Recaf's core that are shared between the host JVM unit tests and the on-device
 * instrumented tests. They are written against plain {@link AssertionError}s so that both JUnit 4 <i>(device)</i> and
 * JUnit 5 <i>(host)</i> can drive them.
 * <p>
 * On the host JVM these exercise everything the Android build replaces: the reflective CDI container, the
 * foreign-memory shim under LL-Java-Zip, runtime class synthesis and the source patches. On a device they
 * additionally prove that Recaf's dependencies behave on Android's runtime.
 */
public final class CoreChecks {
	private static Recaf recaf;
	private static Path workDir;

	private CoreChecks() {}

	/**
	 * @param directory
	 * 		Writable directory for Recaf's files and the extracted samples.
	 */
	public static synchronized void setup(Path directory) throws IOException {
		if (recaf != null)
			return;
		workDir = directory;
		System.setProperty("RECAF_DIR", Files.createDirectories(directory.resolve("recaf")).toString());
		recaf = Bootstrap.get();
		Bootstrap.initialize();
	}

	public static Recaf recaf() {
		return recaf;
	}

	/**
	 * @param name
	 * 		Name of a sample in the {@code samples} resource directory.
	 *
	 * @return Path to an extracted copy of the sample.
	 */
	public static Path sample(String name) throws IOException {
		Path target = Files.createDirectories(workDir.resolve("samples")).resolve(name);
		try (InputStream in = CoreChecks.class.getResourceAsStream("/samples/" + name)) {
			check(in != null, "Missing sample resource: " + name);
			Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
		}
		return target;
	}

	/**
	 * @param name
	 * 		Name of a sample in the {@code samples} resource directory.
	 *
	 * @return Workspace of the imported sample, which is also made the current workspace.
	 */
	public static Workspace openSample(String name) throws IOException {
		WorkspaceResource resource = recaf.get(ResourceImporter.class).importResource(sample(name));
		Workspace workspace = new BasicWorkspace(resource);
		check(recaf.get(WorkspaceManager.class).setCurrent(workspace), "Workspace was not accepted as current");
		return workspace;
	}

	public static void everyBeanCanBeCreated() {
		List<String> failures = new ArrayList<>();
		int count = 0;
		for (Instance.Handle<Object> handle : recaf.getContainer().handles()) {
			count++;
			try {
				check(handle.get() != null, "Null bean instance");
			} catch (Throwable t) {
				Throwable root = t;
				while (root.getCause() != null) root = root.getCause();
				failures.add(handle.getBean().getBeanClass().getName() + " -> " + root);
			}
		}
		check(count > 100, "Expected the bean index to list the core's beans, found " + count);
		check(failures.isEmpty(), "Beans failed to initialize:\n" + String.join("\n", failures));
	}

	public static void servicesResolveByInterfaceAndClass() throws IOException {
		Workspace workspace = openSample("hello.jar");
		WorkspaceManager manager = recaf.get(WorkspaceManager.class);
		check(manager == recaf.get(WorkspaceManager.class), "Application scoped beans must be singletons");
		check(!recaf.instance(Service.class).isUnsatisfied(), "No services found");
		check(recaf.get(Workspace.class) == workspace, "The current workspace must be exposed through the producer method");
	}

	public static void importsJarThroughZipShim() throws IOException {
		Workspace workspace = openSample("hello.jar");
		check(workspace.findJvmClass("sample/Hello") != null, "Missing class sample/Hello");
		check(workspace.findJvmClass("sample/util/Checks") != null, "Missing class sample/util/Checks");
		check(workspace.findFile("config/app.properties") != null, "Missing file config/app.properties");
	}

	public static void decompilesWithEveryJvmDecompiler() throws Exception {
		Workspace workspace = openSample("hello.jar");
		DecompilerManager manager = recaf.get(DecompilerManager.class);
		ClassPathNode path = workspace.findJvmClass("sample/Hello");
		check(path != null, "Missing class sample/Hello");
		JvmClassInfo hello = path.getValue().asJvmClass();
		check(manager.getJvmDecompilers().size() >= 3, "Expected CFR, Procyon and Vineflower, found: " + manager.getJvmDecompilers());
		for (JvmDecompiler decompiler : manager.getJvmDecompilers()) {
			DecompileResult result = manager.decompile(decompiler, workspace, hello).get(300, TimeUnit.SECONDS);
			check(result.getType() == DecompileResult.ResultType.SUCCESS,
					decompiler.getName() + " failed: " + describe(result.getException()));
			check(result.getText() != null && result.getText().contains("class Hello"),
					decompiler.getName() + " produced unexpected output: " + result.getText());
		}
	}

	public static void runtimeClassesAreSynthesized() throws IOException {
		Workspace workspace = openSample("hello.jar");
		RuntimeWorkspaceResource runtime = RuntimeWorkspaceResource.getInstance();
		JvmClassInfo list = runtime.getJvmClassBundle().get("java/util/ArrayList");
		check(list != null, "Platform classes should be modeled from reflection");
		check("java/util/AbstractList".equals(list.getSuperName()), "Unexpected super type: " + list.getSuperName());
		check(list.getInterfaces().contains("java/util/List"), "Unexpected interfaces: " + list.getInterfaces());
		check(list.getDeclaredMethod("add", "(Ljava/lang/Object;)Z") != null, "Missing ArrayList.add");
		check(runtime.getJvmClassBundle().get("does/not/Exist") == null, "Unknown classes must not resolve");

		InheritanceGraph graph = recaf.get(InheritanceGraphService.class).getOrCreateInheritanceGraph(workspace);
		check(graph.isAssignableFrom("java/lang/Runnable", "sample/Hello"), "Hello should be assignable to Runnable");
		check(graph.isAssignableFrom("java/util/Collection", "java/util/ArrayList"), "ArrayList should be a Collection");
		check(!graph.isAssignableFrom("java/util/List", "java/util/HashSet"), "HashSet is not a List");
	}

	public static void importsAndDecompilesApk() throws Exception {
		Workspace workspace = openSample("sample.apk");
		ClassPathNode path = workspace.findAndroidClass("sample/app/MainActivity");
		check(path != null, "Missing Android class sample/app/MainActivity");
		AndroidClassInfo activity = path.getValue().asAndroidClass();
		check("android/app/Activity".equals(activity.getSuperName()), "Unexpected super type: " + activity.getSuperName());

		// Android classes are decompiled through their JVM translation.
		JvmClassInfo translated = activity.asJvmClass();
		DecompilerManager manager = recaf.get(DecompilerManager.class);
		DecompileResult result = manager.decompile(workspace, translated).get(300, TimeUnit.SECONDS);
		check(result.getType() == DecompileResult.ResultType.SUCCESS, "Decompile failed: " + describe(result.getException()));
		check(result.getText().contains("https://example.invalid/collect"), "Unexpected output: " + result.getText());

		// The binary manifest decodes to readable XML.
		FilePathNode manifestPath = workspace.findFile("AndroidManifest.xml");
		check(manifestPath != null, "Missing AndroidManifest.xml");
		FileInfo manifest = manifestPath.getValue();
		check(manifest instanceof BinaryXmlFileInfo, "Manifest was not recognized as binary XML: " + manifest.getClass());
		String xml = XmlDecoder.decode(((BinaryXmlFileInfo) manifest).getChunkModel(), AndroidRes.getAndroidBase(), null);
		check(xml.contains("android.permission.READ_CONTACTS"), "Unexpected manifest: " + xml);
		check(xml.contains("sample.app"), "Unexpected manifest: " + xml);
	}

	private static String describe(Throwable t) {
		if (t == null)
			return "<no exception>";
		StringBuilder sb = new StringBuilder();
		Throwable root = t;
		for (Throwable c = t; c != null; c = c.getCause()) {
			sb.append(c).append(" <- ");
			root = c;
		}
		// The frames of the root cause say where on this runtime things went wrong.
		StackTraceElement[] trace = root.getStackTrace();
		for (int i = 0; i < Math.min(12, trace.length); i++)
			sb.append("\n    at ").append(trace[i]);
		return sb.toString();
	}

	static void check(boolean condition, String message) {
		if (!condition)
			throw new AssertionError(message);
	}
}
