package me.fss.orbal.di

import me.fss.orbal.data.local.dao.ConversationDao
import me.fss.orbal.data.local.dao.MessageAttachmentDao
import me.fss.orbal.data.local.dao.MessageDao
import me.fss.orbal.data.local.dao.ModelDao
import me.fss.orbal.data.repository.ChatRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Provides
    @Singleton
    fun provideChatRepository(
        conversationDao: ConversationDao,
        messageDao: MessageDao,
        messageAttachmentDao: MessageAttachmentDao,
        modelDao: ModelDao,
    ): ChatRepository = ChatRepository(conversationDao, messageDao, messageAttachmentDao, modelDao)
}
