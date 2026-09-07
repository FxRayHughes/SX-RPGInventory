package ru.endlesscode.rpginventory.storage;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedConstruction;
import redis.clients.jedis.JedisPooled;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mockConstruction;

/** 真实 Redis 与临时 SQLite 联合验收；缓存可删除且不可越过 SQL 提交、版本和租约边界。 */
public class RedisPayloadCacheTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    /** 启动方会打印完整异常链，非法 URI 的错误不能携带地址中的密码。 */
    @Test public void invalidCacheUriDoesNotExposeCredentials() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.set("storage.redis.enabled", true);
        config.set("storage.redis.uri", "redis://user:example-secret@localhost/invalid path");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> RepositoryFactory.open(config, temporary.newFolder().toPath()));
        java.io.StringWriter trace = new java.io.StringWriter();
        failure.printStackTrace(new java.io.PrintWriter(trace));
        assertFalse(trace.toString().contains("example-secret"));
        assertFalse(trace.toString().contains("redis://"));
        assertNull(failure.getCause());
        assertTrue(failure.getMessage().contains("storage.redis.uri"));

        // 语法合法仍可能被 Jedis 当作数据库索引解析，错误路径也不能输出密码片段。
        config.set("storage.redis.uri", "redis://:TOP/SECRET@localhost:6379/0");
        IllegalArgumentException semanticFailure = assertThrows(IllegalArgumentException.class,
                () -> RepositoryFactory.open(config, temporary.newFolder().toPath()));
        java.io.StringWriter semanticTrace = new java.io.StringWriter();
        semanticFailure.printStackTrace(new java.io.PrintWriter(semanticTrace));
        assertFalse(semanticTrace.toString().contains("SECRET"));
        assertFalse(semanticTrace.toString().contains("redis://"));
        assertNull(semanticFailure.getCause());
    }

    /** 正常命中省略大 payload 读取；缓存删除和丢失必须从数据库恢复同一份二进制物品。 */
    @Test public void cacheHitsAndDeletionNeverLoseCommittedDatabaseData() throws Exception {
        try (Fixture fixture = fixture(300)) {
            byte[] payload = new byte[]{0, -1, 5, 0, 42};
            fixture.saveFirst(payload);
            assertEquals(1, fixture.cache.puts);
            assertRead(fixture, "reader", payload, 1);
            assertEquals(1, fixture.cache.hits);
            Set<String> keys = fixture.keys();
            assertEquals(1, keys.size());
            long ttl = fixture.redis.ttl(keys.iterator().next());
            assertTrue("Cache writes must carry bounded expiry", ttl > 0 && ttl <= 300);
            fixture.deleteCache();
            assertRead(fixture, "after-delete", payload, 1);
            assertEquals(1, fixture.cache.misses);
            fixture.deleteCache();
            try (var uncached = new JdbcInventoryRepository(fixture.url, "", "", fixture.namespace, 1)) {
                assertArrayEquals(payload, uncached.acquire(fixture.key, "cache-disabled", 60000).payload());
                uncached.release(fixture.key, "cache-disabled");
            }
        }
    }

    /** 使用 Redis 实际 TTL 到期，不能只用客户端时钟或模拟 miss 代替过期回退。 */
    @Test public void expiredCacheReloadsCommittedPayload() throws Exception {
        try (Fixture fixture = fixture(1)) {
            byte[] payload = new byte[]{4, 3, 2, 1};
            fixture.saveFirst(payload);
            assertArrayEquals(payload, fixture.cache.delegate.get(fixture.key, 1));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            while (!fixture.keys().isEmpty() && System.nanoTime() < deadline) Thread.sleep(30);
            assertTrue("Redis TTL must remove cache entry", fixture.keys().isEmpty());
            assertRead(fixture, "expired", payload, 1);
            assertEquals(1, fixture.cache.misses);
        }
    }

    /** 不同 SQL revision 使用不同缓存键，即使旧提交晚到也不能污染新版本。 */
    @Test public void lateOldVersionCannotReplaceNewSqlCommit() throws Exception {
        try (Fixture fixture = fixture(300)) {
            byte[] first = new byte[]{1};
            byte[] second = new byte[]{2, 2};
            fixture.saveFirst(first);
            fixture.repository.acquire(fixture.key, "writer-2", 60000);
            assertEquals(2, fixture.repository.save(fixture.key, "writer-2", 1, second, 60000, true));
            fixture.cache.delegate.put(fixture.key, 1, first);
            assertArrayEquals(second, fixture.cache.delegate.get(fixture.key, 2));
            assertRead(fixture, "latest", second, 2);
        }
    }

    /** 截断、错误校验和或错误版本的缓存只能 miss，数据库原文必须被重新发布。 */
    @Test public void corruptedCacheEnvelopeFallsBackToDatabase() throws Exception {
        try (Fixture fixture = fixture(300)) {
            byte[] payload = new byte[]{8, 0, -9};
            fixture.saveFirst(payload);
            byte[] key = fixture.keys().iterator().next().getBytes(StandardCharsets.UTF_8);
            byte[] valid = fixture.redis.get(key);
            List<byte[]> corruptions = new ArrayList<>();
            corruptions.add(new byte[]{1, 2});
            byte[] wrongMagic = valid.clone(); wrongMagic[0] ^= 1; corruptions.add(wrongMagic);
            byte[] wrongVersion = valid.clone(); wrongVersion[11] ^= 1; corruptions.add(wrongVersion);
            byte[] wrongLength = valid.clone(); wrongLength[15] ^= 1; corruptions.add(wrongLength);
            byte[] wrongChecksum = valid.clone(); wrongChecksum[16] ^= 1; corruptions.add(wrongChecksum);
            int owner = 0;
            for (byte[] corruption : corruptions) {
                fixture.redis.set(key, corruption);
                assertRead(fixture, "corrupt-" + owner++, payload, 1);
                assertArrayEquals(payload, fixture.cache.delegate.get(fixture.key, 1));
            }
            assertEquals(corruptions.size(), fixture.cache.misses);
        }
    }

    /** 即使 Redis 已有快照，SQL 所有权冲突、过期和数据库故障也必须失败。 */
    @Test public void cachedPayloadCannotBypassLeaseOrDatabaseFailure() throws Exception {
        try (Fixture fixture = fixture(300)) {
            fixture.saveFirst(new byte[]{7});
            fixture.repository.acquire(fixture.key, "holder", 60000);
            int reads = fixture.cache.reads;
            int writes = fixture.cache.puts;
            assertThrows(StorageConflictException.class, () -> fixture.repository.acquire(fixture.key, "intruder", 60000));
            assertThrows(StorageConflictException.class, () -> fixture.repository.save(fixture.key, "intruder", 1, new byte[]{0}, 60000, true));
            assertEquals(reads, fixture.cache.reads);
            assertEquals(writes, fixture.cache.puts);
            try (var connection = DriverManager.getConnection(fixture.url); var statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE sx_rpginventory_records SET lease_until=0");
            }
            assertThrows(StorageConflictException.class, () -> fixture.repository.renew(fixture.key, "holder", 60000));
            assertThrows(StorageConflictException.class, () -> fixture.repository.save(fixture.key, "holder", 1, new byte[]{0}, 60000, true));
            assertEquals(writes, fixture.cache.puts);
            // 删除临时测试表制造真实 SQL 故障，Redis 中的正确快照仍必须无法授权读取。
            try (var connection = DriverManager.getConnection(fixture.url); var statement = connection.createStatement()) {
                statement.executeUpdate("DROP TABLE sx_rpginventory_records");
            }
            assertThrows(StorageException.class, () -> fixture.repository.acquire(fixture.key, "sql-down", 60000));
            assertThrows(StorageException.class, () -> fixture.repository.hasData(fixture.key));
            assertThrows(StorageException.class, () -> fixture.repository.save(fixture.key, "holder", 1, new byte[]{0}, 60000, true));
            assertEquals(reads, fixture.cache.reads);
            assertEquals(writes, fixture.cache.puts);
        }
    }

    /** 独立 TCP 转发的断开/恢复模拟 Redis 服务中断，不停止其他测试正在使用的服务。 */
    @Test public void redisRefusalAndRecoveryAutomaticallyUseSql() throws Exception {
        URI original = redisUri();
        Assume.assumeTrue("TCP fault test uses the local redis:// test service", "redis".equals(original.getScheme()));
        try (RedisProxy proxy = new RedisProxy(original)) {
            // 初始端口拒连也不能阻止 SQL 仓库构造与第一次提交。
            try (Fixture fixture = new Fixture(temporary.newFile().toPath(), original, proxy.uri(), 300)) {
                byte[] first = new byte[]{11};
                fixture.saveFirst(first);
                assertRead(fixture, "initial-refusal", first, 1);
                assertTrue(fixture.keys().isEmpty());
                proxy.start();
                awaitCacheRecovery(fixture, first, 1);
                assertRead(fixture, "connected", first, 1);
                int hits = fixture.cache.hits;
                proxy.stop();
                fixture.repository.acquire(fixture.key, "writer-disconnected", 60000);
                byte[] second = new byte[]{12, 13};
                assertEquals(2, fixture.repository.save(fixture.key, "writer-disconnected", 1, second, 60000, true));
                assertRead(fixture, "during-outage", second, 2);
                proxy.start();
                awaitCacheRecovery(fixture, second, 2);
                assertRead(fixture, "after-recovery", second, 2);
                assertTrue("Same cache instance must resume serving hits", fixture.cache.hits > hits);
            }
        }
    }

    /** 禁用缓存时不解析 Redis 地址；旧 Redis 主存储必须拒绝，不能静默创建空 SQLite。 */
    @Test public void disabledCacheIgnoresRedisAndLegacyBackendIsRejected() throws Exception {
        Path root = temporary.newFolder().toPath();
        YamlConfiguration config = new YamlConfiguration();
        config.set("storage.backend", "SQLITE");
        config.set("storage.redis.enabled", false);
        config.set("storage.redis.uri", "invalid cache URI which must not be used");
        StorageKey key = new StorageKey(StorageKey.Kind.PLAYER, UUID.randomUUID());
        try (InventoryRepository repository = RepositoryFactory.open(config, root)) {
            repository.acquire(key, "disabled", 60000);
            repository.save(key, "disabled", 0, new byte[]{99}, 60000, true);
            assertArrayEquals(new byte[]{99}, repository.acquire(key, "read", 60000).payload());
        }
        Path legacyRoot = temporary.newFolder().toPath();
        config.set("storage.backend", "REDIS");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> RepositoryFactory.open(config, legacyRoot));
        assertTrue("Migration refusal must explain the required migration", error.getMessage().contains("迁移"));
        assertFalse(Files.exists(legacyRoot.resolve("storage/inventories.db")));
    }

    /** 相同玩家和 revision 在不同数据库/namespace 中不能共享缓存。 */
    @Test public void cacheIdentityIncludesDatabaseAndNamespace() throws Exception {
        try (Fixture fixture = fixture(300);
             RedisPayloadCache otherDatabase = new RedisPayloadCache(fixture.uri, fixture.url + "other", fixture.namespace, 300, 200);
             RedisPayloadCache otherNamespace = new RedisPayloadCache(fixture.uri, fixture.url, fixture.namespace + "x", 300, 200)) {
            fixture.saveFirst(new byte[]{1});
            assertNull(otherDatabase.get(fixture.key, 1));
            assertNull(otherNamespace.get(fixture.key, 1));
        }
    }

    /** 相同 PostgreSQL URL 的不同账号可能选择不同 schema，工厂必须将账号加入缓存身份。 */
    @Test public void factoryCacheIdentitySeparatesDatabaseUsers() throws Exception {
        List<String> identities = new ArrayList<>();
        try (MockedConstruction<RedisPayloadCache> caches = mockConstruction(RedisPayloadCache.class,
                (cache, context) -> identities.add((String) context.arguments().get(1)));
             MockedConstruction<JdbcInventoryRepository> repositories = mockConstruction(JdbcInventoryRepository.class)) {
            YamlConfiguration config = new YamlConfiguration();
            config.set("storage.backend", "POSTGRESQL");
            config.set("storage.postgresql.url", "jdbc:postgresql://localhost/test");
            config.set("storage.redis.enabled", true);
            config.set("storage.redis.uri", "redis://localhost:6379/0");
            for (String user : new String[]{"schema_a", "schema_b"}) {
                config.set("storage.postgresql.username", user);
                try (InventoryRepository ignored = RepositoryFactory.open(config, temporary.newFolder().toPath())) { }
            }
            assertEquals(2, identities.size());
            assertNotEquals(identities.get(0), identities.get(1));
            assertEquals("jdbc:postgresql://localhost/test\nuser=schema_a", identities.get(0));
            assertEquals("jdbc:postgresql://localhost/test\nuser=schema_b", identities.get(1));
        }
    }

    /** PostgreSQL 使用真实 Redis 读取路径验收，不能只用 SQLite 推断所有 SQL 方言。 */
    @Test public void postgresqlRemainsAuthoritativeWithRealRedisCache() throws Exception {
        remoteSqlContract("POSTGRES");
    }

    /** MySQL 的持锁事务和二进制身份同样经过缓存启用路径。 */
    @Test public void mysqlRemainsAuthoritativeWithRealRedisCache() throws Exception {
        remoteSqlContract("MYSQL");
    }

    private static void remoteSqlContract(String backend) throws Exception {
        String url = System.getenv("SX_RPG_TEST_" + backend + "_URL");
        Assume.assumeTrue(backend + " integration environment not configured", url != null);
        String user = System.getenv("SX_RPG_TEST_" + backend + "_USER");
        String password = System.getenv("SX_RPG_TEST_" + backend + "_PASSWORD");
        URI uri = redisUri();
        String namespace = "cachetest_" + UUID.randomUUID().toString().replace("-", "");
        StorageKey key = new StorageKey(StorageKey.Kind.BACKPACK, UUID.randomUUID());
        byte[] payload = new byte[70001];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i * 31);
        try (JedisPooled redis = new JedisPooled(uri)) {
            RecordingCache cache = new RecordingCache(new RedisPayloadCache(uri, url + "\nuser=" + user, namespace, 300, 200));
            try (JdbcInventoryRepository repository = new JdbcInventoryRepository(url, user, password, namespace, 2, cache)) {
                repository.acquire(key, "a", 60000);
                repository.save(key, "a", 0, payload, 60000, true);
                assertArrayEquals(payload, repository.acquire(key, "b", 60000).payload());
                assertEquals(1, cache.hits);
                assertThrows(StorageConflictException.class, () -> repository.acquire(key, "other", 60000));
                repository.release(key, "b");
                for (String cachedKey : redis.keys("sx-rpginventory:cache:v1:*:" + namespace + ":*")) redis.del(cachedKey);
                assertArrayEquals(payload, repository.acquire(key, "after-delete", 60000).payload());
                assertEquals(1, cache.misses);
                repository.release(key, "after-delete");
            } finally {
                for (String cachedKey : redis.keys("sx-rpginventory:cache:v1:*:" + namespace + ":*")) redis.del(cachedKey);
            }
            try (JdbcInventoryRepository reopened = new JdbcInventoryRepository(url, user, password, namespace, 2)) {
                assertArrayEquals(payload, reopened.acquire(key, "without-cache", 60000).payload());
                reopened.release(key, "without-cache");
            }
        }
    }

    private Fixture fixture(int ttl) throws Exception {
        URI uri = redisUri();
        return new Fixture(temporary.newFile().toPath(), uri, uri, ttl);
    }

    private static URI redisUri() {
        String uri = System.getenv("SX_RPG_TEST_REDIS_URI");
        Assume.assumeTrue("Redis integration environment not configured", uri != null && !uri.isEmpty());
        return URI.create(uri);
    }

    private static void assertRead(Fixture fixture, String owner, byte[] payload, long revision) {
        StoredRecord record = fixture.repository.acquire(fixture.key, owner, 60000);
        assertEquals(revision, record.revision());
        assertArrayEquals(payload, record.payload());
        fixture.repository.release(fixture.key, owner);
    }

    private static void awaitCacheRecovery(Fixture fixture, byte[] payload, long revision) throws Exception {
        // 生产缓存采用 5 秒熔断；有界重试验证真实连接恢复，不能新建缓存实例绕过恢复逻辑。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (System.nanoTime() < deadline) {
            assertRead(fixture, "recovery", payload, revision);
            if (fixture.cache.delegate.get(fixture.key, revision) != null) return;
            Thread.sleep(100);
        }
        fail("Redis cache did not recover within the bounded retry window");
    }

    /** 统计真实缓存返回结果；委托仍使用 Redis 网络协议，不以 mock 代替命中和格式校验。 */
    private static final class RecordingCache implements InventoryPayloadCache {
        final RedisPayloadCache delegate;
        int reads, hits, misses, puts;
        RecordingCache(RedisPayloadCache delegate) { this.delegate = delegate; }
        @Override public byte[] get(StorageKey key, long revision) {
            reads++;
            byte[] payload = delegate.get(key, revision);
            if (payload == null) misses++; else hits++;
            return payload;
        }
        @Override public void put(StorageKey key, long revision, byte[] payload) { puts++; delegate.put(key, revision, payload); }
        @Override public void close() { delegate.close(); }
    }

    /** 每例使用独立 SQL 文件和随机 namespace，只清理自己的可丢弃缓存前缀。 */
    private static final class Fixture implements AutoCloseable {
        final String namespace = "cachetest_" + UUID.randomUUID().toString().replace("-", "");
        final StorageKey key = new StorageKey(StorageKey.Kind.PLAYER, UUID.randomUUID());
        final String url;
        final URI uri;
        final JedisPooled redis;
        final RecordingCache cache;
        final JdbcInventoryRepository repository;
        Fixture(Path database, URI directUri, URI cacheUri, int ttl) {
            url = "jdbc:sqlite:" + database.toAbsolutePath();
            uri = directUri;
            redis = new JedisPooled(directUri);
            assertEquals("PONG", redis.ping());
            cache = new RecordingCache(new RedisPayloadCache(cacheUri, url, namespace, ttl, 200));
            repository = new JdbcInventoryRepository(url, "", "", namespace, 1, cache);
        }
        void saveFirst(byte[] payload) {
            assertNull(repository.acquire(key, "first", 60000).payload());
            assertEquals(1, repository.save(key, "first", 0, payload, 60000, true));
        }
        Set<String> keys() { return redis.keys("sx-rpginventory:cache:v1:*:" + namespace + ":*"); }
        void deleteCache() { for (String key : keys()) redis.del(key); }
        @Override public void close() {
            try { repository.close(); }
            finally { try { deleteCache(); } finally { redis.close(); } }
        }
    }

    /** 只转发测试连接的字节，断开时关闭全部旧连接，使拒连与恢复有明确边界。 */
    private static final class RedisProxy implements AutoCloseable {
        final URI target;
        final int port;
        final List<Socket> sockets = new CopyOnWriteArrayList<>();
        volatile ServerSocket listener;
        RedisProxy(URI target) throws IOException {
            this.target = target;
            try (ServerSocket reservation = new ServerSocket(0, 16, java.net.InetAddress.getLoopbackAddress())) {
                port = reservation.getLocalPort();
            }
        }
        URI uri() throws Exception { return new URI(target.getScheme(), target.getUserInfo(), "127.0.0.1", port, target.getPath(), target.getQuery(), null); }
        void start() throws IOException {
            ServerSocket server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress("127.0.0.1", port));
            listener = server;
            daemon(() -> {
                while (!server.isClosed()) {
                    try {
                        Socket incoming = server.accept();
                        Socket outgoing = new Socket();
                        sockets.add(incoming); sockets.add(outgoing);
                        outgoing.connect(new InetSocketAddress(target.getHost(), target.getPort() < 0 ? 6379 : target.getPort()), 1000);
                        daemon(() -> copy(incoming, outgoing));
                        daemon(() -> copy(outgoing, incoming));
                    } catch (IOException ignored) { /* 停止监听或断连是本测试主动制造的故障。 */ }
                }
            });
        }
        void stop() throws IOException {
            ServerSocket server = listener;
            listener = null;
            if (server != null) server.close();
            for (Socket socket : sockets) try { socket.close(); } catch (IOException ignored) { }
            sockets.clear();
        }
        private static void copy(Socket from, Socket to) {
            try {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = from.getInputStream().read(buffer)) >= 0) {
                    to.getOutputStream().write(buffer, 0, count);
                    to.getOutputStream().flush();
                }
            } catch (IOException ignored) { /* 连接故障由生产 Redis 客户端观察。 */ }
            finally {
                try { from.close(); } catch (IOException ignored) { }
                try { to.close(); } catch (IOException ignored) { }
            }
        }
        private static void daemon(Runnable action) { Thread thread = new Thread(action, "redis-cache-test-proxy"); thread.setDaemon(true); thread.start(); }
        @Override public void close() throws IOException { stop(); }
    }
}
