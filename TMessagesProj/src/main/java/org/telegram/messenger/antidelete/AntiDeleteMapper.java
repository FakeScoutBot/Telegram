package org.telegram.messenger.antidelete;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * capture: TLRPC.Message (about to be purged) -> DeletedMessage Room row.
 * reconstruct: DeletedMessage Room row -> a live TLRPC.TL_message, indistinguishable
 * (for rendering purposes) from a normal loaded message except for antiDeleted=true.
 *
 * Media handling here covers the two common cases explicitly (document, photo).
 * Other media kinds (poll, contact, geo, venue, dice, game, invoice, story-repost,
 * webpage-only) fall back to preserving the text/entities and dropping the media
 * body -- extend mapMedia()/reconstructMedia() per-type as you add support, this
 * is intentionally not a complete media-type matrix.
 */
public class AntiDeleteMapper {

    /** Root folder for physically-copied media, separate from Telegram's own
     *  cache so it survives Telegram's cache-clearing. */
    private static File mediaRoot(int account) {
        File dir = new File(ApplicationLoader.applicationContext.getFilesDir(), "antidelete_media/" + account);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public static DeletedMessage map(SaveMessageRequest req) {
        TLRPC.Message m = req.message;
        DeletedMessage e = new DeletedMessage();

        e.userId = UserConfig.getInstance(req.currentAccount).getClientUserId();
        e.dialogId = req.dialogId;
        e.peerId = MessageObject.getPeerId(m.peer_id);
        e.fromId = m.from_id != null ? MessageObject.getPeerId(m.from_id) : 0;
        e.topicId = req.topicId;
        e.messageId = req.messageId;
        e.date = m.date;
        e.editDate = m.edit_date;
        e.flags = m.flags;
        e.views = m.views;
        e.text = m.message;
        e.groupedId = m.grouped_id;
        e.postAuthor = m.post_author;
        e.entityCreateDate = req.requestCatchTime;

        if (!m.entities.isEmpty()) {
            ArrayList<TLObject> entityList = new ArrayList<>(m.entities);
            e.textEntities = TLSerializationHelper.serializeMultiple(entityList);
        }

        if (m.reply_to != null) {
            e.replySerialized = TLSerializationHelper.serializeSingle(m.reply_to);
            e.replyMessageId = m.reply_to.reply_to_msg_id;
            e.replyTopId = m.reply_to.reply_to_top_id;
            e.replyPeerId = m.reply_to.reply_to_peer_id != null ? MessageObject.getPeerId(m.reply_to.reply_to_peer_id) : 0;
            e.replyFlags = m.reply_to.flags;
            e.replyForumTopic = m.reply_to.forum_topic;
        }

        if (m.reply_markup != null) {
            e.replyMarkupSerialized = TLSerializationHelper.serializeSingle(m.reply_markup);
        }

        if (m.fwd_from != null) {
            e.fwdFlags = m.fwd_from.flags;
            e.fwdDate = m.fwd_from.date;
            e.fwdName = m.fwd_from.from_name;
            e.fwdPostAuthor = m.fwd_from.post_author;
            if (m.fwd_from.from_id != null) {
                e.fwdFromId = MessageObject.getPeerId(m.fwd_from.from_id);
            }
        }

        mapMedia(req, e, true);

        return e;
    }

    /**
     * Copies the physical media file into our own persistent storage (so it
     * survives Telegram's cache eviction) and serializes the Document/PhotoSize
     * metadata into the blob columns.
     */
    public static void mapMedia(SaveMessageRequest req, DeletedMessage e, boolean copyFile) {
        TLRPC.Message m = req.message;
        if (m.media == null) {
            e.documentType = 0;
            return;
        }
        try {
            if (m.media.document != null) {
                e.documentType = 1;
                e.documentSerialized = TLSerializationHelper.serializeSingle(m.media.document);
                e.mimeType = m.media.document.mime_type;
                if (!m.media.document.attributes.isEmpty()) {
                    ArrayList<TLObject> attrs = new ArrayList<>(m.media.document.attributes);
                    e.documentAttributesSerialized = TLSerializationHelper.serializeMultiple(attrs);
                }
                if (!m.media.document.thumbs.isEmpty()) {
                    ArrayList<TLObject> thumbs = new ArrayList<>(m.media.document.thumbs);
                    e.thumbsSerialized = TLSerializationHelper.serializeMultiple(thumbs);
                }
            } else if (m.media.photo != null) {
                e.documentType = 2;
                if (!m.media.photo.sizes.isEmpty()) {
                    ArrayList<TLObject> sizes = new ArrayList<>(m.media.photo.sizes);
                    e.thumbsSerialized = TLSerializationHelper.serializeMultiple(sizes);
                }
            } else {
                // Poll / contact / geo / venue / dice / game / invoice / webpage-only /
                // story repost, etc: text + entities are preserved above; media body
                // is intentionally not reconstructed for these types yet.
                e.documentType = 0;
                return;
            }

            if (copyFile) {
                File source = FileLoader.getInstance(req.currentAccount).getPathToMessage(m);
                if (source != null && source.exists()) {
                    File dest = new File(mediaRoot(req.currentAccount), req.dialogId + "_" + req.messageId + "_" + source.getName());
                    copyFile(source, dest);
                    e.mediaPath = dest.getAbsolutePath();
                }
            }
        } catch (Exception ex) {
            FileLog.e(ex);
        }
    }

    private static void copyFile(File source, File dest) throws Exception {
        try (FileInputStream in = new FileInputStream(source); FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[64 * 1024];
            int read;
            while ((read = in.read(buf)) > 0) {
                out.write(buf, 0, read);
            }
        }
    }

    /**
     * Rebuilds a live TL_message from a stored row, indistinguishable from a
     * normal message for rendering except for antiDeleted == true.
     */
    public static TLRPC.TL_message reconstruct(int currentAccount, DeletedMessageFull full) {
        DeletedMessage e = full.message;
        TLRPC.TL_message m = new TLRPC.TL_message();
        m.id = e.messageId;
        m.date = e.date;
        m.edit_date = e.editDate;
        m.flags = e.flags;
        m.views = e.views;
        m.message = e.text != null ? e.text : "";
        m.grouped_id = e.groupedId;
        m.post_author = e.postAuthor;
        m.dialog_id = e.dialogId;
        m.antiDeleted = true;

        m.peer_id = peerFromLong(e.dialogId, e.peerId);
        m.from_id = peerFromLong(e.dialogId, e.fromId);
        m.out = e.fromId != 0 && e.fromId == UserConfig.getInstance(currentAccount).getClientUserId();

        if (e.textEntities != null) {
            m.entities = TLSerializationHelper.deserializeMultiple(e.textEntities, AntiDeleteMapper::readEntity);
        }

        if (e.replySerialized != null) {
            m.reply_to = TLSerializationHelper.deserializeSingle(e.replySerialized, AntiDeleteMapper::readReplyHeader);
        }

        if (e.replyMarkupSerialized != null) {
            m.reply_markup = TLSerializationHelper.deserializeSingle(e.replyMarkupSerialized, AntiDeleteMapper::readReplyMarkup);
        }

        if (e.fwdFlags != 0 || e.fwdDate != 0 || e.fwdName != null) {
            TLRPC.TL_messageFwdHeader fwd = new TLRPC.TL_messageFwdHeader();
            fwd.flags = e.fwdFlags;
            fwd.date = e.fwdDate;
            fwd.from_name = e.fwdName;
            fwd.post_author = e.fwdPostAuthor;
            if (e.fwdFromId != 0) {
                fwd.from_id = peerFromLong(e.dialogId, e.fwdFromId);
            }
            m.fwd_from = fwd;
        }

        reconstructMedia(e, m);

        if (full.reactions != null && !full.reactions.isEmpty()) {
            TLRPC.TL_messageReactions reactions = new TLRPC.TL_messageReactions();
            for (DeletedMessageReaction r : full.reactions) {
                TLRPC.TL_reactionCount rc = new TLRPC.TL_reactionCount();
                rc.count = r.count;
                rc.chosen = r.selfSelected;
                if (r.selfSelected) {
                    rc.flags |= 1; // FLAG_0
                }
                if (r.isCustom) {
                    TLRPC.TL_reactionCustomEmoji custom = new TLRPC.TL_reactionCustomEmoji();
                    custom.document_id = r.documentId;
                    rc.reaction = custom;
                } else if (r.isPaid) {
                    rc.reaction = new TLRPC.TL_reactionPaid();
                } else {
                    TLRPC.TL_reactionEmoji emoji = new TLRPC.TL_reactionEmoji();
                    emoji.emoticon = r.emoticon;
                    rc.reaction = emoji;
                }
                reactions.results.add(rc);
            }
            m.reactions = reactions;
        }

        return m;
    }

    private static void reconstructMedia(DeletedMessage e, TLRPC.TL_message m) {
        if (e.documentType == 1 && e.documentSerialized != null) {
            TLRPC.TL_messageMediaDocument media = new TLRPC.TL_messageMediaDocument();
            media.document = TLSerializationHelper.deserializeSingle(e.documentSerialized, AntiDeleteMapper::readDocument);
            if (media.document != null) {
                if (e.documentAttributesSerialized != null) {
                    media.document.attributes = TLSerializationHelper.deserializeMultiple(e.documentAttributesSerialized, AntiDeleteMapper::readDocumentAttribute);
                }
                if (e.thumbsSerialized != null) {
                    media.document.thumbs = TLSerializationHelper.deserializeMultiple(e.thumbsSerialized, AntiDeleteMapper::readPhotoSize);
                }
                media.flags |= 1;
            }
            m.media = media;
        } else if (e.documentType == 2) {
            TLRPC.TL_messageMediaPhoto media = new TLRPC.TL_messageMediaPhoto();
            TLRPC.TL_photo photo = new TLRPC.TL_photo();
            if (e.thumbsSerialized != null) {
                photo.sizes = TLSerializationHelper.deserializeMultiple(e.thumbsSerialized, AntiDeleteMapper::readPhotoSize);
            }
            media.photo = photo;
            media.flags |= 1;
            m.media = media;
        }
        // NOTE: e.mediaPath (the locally-copied file) is not wired back into the
        // reconstructed TLRPC.Document/Photo here -- ChatMessageCell/ImageReceiver
        // resolve media by cache-key derived from the Document/Photo id+size, not
        // by an arbitrary path. Making the copied file actually resolve on load
        // requires either (a) registering e.mediaPath with FileLoader under the
        // same cache key the Document/Photo would normally produce, or (b) a
        // custom ImageLocation that points straight at e.mediaPath. Either is
        // real, non-trivial wiring -- flagging rather than faking it.
    }

    private static TLRPC.Peer peerFromLong(long dialogId, long id) {
        // NOTE: this collapses TL_peerChat (basic group) and TL_peerChannel into
        // the same negative-id branch, since getPeerId() encodes both as -id and
        // MessageObject.getPeerId(TLRPC.Peer) is the only inverse we captured.
        // Message authorship in groups is virtually always a TL_peerUser, so this
        // only matters for the rare service-message-from-chat case; if you need
        // chat_id specifically, store the original peer type alongside peerId.
        if (id > 0) {
            TLRPC.TL_peerUser p = new TLRPC.TL_peerUser();
            p.user_id = id;
            return p;
        } else {
            TLRPC.TL_peerChannel pc = new TLRPC.TL_peerChannel();
            pc.channel_id = -id;
            return pc;
        }
    }

    // --- TL self-parsing readers (constructor id + TLdeserialize) ---

    private static TLRPC.MessageEntity readEntity(NativeByteBuffer buf) {
        int c = buf.readInt32(true);
        return TLRPC.MessageEntity.TLdeserialize(buf, c, true);
    }

    private static TLRPC.MessageReplyHeader readReplyHeader(NativeByteBuffer buf) {
        int c = buf.readInt32(true);
        return TLRPC.MessageReplyHeader.TLdeserialize(buf, c, true);
    }

    private static TLRPC.ReplyMarkup readReplyMarkup(NativeByteBuffer buf) {
        int c = buf.readInt32(true);
        return TLRPC.ReplyMarkup.TLdeserialize(buf, c, true);
    }

    private static TLRPC.Document readDocument(NativeByteBuffer buf) {
        int c = buf.readInt32(true);
        return TLRPC.Document.TLdeserialize(buf, c, true);
    }

    private static TLRPC.DocumentAttribute readDocumentAttribute(NativeByteBuffer buf) {
        int c = buf.readInt32(true);
        return TLRPC.DocumentAttribute.TLdeserialize(buf, c, true);
    }

    private static TLRPC.PhotoSize readPhotoSize(NativeByteBuffer buf) {
        int c = buf.readInt32(true);
        return TLRPC.PhotoSize.TLdeserialize(buf, c, true);
    }
}
