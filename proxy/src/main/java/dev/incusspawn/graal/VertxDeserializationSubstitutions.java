package dev.incusspawn.graal;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

import io.vertx.core.buffer.Buffer;

// CodecManager → SerializableCodec.decodeFromWire()/transform()
//     → CheckedClassNameObjectInputStream (extends ObjectInputStream)
// Safe: we use Vert.x only as an HTTP server (MITM proxy).

@TargetClass(className = "io.vertx.core.eventbus.impl.codecs.SerializableCodec")
final class Target_io_vertx_core_eventbus_impl_codecs_SerializableCodec {

    @Substitute
    public Object decodeFromWire(int pos, Buffer buffer) {
        throw new UnsupportedOperationException(
                "Vert.x SerializableCodec is excluded from native image");
    }

    @Substitute
    public Object transform(Object obj) {
        throw new UnsupportedOperationException(
                "Vert.x SerializableCodec is excluded from native image");
    }
}

// Checker.checkCopyable() → SerializableUtils.fromBytes() → ObjectInputStream
// Safe: we do not use Vert.x shared data structures.

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
