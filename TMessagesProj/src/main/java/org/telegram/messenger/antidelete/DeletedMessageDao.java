package org.telegram.messenger.antidelete;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;

import java.util.List;

@Dao
public interface DeletedMessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(DeletedMessage message);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertReaction(DeletedMessageReaction reaction);

    @Query("SELECT EXISTS(SELECT 1 FROM deleted_messages WHERE userId = :userId AND dialogId = :dialogId AND messageId = :messageId)")
    boolean exists(long userId, long dialogId, long topicId, int messageId);

    @Transaction
    @Query("SELECT * FROM deleted_messages WHERE userId = :userId AND dialogId = :dialogId AND messageId = :messageId LIMIT 1")
    DeletedMessageFull getMessage(long userId, long dialogId, int messageId);

    /**
     * For dialogs without topics (regular chats / DMs): the whole [minId, maxId]
     * window regardless of topicId.
     */
    @Transaction
    @Query("SELECT * FROM deleted_messages WHERE userId = :userId AND dialogId = :dialogId AND messageId BETWEEN :minId AND :maxId ORDER BY messageId ASC")
    List<DeletedMessageFull> getMessagesTopicless(long userId, long dialogId, int minId, int maxId);

    /**
     * For forum/topic dialogs: same window, scoped to one topic.
     */
    @Transaction
    @Query("SELECT * FROM deleted_messages WHERE userId = :userId AND dialogId = :dialogId AND topicId = :topicId AND messageId BETWEEN :minId AND :maxId ORDER BY messageId ASC")
    List<DeletedMessageFull> getMessagesForTopic(long userId, long dialogId, long topicId, int minId, int maxId);

    @Query("SELECT * FROM deleted_messages WHERE userId = :userId ORDER BY date DESC")
    List<DeletedMessage> getAllForAccount(long userId);

    @Query("DELETE FROM deleted_messages WHERE userId = :userId AND dialogId = :dialogId AND messageId = :messageId")
    void delete(long userId, long dialogId, int messageId);

    @Query("DELETE FROM deleted_messages WHERE userId = :userId AND dialogId = :dialogId AND (:beforeDate IS NULL OR date < :beforeDate)")
    void clearForDialog(long userId, long dialogId, Long beforeDate);

    @Query("SELECT dialogId FROM deleted_messages WHERE userId = :userId GROUP BY dialogId")
    List<Long> getDialogsWithDeletedMessages(long userId);

    // --- DeletedDialog (chat-list preview fallback) ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertDeletedDialog(DeletedDialog dialog);

    @Query("SELECT * FROM deleted_dialogs WHERE userId = :userId")
    List<DeletedDialog> getDeletedDialogs(long userId);

    @Query("DELETE FROM deleted_dialogs WHERE userId = :userId AND dialogId = :dialogId")
    void removeDeletedDialog(long userId, long dialogId);
}
