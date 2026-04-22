// IOnSaiTokenListener.aidl
package cn.wildfirechat.client;

interface IOnSaiTokenListener {
    void onSaiToken(String streamId, String delta, boolean done, String target);
}
