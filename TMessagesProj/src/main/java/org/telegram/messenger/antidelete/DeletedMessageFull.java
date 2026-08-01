package org.telegram.messenger.antidelete;

import androidx.room.Embedded;
import androidx.room.Relation;

import java.util.List;

public class DeletedMessageFull {

    @Embedded
    public DeletedMessage message;

    @Relation(parentColumn = "fakeId", entityColumn = "deletedMessageId")
    public List<DeletedMessageReaction> reactions;
}
