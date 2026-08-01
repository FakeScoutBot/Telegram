package org.telegram.messenger.antidelete;

import org.telegram.messenger.FileLog;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLObject;

import java.util.ArrayList;
import java.util.function.Function;

public class TLSerializationHelper {

    public static byte[] serializeSingle(TLObject obj) {
        if (obj == null) {
            return null;
        }
        ArrayList<TLObject> list = new ArrayList<>(1);
        list.add(obj);
        return serializeMultiple(list);
    }

    public static byte[] serializeMultiple(ArrayList<? extends TLObject> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        int size = 0;
        for (int i = 0; i < list.size(); i++) {
            TLObject object = list.get(i);
            if (object != null) {
                size += object.getObjectSize();
            }
        }
        if (size == 0) {
            return null;
        }
        NativeByteBuffer buffer = null;
        try {
            buffer = new NativeByteBuffer(size);
            for (int i = 0; i < list.size(); i++) {
                TLObject object = list.get(i);
                if (object != null) {
                    object.serializeToStream(buffer);
                }
            }
            int length = buffer.position();
            byte[] result = new byte[length];
            buffer.position(0);
            buffer.buffer.get(result);
            return result;
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
        if (data == null || data.length == 0 || ctor == null) {
            return result;
        }
        NativeByteBuffer buffer = null;
        try {
            buffer = new NativeByteBuffer(data.length);
            buffer.writeBytes(data);
            buffer.position(0);
            while (buffer.hasRemaining()) {
                T object = ctor.apply(buffer);
                if (object == null) {
                    break;
                }
                result.add(object);
            }
        } catch (Exception e) {
            FileLog.e(e);
            result.clear();
        } finally {
            if (buffer != null) {
                buffer.reuse();
            }
        }
        return result;
    }
}
