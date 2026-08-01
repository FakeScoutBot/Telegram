package org.telegram.messenger.antidelete;

import org.telegram.messenger.FileLog;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLObject;

import java.util.ArrayList;
import java.util.function.Function;

/**
 * Stores exact wire-format TL objects (entities, reply headers, documents, reaction
 * lists, reply markup) as opaque byte[] blobs (Room BLOB columns), and reconstructs
 * the identical live objects later. This is what lets the feature avoid losing any
 * formatting/entity/media metadata when a message is captured for recovery.
 *
 * IMPORTANT: NativeByteBuffer in this codebase is backed by a pooled *direct*
 * ByteBuffer (native_getJavaByteBuffer) -- it has no public byte[] constructor and
 * buffer.buffer.array() is not safe to call on it (direct buffers throw
 * UnsupportedOperationException on array()). So conversion to/from a plain byte[]
 * for Room storage goes through explicit get()/put() copies below rather than
 * NativeByteBuffer's own (de)serialization helpers, which normally read straight
 * from a SQLite cursor's native blob (see MessagesStorage's cursor.byteBufferValue()
 * calls) and never need a plain byte[] at all.
 */
public class TLSerializationHelper {

    private TLSerializationHelper() {
    }

    public static byte[] serializeSingle(TLObject obj) {
        if (obj == null) {
            return null;
        }
        ArrayList<TLObject> list = new ArrayList<>(1);
        list.add(obj);
        return serializeMultiple(list);
    }

    public static byte[] serializeMultiple(ArrayList<TLObject> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        NativeByteBuffer buffer = null;
        try {
            int size = 0;
            for (int i = 0, n = list.size(); i < n; i++) {
                size += list.get(i).getObjectSize();
            }
            if (size <= 0) {
                return null;
            }
            buffer = new NativeByteBuffer(size);
            for (int i = 0, n = list.size(); i < n; i++) {
                list.get(i).serializeToStream(buffer);
            }
            byte[] out = new byte[size];
            buffer.buffer.position(0);
            buffer.buffer.get(out);
            return out;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        } finally {
            if (buffer != null) {
                buffer.reuse();
            }
        }
    }

    public static <T extends TLObject> T deserializeSingle(byte[] data, Function<NativeByteBuffer, T> ctor) {
        ArrayList<T> list = deserializeMultiple(data, ctor);
        return list.isEmpty() ? null : list.get(0);
    }

    public static <T extends TLObject> ArrayList<T> deserializeMultiple(byte[] data, Function<NativeByteBuffer, T> ctor) {
        ArrayList<T> result = new ArrayList<>();
        if (data == null || data.length == 0) {
            return result;
        }
        NativeByteBuffer buffer = null;
        try {
            buffer = new NativeByteBuffer(data.length);
            buffer.buffer.put(data);
            buffer.buffer.position(0);
            buffer.buffer.limit(data.length);
            while (buffer.buffer.hasRemaining()) {
                T obj = ctor.apply(buffer);
                if (obj == null) {
                    break;
                }
                result.add(obj);
            }
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            if (buffer != null) {
                buffer.reuse();
            }
        }
        return result;
    }
}
