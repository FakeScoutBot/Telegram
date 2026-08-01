package org.telegram.messenger.antidelete;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class AntiDeleteMapper {

    public void map(SaveMessageRequest req, AntiDeleteDatabase.DeletedMessage out) {
        TLRPC.Message message = req.message;
        out.userId = org.telegram.messenger.UserConfig.getInstance(req.accountId).getClientUserId();
        out.dialogId = req.dialogId;
        out.peerId = MessageObject.getPeerId(message.peer_id);
        out.fromId = MessageObject.getPeerId(message.from_id);
        out.topicId = req.topicId;
        out.groupedId = message.grouped_id;
        out.messageId = message.id;
        out.date = message.date;
        out.editDate = message.edit_date;
        out.flags = message.flags;
        out.views = message.views;
        out.text = message.message;
        out.postAuthor = message.post_author;
        out.entityCreateDate = req.requestCatchTime;
        out.messageSerialized = TLSerializationHelper.serializeSingle(message);

        if (message.fwd_from != null) {
            out.fwdFlags = message.fwd_from.flags;
            out.fwdDate = message.fwd_from.date;
            out.fwdName = message.fwd_from.from_name;
            out.fwdPostAuthor = message.fwd_from.post_author;
            if (message.fwd_from.from_id != null) {
                out.fwdFromId = MessageObject.getPeerId(message.fwd_from.from_id);
            }
        }

        if (message.reply_to != null) {
            out.replySerialized = TLSerializationHelper.serializeSingle(message.reply_to);
            out.replyFlags = message.reply_to.flags;
            out.replyMessageId = message.reply_to.reply_to_msg_id;
            out.replyTopId = message.reply_to.reply_to_top_id;
            out.replyForumTopic = message.reply_to.forum_topic;
            if (message.reply_to.reply_to_peer_id != null) {
                out.replyPeerId = MessageObject.getPeerId(message.reply_to.reply_to_peer_id);
            }
        }

        out.replyMarkupSerialized = TLSerializationHelper.serializeSingle(message.reply_markup);

        if (message.entities != null && !message.entities.isEmpty()) {
            out.textEntities = TLSerializationHelper.serializeMultiple(new ArrayList<>(message.entities));
        }

        if (MessageObject.getMedia(message) instanceof TLRPC.TL_messageMediaDocument && MessageObject.getMedia(message).document != null) {
            out.documentSerialized = TLSerializationHelper.serializeSingle(MessageObject.getMedia(message).document);
            out.documentAttributesSerialized = TLSerializationHelper.serializeMultiple(new ArrayList<>(MessageObject.getMedia(message).document.attributes));
            out.mimeType = MessageObject.getMedia(message).document.mime_type;
            out.documentType = 1;
        } else if (MessageObject.getMedia(message) instanceof TLRPC.TL_messageMediaPhoto && MessageObject.getMedia(message).photo != null) {
            out.thumbsSerialized = TLSerializationHelper.serializeMultiple(new ArrayList<>(MessageObject.getMedia(message).photo.sizes));
            out.documentType = 2;
        }
    }

    public void mapMedia(SaveMessageRequest req, AntiDeleteDatabase.DeletedMessage out, boolean copyMedia) {
        if (!copyMedia || req.message == null) {
            return;
        }
        try {
            File src = FileLoader.getInstance(req.accountId).getPathToMessage(req.message);
            if (src == null || !src.exists() || src.length() <= 0) {
                return;
            }
            File dir = new File(org.telegram.messenger.ApplicationLoader.applicationContext.getFilesDir(), "antidelete_media/" + req.accountId);
            if (!dir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            }
            String ext = "";
            String name = src.getName();
            int dot = name.lastIndexOf('.');
            if (dot >= 0) {
                ext = name.substring(dot);
            }
            File dst = new File(dir, req.dialogId + "_" + req.message.id + ext);
            AndroidUtilities.copyFile(src, dst);
            out.mediaPath = dst.getAbsolutePath();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public TLRPC.Message reconstruct(AntiDeleteDatabase.DeletedMessageFull full) {
        AntiDeleteDatabase.DeletedMessage saved = full.message;
        TLRPC.Message message = TLSerializationHelper.deserializeSingle(saved.messageSerialized, buffer -> TLRPC.Message.TLdeserialize(buffer, buffer.readInt32(false), false));
        if (message == null) {
            TLRPC.TL_message fallback = new TLRPC.TL_message();
            fallback.id = saved.messageId;
            fallback.date = saved.date;
            fallback.edit_date = saved.editDate;
            fallback.flags = saved.flags;
            fallback.views = saved.views;
            fallback.grouped_id = saved.groupedId;
            fallback.message = saved.text;
            fallback.post_author = saved.postAuthor;
            fallback.reply_markup = TLSerializationHelper.deserializeSingle(saved.replyMarkupSerialized, b -> TLRPC.ReplyMarkup.TLdeserialize(b, b.readInt32(false), false));
            fallback.reply_to = TLSerializationHelper.deserializeSingle(saved.replySerialized, b -> TLRPC.MessageReplyHeader.TLdeserialize(b, b.readInt32(false), false));
            fallback.entities = TLSerializationHelper.deserializeMultiple(saved.textEntities, b -> TLRPC.MessageEntity.TLdeserialize(b, b.readInt32(false), false));
            fallback.peer_id = peerFrom(saved.peerId);
            fallback.from_id = peerFrom(saved.fromId);
            message = fallback;
        }

        message.antiDeleted = true;
        message.id = saved.messageId;
        message.dialog_id = saved.dialogId;
        if (message.peer_id == null) {
            message.peer_id = peerFrom(saved.peerId == 0 ? saved.dialogId : saved.peerId);
        }
        if (message.from_id == null && saved.fromId != 0) {
            message.from_id = peerFrom(saved.fromId);
        }

        if (message.reactions == null && full.reactions != null && !full.reactions.isEmpty()) {
            message.reactions = new TLRPC.TL_messageReactions();
            message.reactions.results = new ArrayList<>();
            for (AntiDeleteDatabase.DeletedMessageReaction reaction : full.reactions) {
                TLRPC.TL_reactionCount c = new TLRPC.TL_reactionCount();
                c.count = reaction.count;
                c.chosen = reaction.selfSelected;
                if (reaction.selfSelected) {
                    c.flags |= 1;
                }
                if (reaction.isPaid) {
                    c.reaction = new TLRPC.TL_reactionPaid();
                } else if (reaction.isCustom) {
                    TLRPC.TL_reactionCustomEmoji custom = new TLRPC.TL_reactionCustomEmoji();
                    custom.document_id = reaction.documentId;
                    c.reaction = custom;
                } else {
                    TLRPC.TL_reactionEmoji emoji = new TLRPC.TL_reactionEmoji();
                    emoji.emoticon = reaction.emoticon;
                    c.reaction = emoji;
                }
                message.reactions.results.add(c);
            }
        }

        if (saved.mediaPath != null && message.media != null) {
            if (message.media.document != null) {
                message.media.document.localPath = saved.mediaPath;
            } else if (message.media.webpage != null && message.media.webpage.document != null) {
                message.media.webpage.document.localPath = saved.mediaPath;
            }
        }
        return message;
    }

    public AntiDeleteDatabase.DeletedMessageReaction toReactionEntity(long deletedMessageId, TLRPC.ReactionCount rc) {
        AntiDeleteDatabase.DeletedMessageReaction row = new AntiDeleteDatabase.DeletedMessageReaction();
        row.deletedMessageId = deletedMessageId;
        row.count = rc.count;
        row.selfSelected = rc.chosen;
        if (rc.reaction instanceof TLRPC.TL_reactionCustomEmoji) {
            row.isCustom = true;
            row.documentId = ((TLRPC.TL_reactionCustomEmoji) rc.reaction).document_id;
        } else if (rc.reaction instanceof TLRPC.TL_reactionPaid) {
            row.isPaid = true;
        } else if (rc.reaction instanceof TLRPC.TL_reactionEmoji) {
            row.emoticon = ((TLRPC.TL_reactionEmoji) rc.reaction).emoticon;
        }
        return row;
    }

    private TLRPC.Peer peerFrom(long peerId) {
        if (peerId == 0) {
            return null;
        }
        if (peerId > 0) {
            TLRPC.TL_peerUser p = new TLRPC.TL_peerUser();
            p.user_id = peerId;
            return p;
        }
        TLRPC.TL_peerChannel p = new TLRPC.TL_peerChannel();
        p.channel_id = -peerId;
        return p;
    }
}
