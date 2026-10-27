package cn.wildfirechat.proto.store;

import android.content.Context;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.comsince.github.logger.Log;
import com.comsince.github.logger.LoggerFactory;

public class ChatStoreHelper extends SQLiteOpenHelper {
    Log logger = LoggerFactory.getLogger(ChatStoreHelper.class);
    private static final String TAG                 = ChatStoreHelper.class.getName();
    public static final String DATABASE_NAME       = "chat.db";
    public static final String TABLE_MESSAGES         = "messages";
    public static final String TABLE_CONVERSATIONS = "conversations";
    public static final String TABLE_CONVERSATION_GAPS = "conversation_gaps";
    private static final String queryDropMessagesTable = "DROP TABLE IF EXISTS '" + TABLE_MESSAGES + "'";
    private static final String queryDropConversationsTable = "DROP TABLE IF EXISTS '" + TABLE_CONVERSATIONS + "'";
    private static final String queryCreateMessagesTable = "CREATE TABLE IF NOT EXISTS 'messages' " +
            "(id INTEGER PRIMARY KEY," +
            " message_target TEXT," +
            " message_id INTEGER DEFAULT 0," +
            " message_uid INTEGER DEFAULT 0," +
            " message_data BLOB, " +
            " message_searchable_data TEXT," +
            " date_created TIMESTAMP DEFAULT CURRENT_TIMESTAMP)";

    private static final String queryCreateConversationsTable = "CREATE TABLE IF NOT EXISTS 'conversations' " +
            "(id INTEGER PRIMARY KEY," +
            " conversation_type INT DEFAULT 0," +
            " conversation_line INT DEFAULT 0," +
            " conversation_target TEXT NOT NULL," +
            " conversation_info BLOB, " +
            " date_created TIMESTAMP DEFAULT CURRENT_TIMESTAMP)";

    // 会话断层表，见 ConversationGapFiller。在 onOpen 里建表而不升 DATABASE_VERSION：
    // onUpgrade 会删光消息和会话，升版本等于清空用户本地聊天记录
    private static final String queryCreateConversationGapsTable = "CREATE TABLE IF NOT EXISTS '" + TABLE_CONVERSATION_GAPS + "' " +
            "(gap_target TEXT NOT NULL," +
            " gap_before_uid INTEGER NOT NULL," +
            " PRIMARY KEY (gap_target, gap_before_uid))";

    private static final int DATABASE_VERSION       = 3;

    private static ChatStoreHelper sInstance;

    public static ChatStoreHelper getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new ChatStoreHelper(context.getApplicationContext());
        }
        return sInstance;
    }

    private ChatStoreHelper(Context context){
        this(context,DATABASE_NAME);
    }

    private ChatStoreHelper(Context context,String databaseName){
        super(context,databaseName,null,DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        logger.i("onzCreate sql messages and conversations");
        db.execSQL(queryCreateMessagesTable);
        db.execSQL(queryCreateConversationsTable);
        db.execSQL(queryCreateConversationGapsTable);
    }

    @Override
    public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        db.execSQL(queryCreateConversationGapsTable);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        logger.i(TAG, "Upgrading database from version " + oldVersion + " to "
                + newVersion + ". Destroying old data now..");
        db.execSQL(queryDropMessagesTable);
        db.execSQL(queryDropConversationsTable);
        onCreate(db);
    }
}
