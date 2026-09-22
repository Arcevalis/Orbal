package me.fss.orbal.di

import android.app.Application
import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.fss.orbal.ai.InferenceEngine
import me.fss.orbal.ai.ModelManager
import me.fss.orbal.ai.compaction.CompactionManager
import me.fss.orbal.ai.remote.LmStudioClient
import me.fss.orbal.ai.remote.RemoteInferenceEngine
import me.fss.orbal.ai.tools.ToolExecutor
import me.fss.orbal.data.local.ChatDatabase
import me.fss.orbal.data.rag.RetrievalService
import me.fss.orbal.data.repository.ChatRepository
import me.fss.orbal.data.repository.KnowledgeRepository
import me.fss.orbal.data.repository.SettingsRepository
import me.fss.orbal.ui.screens.ChatViewModel
import me.fss.orbal.ui.screens.KnowledgeBaseViewModel
import me.fss.orbal.ui.screens.OnboardingViewModel
import me.fss.orbal.ui.screens.SettingsViewModel

val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("No AppContainer provided")
}

class AppContainer(private val appContext: Context) {

    // --- Core ---

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(appContext)
    }

    val lmStudioClient: LmStudioClient by lazy {
        LmStudioClient()
    }

    // --- Database ---

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

    val chatDatabase: ChatDatabase by lazy {
        Room.databaseBuilder(
            appContext,
            ChatDatabase::class.java,
            "orbal_database"
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .fallbackToDestructiveMigration()
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()
    }

    // DAOs
    private val conversationDao by lazy { chatDatabase.conversationDao() }
    private val messageDao by lazy { chatDatabase.messageDao() }
    private val modelDao by lazy { chatDatabase.modelDao() }
    private val knowledgeDocumentDao by lazy { chatDatabase.knowledgeDocumentDao() }
    private val knowledgeChunkDao by lazy { chatDatabase.knowledgeChunkDao() }
    private val messageAttachmentDao by lazy { chatDatabase.messageAttachmentDao() }

    // --- Repositories / Services ---

    val chatRepository: ChatRepository by lazy {
        ChatRepository(conversationDao, messageDao, messageAttachmentDao, modelDao)
    }

    val retrievalService: RetrievalService by lazy {
        RetrievalService(knowledgeDocumentDao, knowledgeChunkDao)
    }

    val knowledgeRepository: KnowledgeRepository by lazy {
        KnowledgeRepository(appContext, knowledgeDocumentDao, knowledgeChunkDao, retrievalService)
    }

    // --- Inference ---

    val inferenceEngine: InferenceEngine by lazy {
        InferenceEngine(settingsRepository)
    }

    val toolExecutor: ToolExecutor by lazy {
        ToolExecutor(appContext, retrievalService, settingsRepository)
    }

    val remoteInferenceEngine: RemoteInferenceEngine by lazy {
        RemoteInferenceEngine(lmStudioClient, settingsRepository, toolExecutor)
    }

    val compactionManager: CompactionManager by lazy {
        CompactionManager(settingsRepository, lmStudioClient, inferenceEngine)
    }

    val modelManager: ModelManager by lazy {
        ModelManager(appContext, chatRepository, settingsRepository, inferenceEngine)
    }

    // --- ViewModel Factory ---

    class Factory(
        private val container: AppContainer,
        private val application: Application,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return when {
                modelClass.isAssignableFrom(ChatViewModel::class.java) ->
                    ChatViewModel(
                        application = application,
                        chatRepository = container.chatRepository,
                        settingsRepository = container.settingsRepository,
                        modelManager = container.modelManager,
                        inferenceEngine = container.inferenceEngine,
                        remoteInferenceEngine = container.remoteInferenceEngine,
                        knowledgeRepository = container.knowledgeRepository,
                        compactionManager = container.compactionManager,
                        lmStudioClient = container.lmStudioClient,
                    ) as T

                modelClass.isAssignableFrom(SettingsViewModel::class.java) ->
                    SettingsViewModel(
                        application = application,
                        settingsRepository = container.settingsRepository,
                        chatRepository = container.chatRepository,
                        modelManager = container.modelManager,
                        lmStudioClient = container.lmStudioClient,
                    ) as T

                modelClass.isAssignableFrom(KnowledgeBaseViewModel::class.java) ->
                    KnowledgeBaseViewModel(
                        app = application,
                        repo = container.knowledgeRepository,
                    ) as T

                modelClass.isAssignableFrom(OnboardingViewModel::class.java) ->
                    OnboardingViewModel(
                        modelManager = container.modelManager,
                        settingsRepository = container.settingsRepository,
                    ) as T

                else -> throw IllegalArgumentException("Unknown ViewModel class $modelClass")
            }
        }
    }
}
