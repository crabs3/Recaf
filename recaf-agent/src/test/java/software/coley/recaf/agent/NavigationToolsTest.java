package software.coley.recaf.agent;

import jakarta.annotation.Nonnull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import software.coley.recaf.RecafConstants;
import software.coley.recaf.agent.tool.AgentTool;
import software.coley.recaf.agent.tool.impl.NavigationTools;
import software.coley.recaf.info.FileInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.workspace.io.ResourceImporter;
import software.coley.recaf.test.TestClassUtils;
import software.coley.recaf.workspace.model.BasicWorkspace;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.FileBundle;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link NavigationTools}.
 */
class NavigationToolsTest extends AgentTestBase {
	private static final List<String> TOOL_NAMES = List.of("list_packages", "list_files", "read_file", "get_android_manifest", "list_modified");

	@BeforeEach
	void registerTools() {
		// Registered here until the group is part of the standard toolbox.
		if (toolbox.tool(TOOL_NAMES.get(0)) == null)
			toolbox.register(new NavigationTools(toolbox.context()));
	}

	@Test
	void toolsAreDefinedAndReadOnly() {
		for (String name : TOOL_NAMES) {
			AgentTool tool = toolbox.tool(name);
			assertNotNull(tool, "Missing tool: " + name);
			assertTrue(tool.readOnly(), name + " only reads and must not be marked as mutating");
			assertTrue(tool.description().length() > 40, "Description of " + name + " is too short to guide a model");
			tool.inputSchema().getAsJsonArray("required").forEach(required ->
					assertTrue(tool.inputSchema().getAsJsonObject("properties").has(required.getAsString()),
							name + " requires an undeclared property: " + required));
			Object[] arguments = name.equals("read_file") ? new Object[]{"path", "config/app.properties"} : new Object[0];
			assertTrue(callExpectingError(name, arguments).contains("No workspace is open"), name + " should report the missing workspace");
		}
	}

	@Test
	void listPackagesCountsClasses() {
		openSample("hello.jar");
		String all = call("list_packages");
		assertTrue(all.contains("sample  (1 class)") && all.contains("sample/util  (1 class)"), all);

		openSample("sample.apk");
		assertTrue(call("list_packages").contains("sample/app  (2 classes)"), "Android classes are counted too");
	}

	@Test
	void listPackagesFoldsByDepthAndPages() {
		openClasses("Root", "com/a/One", "com/a/b/Two", "com/a/b/c/Three", "com/ab/Four", "org/x/Five");

		String all = call("list_packages");
		assertTrue(all.contains("Showing packages 1-6 of 6"), all);
		assertTrue(all.contains("(default package)  (1 class)"), all);
		assertTrue(all.contains("com/a/b/c  (1 class)"), all);

		// Folded to the top level: a few lines describe the whole program.
		String top = call("list_packages", "depth", 1);
		assertTrue(top.contains("Showing packages 1-3 of 3"), top);
		assertTrue(top.contains("com  (4 classes in 4 packages)"), top);
		assertTrue(top.contains("org  (1 class in 1 package)"), top);

		// Depth is counted below the requested package, and 'com/a' does not match 'com/ab'.
		String below = call("list_packages", "package", "com.a", "depth", 1);
		assertTrue(below.contains("Showing packages 1-2 of 2"), below);
		assertTrue(below.contains("com/a  (1 class)"), below);
		assertTrue(below.contains("com/a/b  (2 classes in 2 packages)"), below);
		assertFalse(below.contains("com/ab"), below);

		String paged = call("list_packages", "limit", 2);
		assertTrue(paged.contains("offset=2") && !paged.contains("com/a/b"), paged);
		assertTrue(call("list_packages", "offset", 5).contains("org/x  (1 class)"));
	}

	@Test
	void listPackagesRejectsUnknownPackageAndBadDepth() {
		openSample("hello.jar");
		String unknown = callExpectingError("list_packages", "package", "com/nothing");
		assertTrue(unknown.contains("no package 'com/nothing'"), unknown);
		assertTrue(callExpectingError("list_packages", "depth", 0).contains("'depth' must be 1 or more"));
	}

	@Test
	void listFilesShowsKindSizeAndNestedArchives(@TempDir Path directory) throws IOException {
		openArchive(directory);

		String all = call("list_files");
		assertTrue(all.contains("Showing files 1-6 of 6"), all);
		assertTrue(all.contains("notes.txt  [text, 11 B]"), all);
		assertTrue(all.contains("img/logo.png  [image, 64 B]"), all);
		assertTrue(all.contains("lib/arm64/libnative.so  [native library, 64 B]"), all);
		assertTrue(all.contains("data/blob.bin  [binary, 300 B]"), all);
		assertTrue(all.contains("assets/min.js  [text, 2.3 KB]"), all);
		// The nested archive is loaded rather than listed, and its files carry its path.
		assertTrue(all.contains("lib/inner.jar!/notes.txt  [text, 11 B]"), all);
		assertFalse(all.contains("lib/inner.jar  ["), all);

		String inLib = call("list_files", "directory", "/lib/");
		assertTrue(inLib.contains("Showing files 1-2 of 2") && inLib.contains("libnative.so") && inLib.contains("inner.jar!/notes.txt"), inLib);
		String byPattern = call("list_files", "pattern", "\\.(PNG|js)$");
		assertTrue(byPattern.contains("img/logo.png") && byPattern.contains("assets/min.js") && !byPattern.contains("notes.txt"), byPattern);
		String paged = call("list_files", "limit", 1);
		assertTrue(paged.contains("Showing files 1-1 of 6") && paged.contains("offset=1"), paged);
		assertEquals("No files found.", call("list_files", "directory", "nowhere"));
	}

	@Test
	void listFilesRejectsInvalidPattern() {
		openSample("hello.jar");
		assertTrue(callExpectingError("list_files", "pattern", "(").contains("Invalid regular expression"));
	}

	@Test
	void readFileShowsTextInPagesOfLines() {
		openSample("hello.jar");
		String properties = call("read_file", "path", "config/app.properties");
		assertTrue(properties.startsWith("config/app.properties: text, 48 B\n"), properties);
		assertTrue(properties.contains("endpoint=https://example.invalid/api\nmode=debug"), properties);

		String firstLine = call("read_file", "path", "/config/app.properties", "max_lines", 1);
		assertTrue(firstLine.contains("endpoint=") && !firstLine.contains("mode=debug"), firstLine);
		assertTrue(firstLine.contains("start_line=2"), firstLine);
		String secondLine = call("read_file", "path", "config/app.properties", "start_line", 2);
		assertTrue(secondLine.contains("mode=debug") && !secondLine.contains("endpoint="), secondLine);
	}

	@Test
	void readFileShowsBinaryAsHexDump(@TempDir Path directory) throws IOException {
		openArchive(directory);

		// Detected as binary: a hex dump with offsets and an ASCII column, bounded by default.
		String blob = call("read_file", "path", "data/blob.bin");
		assertTrue(blob.startsWith("data/blob.bin: binary, 300 B (300 bytes)\n"), blob);
		assertTrue(blob.contains("00000000  de ad be ef 00 01 02 03  04 05 06 07 08 09 0a 0b  |................|"), blob);
		assertFalse(blob.contains("offset="), "300 bytes fit into the default range: " + blob);

		String range = call("read_file", "path", "data/blob.bin", "offset", 68, "length", 20);
		assertTrue(range.contains("00000044  40 41 42 43 44 45 46 47  48 49 4a 4b 4c 4d 4e 4f  |@ABCDEFGHIJKLMNO|"), range);
		assertTrue(range.contains("00000054  50 51 52 53" + " ".repeat(39) + "|PQRS|"), range);
		assertTrue(range.contains("[Bytes 68-87 of 300. Call again with offset=88 to continue.]"), range);
		assertTrue(call("read_file", "path", "data/blob.bin", "offset", 300).contains("No byte at offset 300"));

		// Other detected kinds are named, and text can be dumped as bytes on request.
		assertTrue(call("read_file", "path", "img/logo.png").startsWith("img/logo.png: image, 64 B (64 bytes)\n00000000  89 50 4e 47"));
		String textAsHex = call("read_file", "path", "notes.txt", "encoding", "hex");
		assertTrue(textAsHex.contains("6f 75 74 65 72 20 6e 6f  74 65 73") && textAsHex.contains("|outer notes|"), textAsHex);

		// Binary read as text on request.
		String forced = call("read_file", "path", "data/blob.bin", "encoding", "text");
		assertTrue(forced.contains("bytes read as UTF-8 text on request") && forced.contains("@ABCDEFG"), forced);
	}

	@Test
	void readFileResolvesNestedArchivesAndWrapsLongLines(@TempDir Path directory) throws IOException {
		openArchive(directory);
		assertTrue(call("read_file", "path", "notes.txt").endsWith("outer notes"));
		assertTrue(call("read_file", "path", "lib/inner.jar!/notes.txt").endsWith("inner notes"));
		assertTrue(callExpectingError("read_file", "path", "lib/inner.jar!/missing.txt").contains("No file named 'lib/inner.jar!/missing.txt'"));

		// A minified file is one long line. It is wrapped so that paging by lines reaches all of it.
		String minified = call("read_file", "path", "assets/min.js", "max_lines", 2);
		assertTrue(minified.contains("lines longer than 1000 characters are wrapped"), minified);
		assertTrue(minified.contains("[Lines 1-2 of 3. Call again with start_line=3 to continue.]"), minified);
		String rest = call("read_file", "path", "assets/min.js", "start_line", 3);
		assertTrue(rest.contains("x".repeat(400) + ";END") && !rest.contains("x".repeat(401)), rest);
	}

	@Test
	void readFileDecodesAndroidBinaryXml() {
		openSample("sample.apk");
		String manifest = call("read_file", "path", "AndroidManifest.xml");
		assertTrue(manifest.startsWith("AndroidManifest.xml: binary XML, 2.1 KB, decoded to XML\n<?xml"), manifest);
		assertTrue(manifest.contains("package=\"sample.app\""), manifest);
		assertTrue(manifest.contains("android:name=\"android.permission.READ_CONTACTS\""), manifest);
		assertTrue(manifest.contains("android:exported=\"true\""), manifest);

		// The decoded text is paged by lines like any other text, and the raw bytes remain available.
		String paged = call("read_file", "path", "AndroidManifest.xml", "start_line", 2, "max_lines", 1);
		assertTrue(paged.contains("<manifest") && !paged.contains("<?xml") && paged.contains("start_line=3"), paged);
		String hex = call("read_file", "path", "AndroidManifest.xml", "encoding", "hex", "length", 16);
		assertTrue(hex.contains("00000000  03 00 08 00") && hex.contains("offset=16"), hex);

		// The resource table of the sample has no packages, which Recaf cannot model. It is shown as bytes and says so.
		String table = call("read_file", "path", "resources.arsc");
		assertTrue(table.contains("ARSC resource table") && table.contains("could not be decoded as a resource table") &&
				table.contains("00000000  02 00"), table);
	}

	@Test
	void readFileReportsProblems() {
		openSample("hello.jar");
		String missing = callExpectingError("read_file", "path", "app.properties");
		assertTrue(missing.contains("Did you mean: config/app.properties"), missing);
		assertTrue(callExpectingError("read_file").contains("path"), "Missing argument should be named");
		assertTrue(callExpectingError("read_file", "path", "config/app.properties", "encoding", "base64").contains("encoding"));

		// Paging arguments of the other representation are pointed out instead of being ignored.
		String wrongForText = callExpectingError("read_file", "path", "config/app.properties", "offset", 10);
		assertTrue(wrongForText.contains("start_line") && wrongForText.contains("encoding='hex'"), wrongForText);
		String wrongForHex = callExpectingError("read_file", "path", "config/app.properties", "encoding", "hex", "start_line", 2);
		assertTrue(wrongForHex.contains("'offset' and 'length'"), wrongForHex);
	}

	@Test
	void androidManifestIsSummarized() {
		openSample("sample.apk");
		String summary = call("get_android_manifest");
		assertTrue(summary.startsWith("Package: sample.app\nVersion: 1.2.3 (code 7)\nSDK: min 26, target 35\n"), summary);
		assertTrue(summary.contains("Application: label=Sample App"), summary);
		assertTrue(summary.contains("Permissions requested (2): android.permission.INTERNET, android.permission.READ_CONTACTS"), summary);
		// The component is named by its class, which exists in the DEX file.
		assertTrue(summary.contains("activity sample/app/MainActivity  [exported=true]\n" +
				"    intent-filter: actions android.intent.action.MAIN; categories android.intent.category.LAUNCHER"), summary);
		assertFalse(summary.contains("<manifest"), "The XML is only included on request: " + summary);

		String withXml = call("get_android_manifest", "include_xml", true);
		assertTrue(withXml.startsWith(summary), withXml);
		assertTrue(withXml.contains("AndroidManifest.xml, decoded to XML:\n<?xml") && withXml.endsWith("</manifest>"), withXml);
	}

	@Test
	void androidManifestRequiresAnAndroidWorkspace() {
		openSample("hello.jar");
		String error = callExpectingError("get_android_manifest");
		assertTrue(error.contains("no AndroidManifest.xml"), error);
	}

	@Test
	void androidManifestDetailsAndPaging() {
		String xml = """
				<?xml version="1.0" encoding="utf-8"?>
				<!-- <activity android:name=".Commented" /> -->
				<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.acme">
				    <uses-permission android:name="android.permission.SEND_SMS"/>
				    <permission android:name="com.acme.PRIVATE" android:protectionLevel="signature"/>
				    <application android:name=".App" android:debuggable="true" android:allowBackup="false">
				        <activity name="decoy" android:name=".ui.Login">
				            <intent-filter>
				                <action android:name="android.intent.action.VIEW"/>
				                <data android:scheme="acme" android:host="login"/>
				            </intent-filter>
				        </activity>
				        <activity-alias android:name=".Alias" android:targetActivity=".ui.Login" android:exported="true"/>
				        <service android:name="com.acme.sync.SyncService" android:permission="com.acme.PRIVATE"/>
				        <receiver android:name="BootReceiver" android:exported="false">
				            <intent-filter><action android:name="android.intent.action.BOOT_COMPLETED"/></intent-filter>
				        </receiver>
				        <provider android:name=".data.Provider" android:authorities="com.acme.a &amp; b > c"/>
				    </application>
				</manifest>
				""";
		openFiles("AndroidManifest.xml", xml, "com/acme/ui/Login");

		String summary = call("get_android_manifest");
		assertTrue(summary.contains("Package: com.acme\nVersion: not declared\nSDK: min not declared, target not declared"), summary);
		assertTrue(summary.contains("Application: class com/acme/App, debuggable=true, allowBackup=false"), summary);
		assertTrue(summary.contains("Permissions requested (1): android.permission.SEND_SMS"), summary);
		assertTrue(summary.contains("Permissions declared (1): com.acme.PRIVATE (protectionLevel signature)"), summary);
		assertTrue(summary.contains("Showing components 1-5 of 5"), summary);
		// Names are resolved against the package, the namespaced attribute wins, and defaults of 'exported' are explained.
		assertTrue(summary.contains("activity com/acme/ui/Login  [exported not declared (default true: has intent-filter)]\n" +
				"    intent-filter: actions android.intent.action.VIEW; data scheme=acme host=login"), summary);
		assertTrue(summary.contains("activity-alias com/acme/Alias -> com/acme/ui/Login  [exported=true]"), summary);
		// Components whose class is not in the workspace are flagged.
		assertTrue(summary.contains("service com/acme/sync/SyncService  [exported not declared (default false: no intent-filter), " +
				"permission=com.acme.PRIVATE, class not in workspace]"), summary);
		assertTrue(summary.contains("receiver com/acme/BootReceiver  [exported=false, class not in workspace]\n" +
				"    intent-filter: actions android.intent.action.BOOT_COMPLETED"), summary);
		assertTrue(summary.contains("provider com/acme/data/Provider  [exported not declared (default false since target SDK 17), " +
				"authorities=com.acme.a & b > c, class not in workspace]"), summary);
		assertFalse(summary.contains("decoy") || summary.contains("Commented"), summary);

		String firstPage = call("get_android_manifest", "limit", 2);
		assertTrue(firstPage.contains("Showing components 1-2 of 5") && firstPage.contains("offset=2"), firstPage);
		String secondPage = call("get_android_manifest", "offset", 2, "limit", 2);
		assertTrue(secondPage.startsWith("Showing components 3-4 of 5"), "Only the first page repeats the general part: " + secondPage);
	}

	@Test
	void androidManifestSummaryFollowsResourceIdentifiers() {
		Workspace workspace = openSample("sample.apk");
		FileBundle files = workspace.getPrimaryResource().getFileBundle();
		FileInfo manifest = files.get("AndroidManifest.xml");

		// Android identifies its attributes by resource identifier. The name in the string pool is only for show,
		// so an application can change it to mislead tools that go by the name.
		byte[] patched = replace(manifest.getRawContent(), "exported".getBytes(StandardCharsets.UTF_16LE), "exqorted".getBytes(StandardCharsets.UTF_16LE));
		// An attribute stores a raw string next to its typed value, and Android reads the typed value of its attributes.
		// Here the raw string of the activity's name (string 9, '.MainActivity') is pointed at the label (string 12).
		patched = replace(patched, new byte[]{9, 0, 0, 0, 8, 0, 0, 3, 9, 0, 0, 0}, new byte[]{12, 0, 0, 0, 8, 0, 0, 3, 9, 0, 0, 0});
		files.put(manifest.toFileBuilder().withRawContent(patched).build());

		String summary = call("get_android_manifest", "include_xml", true);
		assertTrue(summary.contains("android:exqorted=\"true\"") && summary.contains("android:name=\"Sample App\""),
				"The decoded text shows what is stored for show: " + summary);
		assertTrue(summary.contains("activity sample/app/MainActivity  [exported=true]"), "The summary reads it as Android does: " + summary);
	}

	@Test
	void androidManifestAsTextSurvivesDamage() {
		// Cut off in the middle of an attribute.
		openFiles("AndroidManifest.xml", "<manifest package=\"a.b\"><application><service android:name=\".Sync\"/>" +
				"<activity android:name=\".Main\"><intent-filter><action android:name=\"x.RUN\"/></intent-filter></activity><receiver android:na");
		String summary = call("get_android_manifest");
		assertTrue(summary.contains("Package: a.b"), summary);
		assertTrue(summary.contains("service a/b/Sync") && summary.contains("activity a/b/Main") && summary.contains("actions x.RUN"), summary);

		openFiles("AndroidManifest.xml", "this is not a manifest");
		assertTrue(callExpectingError("get_android_manifest").contains("no <manifest> element"));
	}

	@Test
	void listModifiedReportsChangesAndRemovals() {
		Workspace workspace = openSample("hello.jar");
		assertEquals("Nothing was changed or removed in this session.", call("list_modified"));

		replaceString(workspace, "sample/Hello", "admin mode", "root mode");
		replaceString(workspace, "sample/Hello", "root mode", "god mode");
		FileBundle files = workspace.getPrimaryResource().getFileBundle();
		FileInfo properties = files.get("config/app.properties");
		files.put(properties.toFileBuilder().withRawContent("mode=release\n".getBytes(StandardCharsets.UTF_8)).build());
		workspace.getPrimaryResource().getJvmClassBundle().remove("sample/util/Checks");

		String modified = call("list_modified");
		assertTrue(modified.contains("Showing changed or removed items 1-3 of 3"), modified);
		assertTrue(modified.contains("modified class  sample/Hello  (2 edits)"), modified);
		assertTrue(modified.contains("modified file   config/app.properties  (1 edit)"), modified);
		assertTrue(modified.contains("removed class   sample/util/Checks"), modified);
		assertFalse(modified.contains("MANIFEST.MF"), "Untouched files are not listed: " + modified);
		assertTrue(call("list_modified", "limit", 1).contains("offset=1"));

		// The file listing marks the edited file too.
		assertTrue(call("list_files").contains("config/app.properties  [text, 13 B, modified]"));
		assertTrue(call("read_file", "path", "config/app.properties").endsWith("mode=release"));
	}

	@Test
	void listModifiedRequiresWorkspace() {
		assertTrue(callExpectingError("list_modified").contains("No workspace is open"));
	}

	/**
	 * Opens a workspace of empty classes.
	 *
	 * @param names
	 * 		Names of the classes.
	 */
	private static void openClasses(@Nonnull String... names) {
		JvmClassInfo[] classes = new JvmClassInfo[names.length];
		for (int i = 0; i < names.length; i++)
			classes[i] = TestClassUtils.createEmptyClass(names[i]);
		workspaceManager.setCurrentIgnoringConditions(TestClassUtils.fromBundle(TestClassUtils.fromClasses(classes)));
	}

	/**
	 * Opens a workspace of a text file and empty classes.
	 *
	 * @param fileName
	 * 		Name of the file.
	 * @param fileText
	 * 		Content of the file.
	 * @param classNames
	 * 		Names of the classes.
	 */
	private static void openFiles(@Nonnull String fileName, @Nonnull String fileText, @Nonnull String... classNames) {
		JvmClassInfo[] classes = new JvmClassInfo[classNames.length];
		for (int i = 0; i < classNames.length; i++)
			classes[i] = TestClassUtils.createEmptyClass(classNames[i]);
		FileBundle files = TestClassUtils.fromFiles(TestClassUtils.createFile(fileName, fileText.getBytes(StandardCharsets.UTF_8)));
		workspaceManager.setCurrentIgnoringConditions(TestClassUtils.fromBundles(TestClassUtils.fromClasses(classes), files));
	}

	/**
	 * @param data
	 * 		Bytes to search.
	 * @param from
	 * 		Bytes to replace, which must occur.
	 * @param to
	 * 		Replacement of the same length.
	 *
	 * @return Copy of the bytes with the first occurrence replaced.
	 */
	@Nonnull
	private static byte[] replace(@Nonnull byte[] data, @Nonnull byte[] from, @Nonnull byte[] to) {
		assertEquals(from.length, to.length);
		for (int i = 0; i <= data.length - from.length; i++) {
			if (Arrays.equals(data, i, i + from.length, from, 0, from.length)) {
				byte[] copy = data.clone();
				System.arraycopy(to, 0, copy, i, to.length);
				return copy;
			}
		}
		return fail("Bytes to replace were not found");
	}

	/**
	 * Opens an archive holding files of several kinds and a nested archive whose file shares its path with a file
	 * of the outer archive.
	 *
	 * @param directory
	 * 		Directory to create the archive in.
	 */
	private static void openArchive(@Nonnull Path directory) throws IOException {
		byte[] blob = new byte[300];
		blob[0] = (byte) 0xDE;
		blob[1] = (byte) 0xAD;
		blob[2] = (byte) 0xBE;
		blob[3] = (byte) 0xEF;
		for (int i = 4; i < blob.length; i++)
			blob[i] = (byte) (i - 4);

		Map<String, byte[]> inner = new LinkedHashMap<>();
		inner.put("notes.txt", "inner notes".getBytes(StandardCharsets.UTF_8));
		Map<String, byte[]> outer = new LinkedHashMap<>();
		outer.put("notes.txt", "outer notes".getBytes(StandardCharsets.UTF_8));
		outer.put("img/logo.png", withHeader(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A));
		outer.put("lib/arm64/libnative.so", withHeader(0x7F, 'E', 'L', 'F', 2, 1, 1, 0));
		outer.put("data/blob.bin", blob);
		outer.put("assets/min.js", ("x".repeat(2400) + ";END").getBytes(StandardCharsets.UTF_8));
		outer.put("lib/inner.jar", zip(inner));

		Path archive = directory.resolve("files.zip");
		Files.write(archive, zip(outer));
		Workspace workspace = new BasicWorkspace(recaf.get(ResourceImporter.class).importResource(archive));
		workspaceManager.setCurrentIgnoringConditions(workspace);
	}

	@Nonnull
	private static byte[] withHeader(int... header) {
		byte[] content = new byte[64];
		for (int i = 0; i < header.length; i++)
			content[i] = (byte) header[i];
		return content;
	}

	@Nonnull
	private static byte[] zip(@Nonnull Map<String, byte[]> entries) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
			for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
				zip.putNextEntry(new ZipEntry(entry.getKey()));
				zip.write(entry.getValue());
				zip.closeEntry();
			}
		}
		return bytes.toByteArray();
	}

	/**
	 * Modifies a class through Recaf's API, the way an editing tool would.
	 *
	 * @param workspace
	 * 		Workspace holding the class.
	 * @param className
	 * 		Name of the class to modify.
	 * @param from
	 * 		String constant to replace.
	 * @param to
	 * 		Replacement.
	 */
	static void replaceString(@Nonnull Workspace workspace, @Nonnull String className, @Nonnull String from, @Nonnull String to) {
		JvmClassBundle bundle = workspace.getPrimaryResource().getJvmClassBundle();
		JvmClassInfo info = bundle.get(className);
		assertNotNull(info, "Missing class: " + className);
		int api = RecafConstants.getAsmVersion();
		ClassWriter writer = new ClassWriter(0);
		info.getClassReader().accept(new ClassVisitor(api, writer) {
			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
				return new MethodVisitor(api, super.visitMethod(access, name, descriptor, signature, exceptions)) {
					@Override
					public void visitLdcInsn(Object value) {
						super.visitLdcInsn(from.equals(value) ? to : value);
					}
				};
			}
		}, 0);
		bundle.put(info.toJvmClassBuilder().adaptFrom(writer.toByteArray()).build());
	}
}
