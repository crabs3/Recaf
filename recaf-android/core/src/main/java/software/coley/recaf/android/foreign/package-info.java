/**
 * Minimal stand-in for {@code java.lang.foreign}, which Android does not provide.
 * <p>
 * Desktop Recaf and LL-Java-Zip model file contents as {@code MemorySegment}s. On Android the build rewrites
 * those references to the types in this package (source rewriting for Recaf's own code, bytecode relocation for
 * LL-Java-Zip). Only the read-oriented subset of the API that those two code bases use is implemented, backed by
 * {@link java.nio.ByteBuffer}s, which limits a single segment to 2GB.
 */
package software.coley.recaf.android.foreign;
