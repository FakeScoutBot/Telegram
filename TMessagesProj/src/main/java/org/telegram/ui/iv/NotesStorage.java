package org.telegram.ui.iv;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.tl.TL_iv;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;

/**
 * Fully local, offline storage for Scout Notes. Notes are stored as files under
 * the app's private storage directory using Telegram's own TL_iv.RichMessage
 * binary serialization (the same format the rich editor already produces),
 * but they are never sent, uploaded, or synced anywhere - purely on-device.
 */
public class NotesStorage {

    public static class NoteMeta {
        public long id;
        public String title = "";
        public String preview = "";
        public long updatedAt;
    }

    private static File dir() {
        File d = new File(ApplicationLoader.applicationContext.getFilesDir(), "scout_notes");
        if (!d.exists()) {
            d.mkdirs();
        }
        return d;
    }

    private static File indexFile() {
        return new File(dir(), "index.json");
    }

    private static File noteFile(long id) {
        return new File(dir(), "note_" + id + ".dat");
    }

    public static ArrayList<NoteMeta> listNotes() {
        ArrayList<NoteMeta> result = new ArrayList<>();
        File f = indexFile();
        if (!f.exists()) {
            return result;
        }
        try {
            byte[] bytes = readFile(f);
            JSONArray arr = new JSONArray(new String(bytes, StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                NoteMeta m = new NoteMeta();
                m.id = o.getLong("id");
                m.title = o.optString("title", "");
                m.preview = o.optString("preview", "");
                m.updatedAt = o.optLong("updatedAt", 0);
                result.add(m);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        Collections.sort(result, (a, b) -> Long.compare(b.updatedAt, a.updatedAt));
        return result;
    }

    private static void saveIndex(ArrayList<NoteMeta> list) {
        try {
            JSONArray arr = new JSONArray();
            for (NoteMeta m : list) {
                JSONObject o = new JSONObject();
                o.put("id", m.id);
                o.put("title", m.title);
                o.put("preview", m.preview);
                o.put("updatedAt", m.updatedAt);
                arr.put(o);
            }
            writeFile(indexFile(), arr.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /** @param id pass 0 to create a new note. @return the note's id (existing or newly generated). */
    public static long saveNote(long id, TL_iv.RichMessage richMessage, String title, String preview) {
        if (id <= 0) {
            id = System.currentTimeMillis();
        }
        try {
            SerializedData data = new SerializedData(richMessage.getObjectSize());
            richMessage.serializeToStream(data);
            writeFile(noteFile(id), data.toByteArray());
        } catch (Exception e) {
            FileLog.e(e);
            return id;
        }
        ArrayList<NoteMeta> list = listNotes();
        NoteMeta existing = null;
        for (NoteMeta m : list) {
            if (m.id == id) {
                existing = m;
                break;
            }
        }
        if (existing == null) {
            existing = new NoteMeta();
            existing.id = id;
            list.add(existing);
        }
        existing.title = title == null ? "" : title;
        existing.preview = preview == null ? "" : preview;
        existing.updatedAt = System.currentTimeMillis();
        saveIndex(list);
        return id;
    }

    public static TL_iv.RichMessage loadNote(long id) {
        File f = noteFile(id);
        if (!f.exists()) {
            return null;
        }
        try {
            byte[] bytes = readFile(f);
            SerializedData data = new SerializedData(bytes);
            int constructor = data.readInt32(true);
            return TL_iv.RichMessage.TLdeserialize(data, constructor, true);
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    public static void deleteNote(long id) {
        //noinspection ResultOfMethodCallIgnored
        noteFile(id).delete();
        ArrayList<NoteMeta> list = listNotes();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id == id) {
                list.remove(i);
                break;
            }
        }
        saveIndex(list);
    }

    private static byte[] readFile(File f) throws Exception {
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int off = 0;
            while (off < buf.length) {
                int r = fis.read(buf, off, buf.length - off);
                if (r < 0) {
                    break;
                }
                off += r;
            }
            return buf;
        }
    }

    private static void writeFile(File f, byte[] bytes) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(bytes);
        }
    }
}
