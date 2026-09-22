package me.fss.orbal.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.fss.orbal.data.local.ChatDatabase
import me.fss.orbal.data.local.dao.ConversationDao
import me.fss.orbal.data.local.dao.MessageDao
import me.fss.orbal.data.local.dao.ModelDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `knowledge_documents` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `projectId` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `path` TEXT NOT NULL,
                    `size` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `enabled` INTEGER NOT NULL
                )
            """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_documents_projectId` ON `knowledge_documents` (`projectId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_documents_name` ON `knowledge_documents` (`name`)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `knowledge_chunks` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `docId` INTEGER NOT NULL,
                    `content` TEXT NOT NULL,
                    `position` INTEGER NOT NULL,
                    FOREIGN KEY(`docId`) REFERENCES `knowledge_documents`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
            """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_chunks_docId` ON `knowledge_chunks` (`docId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_chunks_position` ON `knowledge_chunks` (`position`)")
        }
    }

    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `message_attachments` (
                    `id` TEXT NOT NULL PRIMARY KEY,
                    `messageId` TEXT NOT NULL,
                    `localPath` TEXT NOT NULL,
                    `mimeType` TEXT NOT NULL,
                    `width` INTEGER NOT NULL,
                    `height` INTEGER NOT NULL,
                    `sizeBytes` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    FOREIGN KEY(`messageId`) REFERENCES `messages`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_message_attachments_messageId` ON `message_attachments` (`messageId`)")
        }
    }

    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `conversations` ADD COLUMN `compactionSummary` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `conversations` ADD COLUMN `compactionUpToMessageId` TEXT")
            db.execSQL("ALTER TABLE `conversations` ADD COLUMN `compactionMessageCount` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `conversations` ADD COLUMN `compactionUpdatedAt` INTEGER NOT NULL DEFAULT 0")
        }
    }

    private val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `messages` ADD COLUMN `reasoningContent` TEXT")
        }
    }

    @Provides
    @Singleton
    fun provideChatDatabase(@ApplicationContext context: Context): ChatDatabase {
        return Room.databaseBuilder(
            context,
            ChatDatabase::class.java,
            "orbal_database"
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .fallbackToDestructiveMigration()
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()
    }

    @Provides
    fun provideConversationDao(db: ChatDatabase): ConversationDao = db.conversationDao()

    @Provides
    fun provideMessageDao(db: ChatDatabase): MessageDao = db.messageDao()

    @Provides
    fun provideModelDao(db: ChatDatabase): ModelDao = db.modelDao()

    @Provides
    fun provideKnowledgeDocumentDao(db: ChatDatabase) = db.knowledgeDocumentDao()

    @Provides
    fun provideKnowledgeChunkDao(db: ChatDatabase) = db.knowledgeChunkDao()

    @Provides
    fun provideMessageAttachmentDao(db: ChatDatabase) = db.messageAttachmentDao()
}
