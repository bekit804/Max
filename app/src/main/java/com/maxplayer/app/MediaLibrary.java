package com.maxplayer.app;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Список треков, выбранных пользователем, и обход папки через системный выбор (SAF). */
final class MediaLibrary {

    static final class Item {
        final Uri uri;
        final String name;
        final String folder;
        final long size;

        Item(Uri uri, String name, String folder, long size) {
            this.uri = uri;
            this.name = name;
            this.folder = folder;
            this.size = size;
        }
    }

    static final class Scan {
        final String name;
        final List<Item> items;

        Scan(String name, List<Item> items) {
            this.name = name;
            this.items = items;
        }
    }

    private static final int MAX_ITEMS = 50000;

    private List<Item> items = new ArrayList<>();
    private String name = "";

    synchronized void set(Scan scan) {
        this.items = scan.items;
        this.name = scan.name;
    }

    synchronized Item get(int index) {
        return (index >= 0 && index < items.size()) ? items.get(index) : null;
    }

    synchronized String toJson() {
        StringBuilder sb = new StringBuilder(items.size() * 96 + 64);
        sb.append("{\"name\":").append(JsonUtil.quote(name)).append(",\"items\":[");
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"i\":").append(i)
              .append(",\"n\":").append(JsonUtil.quote(it.name))
              .append(",\"f\":").append(JsonUtil.quote(it.folder))
              .append(",\"s\":").append(it.size)
              .append('}');
        }
        return sb.append("]}").toString();
    }

    /** Обходит выбранную папку и все вложенные, собирает аудиофайлы. */
    static Scan scanTree(ContentResolver cr, Uri tree) {
        String rootId = DocumentsContract.getTreeDocumentId(tree);
        String rootName = displayName(cr, DocumentsContract.buildDocumentUriUsingTree(tree, rootId));
        if (rootName == null || rootName.isEmpty()) rootName = "Музыка";

        List<Item> out = new ArrayList<>();
        ArrayDeque<String[]> stack = new ArrayDeque<>();
        stack.push(new String[] { rootId, rootName });
        String[] projection = {
                Document.COLUMN_DOCUMENT_ID,
                Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_MIME_TYPE,
                Document.COLUMN_SIZE
        };

        while (!stack.isEmpty() && out.size() < MAX_ITEMS) {
            String[] dir = stack.pop();
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dir[0]);
            Cursor c = null;
            try {
                c = cr.query(children, projection, null, null, null);
                if (c == null) continue;
                while (c.moveToNext()) {
                    String id = c.getString(0);
                    String name = c.getString(1);
                    String mime = c.getString(2);
                    long size = c.isNull(3) ? 0 : c.getLong(3);
                    if (id == null || name == null || name.startsWith(".")) continue;
                    if (Document.MIME_TYPE_DIR.equals(mime)) {
                        stack.push(new String[] { id, dir[1] + " / " + name });
                    } else if (isAudio(mime, name)) {
                        out.add(new Item(DocumentsContract.buildDocumentUriUsingTree(tree, id), name, dir[1], size));
                    }
                }
            } catch (Exception e) {
                // папка недоступна, идём дальше
            } finally {
                if (c != null) c.close();
            }
        }
        return new Scan(rootName, out);
    }

    private static String displayName(ContentResolver cr, Uri doc) {
        Cursor c = null;
        try {
            c = cr.query(doc, new String[] { Document.COLUMN_DISPLAY_NAME }, null, null, null);
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception e) {
            // имя корня не обязательно
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    static boolean isAudio(String mime, String name) {
        if (mime != null && (mime.startsWith("audio/") || "application/ogg".equals(mime))) return true;
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".aac") || n.endsWith(".ogg")
                || n.endsWith(".oga") || n.endsWith(".opus") || n.endsWith(".wav") || n.endsWith(".flac")
                || n.endsWith(".weba");
    }

    static String mimeFor(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".m4a")) return "audio/mp4";
        if (n.endsWith(".aac")) return "audio/aac";
        if (n.endsWith(".ogg") || n.endsWith(".oga") || n.endsWith(".opus")) return "audio/ogg";
        if (n.endsWith(".wav")) return "audio/wav";
        if (n.endsWith(".flac")) return "audio/flac";
        if (n.endsWith(".weba")) return "audio/webm";
        return "application/octet-stream";
    }
}
