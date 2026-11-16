package software.coley.recaf.android.compat;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;

/**
 * Class loader whose only purpose is to answer resource lookups for the class files of platform classes, such as
 * {@code java/lang/Object.class}.
 * <p>
 * Some libraries read the class files of core types from the system class loader. On a desktop JVM those come
 * from the JDK. Android has none, so the lookups of such libraries are redirected here at build time
 * <i>(see {@code retargetProcyon} in the build)</i> and answered with outlines from {@link RuntimeClassSynthesizer}.
 *
 * @author Recaf Android port
 */
public final class RuntimeResourceLoader extends ClassLoader {
	private static final String SUFFIX = ".class";
	private static final RuntimeResourceLoader INSTANCE = new RuntimeResourceLoader();

	private RuntimeResourceLoader() {
		super(RuntimeResourceLoader.class.getClassLoader());
	}

	/**
	 * @return Loader answering resource lookups for platform class files.
	 */
	@Nonnull
	public static ClassLoader get() {
		return INSTANCE;
	}

	/**
	 * Replacement for {@code owner.getResourceAsStream(name)} where a library reads a class file as a resource
	 * relative to one of its own classes, only to have <i>some</i> well-formed class file to work with.
	 *
	 * @param owner
	 * 		Class the resource is looked up against.
	 * @param name
	 * 		Resource name.
	 *
	 * @return The resource if the runtime has it, otherwise the outline of {@code java/lang/Object} for class file
	 * lookups, otherwise {@code null}.
	 */
	@Nullable
	public static InputStream classResource(@Nonnull Class<?> owner, @Nonnull String name) {
		InputStream stream = owner.getResourceAsStream(name);
		if (stream != null || !name.endsWith(SUFFIX))
			return stream;
		byte[] bytes = RuntimeClassSynthesizer.getBytes("java/lang/Object");
		return bytes == null ? null : new ByteArrayInputStream(bytes);
	}

	@Nullable
	@Override
	public URL getResource(String name) {
		byte[] bytes = classBytes(name);
		if (bytes == null)
			return super.getResource(name);
		try {
			return new URL(null, "recaf-runtime:/" + name, new URLStreamHandler() {
				@Override
				protected URLConnection openConnection(URL url) {
					return new URLConnection(url) {
						@Override
						public void connect() {
							// Content is in memory.
						}

						@Override
						public InputStream getInputStream() {
							return new ByteArrayInputStream(bytes);
						}

						@Override
						public int getContentLength() {
							return bytes.length;
						}
					};
				}
			});
		} catch (MalformedURLException ex) {
			return null;
		}
	}

	@Nullable
	@Override
	public InputStream getResourceAsStream(String name) {
		byte[] bytes = classBytes(name);
		return bytes == null ? super.getResourceAsStream(name) : new ByteArrayInputStream(bytes);
	}

	@Nullable
	private static byte[] classBytes(@Nullable String name) {
		if (name == null || !name.endsWith(SUFFIX))
			return null;
		String internalName = name.startsWith("/") ? name.substring(1) : name;
		internalName = internalName.substring(0, internalName.length() - SUFFIX.length());
		return RuntimeClassSynthesizer.getBytes(internalName);
	}
}
