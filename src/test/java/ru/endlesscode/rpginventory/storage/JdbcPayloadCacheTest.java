package ru.endlesscode.rpginventory.storage;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.sql.DriverManager;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** 用可观测缓存边界检查 SQL 提交时序；缓存本身的网络/过期行为由真实 Redis 测试负责。 */
public class JdbcPayloadCacheTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    /** 缓存发布时另一个 SQL 连接必须已看见新版本，发布故障也不能回滚已经提交的数据。 */
    @Test public void cachePublicationFollowsCommitAndFailureDoesNotChangeSuccess() throws Exception {
        String url = "jdbc:sqlite:" + temporary.newFile().getAbsolutePath();
        StorageKey key = new StorageKey(StorageKey.Kind.PLAYER, UUID.randomUUID());
        AtomicInteger published = new AtomicInteger();
        InventoryPayloadCache cache = new InventoryPayloadCache() {
            @Override public byte[] get(StorageKey ignored, long revision) { throw new IllegalStateException("cache read unavailable"); }
            @Override public void put(StorageKey ignored, long revision, byte[] payload) {
                try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement();
                     var rows = statement.executeQuery("SELECT revision,payload FROM sx_rpginventory_records")) {
                    assertTrue(rows.next());
                    assertEquals(revision, rows.getLong(1));
                    assertArrayEquals(payload, rows.getBytes(2));
                    published.incrementAndGet();
                } catch (Exception e) { throw new AssertionError("Cache publication preceded SQL commit", e); }
                throw new IllegalStateException("cache write unavailable");
            }
            @Override public void close() { throw new IllegalStateException("cache close unavailable"); }
        };
        try (JdbcInventoryRepository repository = new JdbcInventoryRepository(url, "", "", "commit", 1, cache)) {
            repository.acquire(key, "writer", 60000);
            assertEquals(1, repository.save(key, "writer", 0, new byte[]{6, 0, -1}, 60000, true));
            assertEquals(1, published.get());
            assertArrayEquals(new byte[]{6, 0, -1}, repository.acquire(key, "reader", 60000).payload());
            assertEquals(2, published.get());
        }
    }

    /** 没有 SQL payload 的首次记录不能从缓存伪造历史数据，hasData 也只能查数据库。 */
    @Test public void cacheCannotInventDataForAnEmptySqlRecord() throws Exception {
        String url = "jdbc:sqlite:" + temporary.newFile().getAbsolutePath();
        StorageKey key = new StorageKey(StorageKey.Kind.BACKPACK, UUID.randomUUID());
        AtomicInteger reads = new AtomicInteger();
        InventoryPayloadCache cache = new InventoryPayloadCache() {
            @Override public byte[] get(StorageKey ignored, long revision) { reads.incrementAndGet(); return new byte[]{9}; }
            @Override public void put(StorageKey ignored, long revision, byte[] payload) { fail("An empty SQL record has no snapshot to publish"); }
            @Override public void close() { }
        };
        try (JdbcInventoryRepository repository = new JdbcInventoryRepository(url, "", "", "empty", 1, cache)) {
            assertFalse(repository.hasData(key));
            assertNull(repository.acquire(key, "new", 60000).payload());
            assertFalse(repository.hasData(key));
            assertEquals(0, reads.get());
        }
    }
}
