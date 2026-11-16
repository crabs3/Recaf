package software.coley.recaf.android.compat;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import me.darknet.dex.file.DexHeader;
import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.items.ClassDefItem;
import me.darknet.dex.io.Input;
import me.darknet.dex.tree.definitions.ClassDefinition;
import me.darknet.dex.tree.definitions.annotation.Annotation;
import me.darknet.dex.tree.definitions.annotation.AnnotationProcessing;
import software.coley.recaf.info.AndroidClassInfo;
import software.coley.recaf.info.builder.AndroidClassInfoBuilder;

import java.io.IOException;
import java.lang.ref.SoftReference;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One DEX file, kept as its bytes, that hands out the definition of a class when it is asked for.
 * <p>
 * Recaf's regular DEX reader turns a whole file into trees of instruction objects and lets every class keep its
 * tree for as long as the workspace is open. That costs roughly forty times the size of the file in memory, which
 * a desktop has and a phone does not: an Android app gets a few hundred megabytes, while applications ship tens of
 * megabytes of DEX code. Here a class is turned into its tree only while something looks at its code. What stays in
 * memory per class is its outline: name, hierarchy, fields and methods.
 * <p>
 * Reading a class needs the file's tables of strings, types and members. They take about ten times the size of the
 * file, so they are kept behind a soft reference: they stay around while there is room, are dropped when memory
 * runs short, and are read again from the bytes when next needed.
 *
 * @author Recaf Android port
 */
public final class DexClassSource {
	/** System annotations, which hold what class files keep in attributes. */
	private static final byte VISIBILITY_SYSTEM = 2;
	private static final ThreadLocal<Pending> PENDING = new ThreadLocal<>();
	private final byte[] dex;
	private final int version;
	private final byte[] link;
	private final int classCount;
	private SoftReference<Tables> tables;

	private DexClassSource(@Nonnull byte[] dex, @Nonnull DexHeader header) {
		this.dex = dex;
		this.version = header.version();
		this.link = header.link();
		this.classCount = header.map().classes().size();
		this.tables = new SoftReference<>(new Tables(header.map()));
	}

	/**
	 * @param dex
	 * 		Content of a DEX file. The array is kept and must not be modified afterwards.
	 *
	 * @return Source of the classes in the file.
	 *
	 * @throws IOException
	 * 		When the content is not a DEX file that can be read.
	 */
	@Nonnull
	public static DexClassSource open(@Nonnull byte[] dex) throws IOException {
		return new DexClassSource(dex, DexHeader.CODEC.read(Input.wrap(dex)));
	}

	/**
	 * @return DEX format version of the file.
	 */
	public int version() {
		return version;
	}

	/**
	 * @return Link section of the file.
	 */
	public byte[] link() {
		return link;
	}

	/**
	 * @return Number of classes in the file.
	 */
	public int classCount() {
		return classCount;
	}

	/**
	 * @return The file's tables, read again if they were dropped to free memory.
	 */
	@Nonnull
	public synchronized DexMap map() {
		return tables().map;
	}

	/**
	 * @param index
	 * 		Position of the class in the file, from zero.
	 *
	 * @return The class with its code, built anew. It is not kept here, the caller decides how long it lives.
	 */
	@Nonnull
	public ClassDefinition definition(int index) {
		Tables tables;
		synchronized (this) {
			tables = tables();
		}
		DexMap map = tables.map;
		ClassDefinition definition = ClassDefinition.CODEC.map(map.classes().get(index), map);
		processAttributes(tables, definition);
		return definition;
	}

	/**
	 * @param index
	 * 		Position of the class in the file.
	 * @param definition
	 * 		The class as returned by {@link #definition(int)}.
	 *
	 * @return Class info that keeps the outline of the class and fetches its code from this source on demand.
	 */
	@Nonnull
	public AndroidClassInfo buildInfo(int index, @Nonnull ClassDefinition definition) {
		PENDING.set(new Pending(this, index, definition));
		try {
			return new AndroidClassInfoBuilder().adaptFrom(definition).build();
		} finally {
			PENDING.remove();
		}
	}

	/**
	 * Lets a class info that is being built find out whether its definition can be fetched again later, in which
	 * case it does not have to hold on to it.
	 *
	 * @param definition
	 * 		Definition the class info is built from.
	 *
	 * @return Where the definition came from, or {@code null} when it was not read from a DEX file by this class.
	 */
	@Nullable
	public static Pending pendingFor(@Nullable ClassDefinition definition) {
		Pending pending = PENDING.get();
		return pending != null && pending.definition == definition ? pending : null;
	}

	@Nonnull
	private Tables tables() {
		Tables current = tables.get();
		if (current == null) {
			try {
				current = new Tables(DexHeader.CODEC.read(Input.wrap(dex)).map());
			} catch (IOException ex) {
				// The same bytes were read successfully when the file was opened.
				throw new IllegalStateException("DEX content could not be read again", ex);
			}
			tables = new SoftReference<>(current);
		}
		return current;
	}

	/**
	 * Moves what the DEX format stores in system annotations (generic signatures, inner classes, enclosing
	 * members and so on) to where the class model keeps it, the way the regular reader does for a whole file.
	 */
	private static void processAttributes(@Nonnull Tables tables, @Nonnull ClassDefinition definition) {
		List<Annotation> annotations = definition.getAnnotations();
		if (annotations == null || annotations.isEmpty())
			return;
		Map<String, ClassDefinition> others = new OtherClasses(tables, definition);
		for (Annotation annotation : new ArrayList<>(annotations)) {
			if (annotation.visibility() != VISIBILITY_SYSTEM)
				continue;
			switch (AnnotationProcessing.processAttribute(others, definition, annotation.annotation())) {
				case CONSUMED -> annotations.remove(annotation);
				case PRESERVE -> {}
				case ERROR -> throw new IllegalStateException("Failed to process system annotation: " +
						annotation.annotation().type().internalName());
			}
		}
	}

	/**
	 * Where a definition that a class info is being built from can be fetched again.
	 *
	 * @param source
	 * 		File the class is in.
	 * @param index
	 * 		Position of the class in the file.
	 * @param definition
	 * 		The definition, to recognize the class info it belongs to.
	 */
	public record Pending(@Nonnull DexClassSource source, int index, @Nonnull ClassDefinition definition) {}

	/**
	 * The file's tables, with the position of each class by name.
	 */
	private static final class Tables {
		private final DexMap map;
		private Map<String, Integer> indexByName;

		private Tables(@Nonnull DexMap map) {
			this.map = map;
		}

		@Nullable
		private synchronized Integer indexOf(@Nonnull String internalName) {
			if (indexByName == null) {
				Map<String, Integer> index = new HashMap<>(map.classes().size() * 2);
				int position = 0;
				for (ClassDefItem item : map.classes()) {
					String descriptor = item.type().descriptor().string();
					index.put(descriptor.substring(1, descriptor.length() - 1), position++);
				}
				indexByName = index;
			}
			return indexByName.get(internalName);
		}
	}

	/**
	 * The other classes of the file by name, each read when it is looked up. Attribute processing consults the
	 * classes that a class names as its inner or outer classes.
	 */
	private static final class OtherClasses extends AbstractMap<String, ClassDefinition> {
		private final Tables tables;
		private final ClassDefinition current;

		private OtherClasses(@Nonnull Tables tables, @Nonnull ClassDefinition current) {
			this.tables = tables;
			this.current = current;
		}

		@Override
		public ClassDefinition get(Object key) {
			if (!(key instanceof String name))
				return null;
			if (name.equals(current.getType().internalName()))
				return current;
			Integer index = tables.indexOf(name.replace('.', '/'));
			if (index == null)
				return null;
			return ClassDefinition.CODEC.map(tables.map.classes().get(index), tables.map);
		}

		@Override
		public boolean containsKey(Object key) {
			return key instanceof String name && tables.indexOf(name.replace('.', '/')) != null;
		}

		@Nonnull
		@Override
		public Set<Entry<String, ClassDefinition>> entrySet() {
			// Only lookups by name are served. Walking all classes would read the whole file per class.
			return Set.of();
		}
	}
}
