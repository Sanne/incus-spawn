package dev.incusspawn.graal;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

// Checker.copySerializable() → SerializableUtils.fromBytes() → ObjectInputStream
// SerializableCodec.decodeFromWire()/transform() → SerializableUtils.fromBytes() → ObjectInputStream
// Safe: we use neither Vert.x shared data structures nor Serializable event-bus bodies.
// On Vert.x 4 SerializableCodec built its own reader and needed a substitution of its own;
// Vert.x 5 (Quarkus 4) routes it through fromBytes, so this one cut covers both (#2377).

@TargetClass(className = "io.vertx.core.impl.SerializableUtils")
final class Target_io_vertx_core_impl_SerializableUtils {

    @Substitute
    public static Object fromBytes(byte[] bytes,
            Target_io_vertx_core_impl_SerializableUtils_ObjectInputStreamFactory factory) {
        throw new UnsupportedOperationException(
                "Vert.x Java deserialization is excluded from native image");
    }
}

@TargetClass(className = "io.vertx.core.impl.SerializableUtils",
             innerClass = "ObjectInputStreamFactory")
interface Target_io_vertx_core_impl_SerializableUtils_ObjectInputStreamFactory {
}
