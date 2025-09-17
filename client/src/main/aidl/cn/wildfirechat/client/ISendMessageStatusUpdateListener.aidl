// ISendMessageStatusUpdateListener.aidl
package cn.wildfirechat.client;

// Declare any non-default types here with import statements

interface ISendMessageStatusUpdateListener{
   void onSendMessage(long messageId, int status);
}