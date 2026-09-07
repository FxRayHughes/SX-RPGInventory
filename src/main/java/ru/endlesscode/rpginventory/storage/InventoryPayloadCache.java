package ru.endlesscode.rpginventory.storage;

/**
 * 可丢弃的 SQL 快照缓存；调用者必须先从数据库取得租约与版本，不能用缓存决定所有权或记录是否存在。
 * 实现绑定一个数据库身份及 namespace；同版本快照不可变，旧版本写入不能覆盖新版本。
 */
public interface InventoryPayloadCache extends AutoCloseable {
    /** 仅返回指定 SQL 版本的完整快照；缺失、损坏或不可用均返回 null，由调用者读取 SQL。 */
    byte[] get(StorageKey key, long revision);

    /** 仅在 SQL 提交成功后发布快照，缓存保存失败不得影响已经提交的数据。 */
    void put(StorageKey key, long revision, byte[] payload);

    /** 缓存连接随所属 SQL 仓库关闭；关闭不删除任何服务器上的键。 */
    @Override void close();
}
