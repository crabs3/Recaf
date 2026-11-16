package software.coley.recaf.info;

import jakarta.annotation.Nonnull;
import me.darknet.dex.convert.DexConversionIr;
import me.darknet.dex.convert.ir.lowering.JvmLoweringPolicy;
import me.darknet.dex.tree.definitions.ClassDefinition;
import org.objectweb.asm.ClassReader;
import software.coley.recaf.android.compat.DexClassSource;
import software.coley.recaf.info.builder.AndroidClassInfoBuilder;
import software.coley.recaf.info.builder.JvmClassInfoBuilder;

import java.lang.ref.SoftReference;

/**
 * Android class info implementation of the Android build.
 * <p>
 * Differs from the desktop implementation in what it keeps in memory. A class that was read from a DEX file does
 * not hold on to its definition, the tree of its code: it fetches it from the file when asked, see
 * {@link DexClassSource}. The translation into a JVM class is kept only while memory allows as well.
 * A class that was created some other way, by assembling it for instance, has nowhere to fetch its definition from
 * and keeps it.
 *
 * @author Matt Coley
 * @author Recaf Android port
 */
public class BasicAndroidClassInfo extends BasicClassInfo implements AndroidClassInfo {
	private final ClassDefinition def;
	private final DexClassSource source;
	private final int sourceIndex;
	private SoftReference<ClassDefinition> loaded;
	private SoftReference<JvmClassInfo> converted;

	/**
	 * @param builder
	 * 		Builder to pull info from.
	 */
	public BasicAndroidClassInfo(@Nonnull AndroidClassInfoBuilder builder) {
		super(builder);
		ClassDefinition definition = builder.getDef();
		DexClassSource.Pending pending = DexClassSource.pendingFor(definition);
		if (pending != null) {
			def = null;
			source = pending.source();
			sourceIndex = pending.index();
		} else {
			def = definition;
			source = null;
			sourceIndex = -1;
		}
	}

	@Nonnull
	@Override
	public synchronized ClassDefinition getBackingDefinition() {
		if (def != null)
			return def;
		ClassDefinition definition = loaded == null ? null : loaded.get();
		if (definition == null) {
			definition = source.definition(sourceIndex);
			loaded = new SoftReference<>(definition);
		}
		return definition;
	}

	@Override
	public boolean canMapToJvmClass() {
		// See below
		return true;
	}

	/**
	 * @return Translation into JVM class.
	 */
	@Nonnull
	@Override
	public synchronized JvmClassInfo asJvmClass() {
		// This is an expensive operation, so other threads wait until it is done and then use the one result.
		JvmClassInfo jvmClass = converted == null ? null : converted.get();
		if (jvmClass == null) {
			try {
				DexConversionIr conversion = new DexConversionIr();
				conversion.setJvmLoweringPolicy(JvmLoweringPolicy.AGGRESSIVE_OPTIMIZED);
				byte[] convertedBytecode = conversion.toJavaClass(getBackingDefinition());
				ClassReader reader = new ClassReader(convertedBytecode);
				jvmClass = new JvmClassInfoBuilder(reader).build();
			} catch (IllegalStateException ex) {
				throw ex;
			} catch (Throwable ex) {
				throw new IllegalStateException(ex);
			}
			converted = new SoftReference<>(jvmClass);
		}
		return jvmClass;
	}

	@Override
	public String toString() {
		return "Android class: " + getName();
	}
}
