/**
 * Copyright (c) 2013-2024 Nikita Koksharov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.redisson;

import org.redisson.api.RFuture;
import org.redisson.api.RKeys;
import org.redisson.api.RObject;
import org.redisson.api.RType;
import org.redisson.client.RedisClient;
import org.redisson.client.RedisException;
import org.redisson.client.codec.StringCodec;
import org.redisson.client.handler.State;
import org.redisson.client.protocol.RedisCommand;
import org.redisson.client.protocol.RedisCommands;
import org.redisson.client.protocol.RedisStrictCommand;
import org.redisson.client.protocol.convertor.Convertor;
import org.redisson.client.protocol.decoder.ListMultiDecoder2;
import org.redisson.client.protocol.decoder.ListScanResult;
import org.redisson.client.protocol.decoder.ListScanResultReplayDecoder;
import org.redisson.client.protocol.decoder.ObjectListReplayDecoder;
import org.redisson.command.CommandAsyncExecutor;
import org.redisson.command.CommandBatchService;
import org.redisson.connection.ConnectionManager;
import org.redisson.connection.MasterSlaveEntry;
import org.redisson.iterator.RedissonBaseIterator;
import org.redisson.misc.CompletableFutureWrapper;
import org.redisson.misc.CompositeIterable;
import org.redisson.reactive.CommandReactiveBatchService;
import org.redisson.rx.CommandRxBatchService;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * 
 * @author Nikita Koksharov
 *
 */
public class RedissonKeys implements RKeys {

    private final CommandAsyncExecutor commandExecutor;

    public RedissonKeys(CommandAsyncExecutor commandExecutor) {
        super();
        this.commandExecutor = commandExecutor;
    }

    public CommandAsyncExecutor getCommandExecutor() {
        return commandExecutor;
    }

    public ConnectionManager getConnectionManager() {
        return commandExecutor.getConnectionManager();
    }

    @Override
    public RType getType(String key) {
        return commandExecutor.get(getTypeAsync(key));
    }

    @Override
    public RFuture<RType> getTypeAsync(String key) {
        return commandExecutor.readAsync(map(key), RedisCommands.TYPE, map(key));
    }

    @Override
    public int getSlot(String key) {
        return commandExecutor.get(getSlotAsync(key));
    }

    @Override
    public RFuture<Integer> getSlotAsync(String key) {
        return commandExecutor.readAsync(null, RedisCommands.KEYSLOT, map(key));
    }

    @Override
    public Iterable<String> getKeysByPattern(String pattern) {
        return getKeysByPattern(pattern, 10);
    }

    private final RedisCommand<ListScanResult<String>> scan = new RedisCommand<ListScanResult<String>>("SCAN", new ListMultiDecoder2(
                                                                                new ListScanResultReplayDecoder() {
                                                                                    @Override
                                                                                    public ListScanResult<Object> decode(List<Object> parts, State state) {
                                                                                        return new ListScanResult<>((String) parts.get(0), (List<Object>) (Object) unmap((List<String>) parts.get(1)));
                                                                                    }
                                                                                }, new ObjectListReplayDecoder<String>()));

    @Override
    public Iterable<String> getKeysByPattern(String pattern, int count) {
        return getKeysByPattern(scan, pattern, 0, count);
    }

    public <T> Iterable<T> getKeysByPattern(RedisCommand<?> command, String pattern, int limit, int count) {
        List<Iterable<T>> iterables = new ArrayList<>();
        for (MasterSlaveEntry entry : commandExecutor.getConnectionManager().getEntrySet()) {
            Iterable<T> iterable = new Iterable<T>() {
                @Override
                public Iterator<T> iterator() {
                    return createKeysIterator(entry, command, pattern, count);
                }
            };
            iterables.add(iterable);
        }
        return new CompositeIterable<T>(iterables, limit);
    }

    @Override
    public Iterable<String> getKeysWithLimit(int limit) {
        return getKeysWithLimit(null, limit);
    }

    @Override
    public Iterable<String> getKeysWithLimit(String pattern, int limit) {
        return getKeysByPattern(scan, pattern, limit, limit);
    }

    @Override
    public Iterable<String> getKeys() {
        return getKeysByPattern(null);
    }

    @Override
    public Iterable<String> getKeys(int count) {
        return getKeysByPattern(null, count);
    }

    private RFuture<ScanResult<Object>> scanIteratorAsync(RedisClient client, MasterSlaveEntry entry, RedisCommand<?> command, String startPos,
                                                             String pattern, int count) {
        if (pattern == null) {
            return commandExecutor.readAsync(client, entry, StringCodec.INSTANCE, command, startPos, "COUNT",
                    count);
        }

        pattern = map(pattern);
        return commandExecutor.readAsync(client, entry, StringCodec.INSTANCE, command, startPos, "MATCH",
                pattern, "COUNT", count);
    }

    public RFuture<ScanResult<Object>> scanIteratorAsync(RedisClient client, MasterSlaveEntry entry, String startPos,
            String pattern, int count) {
        return scanIteratorAsync(client, entry, scan, startPos, pattern, count);
    }

    private <T> Iterator<T> createKeysIterator(MasterSlaveEntry entry, RedisCommand<?> command, String pattern, int count) {
        return new RedissonBaseIterator<T>() {

            @Override
            protected ScanResult<Object> iterator(RedisClient client, String nextIterPos) {
                return commandExecutor
                        .get(RedissonKeys.this.scanIteratorAsync(client, entry, command, nextIterPos, pattern, count));
            }

            @Override
            protected void remove(Object value) {
                RedissonKeys.this.delete((String) value);
            }

        };
    }

    @Override
    public long touch(String... names) {
        return commandExecutor.get(touchAsync(names));
    }

    @Override
    public RFuture<Long> touchAsync(String... names) {
        if (names.length == 0) {
            return new CompletableFutureWrapper<>(0L);
        }

        return commandExecutor.writeBatchedAsync(null, RedisCommands.TOUCH_LONG, new LongSlotCallback(), map(names));
    }

    @Override
    public long countExists(String... names) {
        return commandExecutor.get(countExistsAsync(names));
    }

    @Override
    public RFuture<Long> countExistsAsync(String... names) {
        if (names.length == 0) {
            return new CompletableFutureWrapper<>(0L);
        }

        return commandExecutor.readBatchedAsync(StringCodec.INSTANCE, RedisCommands.EXISTS_LONG, new LongSlotCallback(), map(names));
    }

    @Override
    public String randomKey() {
        return commandExecutor.get(randomKeyAsync());
    }

    private final RedisStrictCommand<String> randomKey = new RedisStrictCommand<String>("RANDOMKEY", new Convertor<String>() {
        @Override
        public String convert(Object obj) {
            if (obj == null) {
                return null;
            }
            return unmap((String) obj);
        }
    });

    @Override
    public RFuture<String> randomKeyAsync() {
        return commandExecutor.readRandomAsync(StringCodec.INSTANCE, randomKey);
    }

    @Override
    public long deleteByPattern(String pattern) {
        return commandExecutor.get(deleteByPatternAsync(pattern));
    }

    @Override
    public RFuture<Long> deleteByPatternAsync(String pattern) {
        if (commandExecutor instanceof CommandBatchService
                || commandExecutor instanceof CommandReactiveBatchService
                    || commandExecutor instanceof CommandRxBatchService) {
            if (getConnectionManager().isClusterMode()) {
                throw new IllegalStateException("This method doesn't work in batch for Redis cluster mode. For Redis cluster execute it as non-batch method");
            }

            return commandExecutor.evalWriteAsync((String) null, null, RedisCommands.EVAL_LONG, 
                            "local keys = redis.call('keys', ARGV[1]) "
                              + "local n = 0 "
                              + "for i=1, #keys,5000 do "
                                  + "n = n + redis.call('del', unpack(keys, i, math.min(i+4999, table.getn(keys)))) "
                              + "end "
                          + "return n;", Collections.emptyList(), pattern);
        }
        
        int batchSize = 500;
        List<CompletableFuture<Long>> futures = new ArrayList<>();
        for (MasterSlaveEntry entry : commandExecutor.getConnectionManager().getEntrySet()) {
            CompletableFuture<Long> future = new CompletableFuture<>();
            futures.add(future);
            commandExecutor.getServiceManager().getExecutor().execute(() -> {
                long count = 0;
                try {
                    Iterator<String> keysIterator = createKeysIterator(entry, scan, pattern, batchSize);
                    List<String> keys = new ArrayList<>();
                    while (keysIterator.hasNext()) {
                        String key = keysIterator.next();
                        keys.add(key);

                        if (keys.size() % batchSize == 0) {
                            count += delete(keys.toArray(new String[0]));
                            keys.clear();
                        }
                    }

                    if (!keys.isEmpty()) {
                        count += delete(keys.toArray(new String[0]));
                        keys.clear();
                    }

                    future.complete(count);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });
        }

        CompletableFuture<Void> future = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
        CompletableFuture<Long> res = future.handle((r, e) -> {
            long cc = futures.stream()
                    .filter(f -> f.isDone())
                    .mapToLong(f -> f.getNow(0L))
                    .sum();
            if (e != null) {
                if (cc > 0) {
                    RedisException ex = new RedisException(
                            cc + " keys have been deleted. But one or more nodes has an error", e);
                    throw new CompletionException(ex);
                } else {
                    throw new CompletionException(e);
                }
            }

            return cc;
        });
        return new CompletableFutureWrapper<>(res);
    }

    @Override
    public long delete(String... keys) {
        return commandExecutor.get(deleteAsync(keys));
    }

    @Override
    public long delete(RObject... objects) {
        return commandExecutor.get(deleteAsync(objects));
    }

    @Override
    public RFuture<Long> deleteAsync(RObject... objects) {
        List<String> keys = new ArrayList<>();
        for (RObject obj : objects) {
            keys.add(obj.getName());
        }

        return deleteAsync(keys.toArray(new String[0]));
    }

    @Override
    public long unlink(String... keys) {
        return commandExecutor.get(unlinkAsync(keys));
    }

    @Override
    public RFuture<Long> unlinkAsync(String... keys) {
        if (keys.length == 0) {
            return new CompletableFutureWrapper<>(0L);
        }

        return commandExecutor.writeBatchedAsync(null, RedisCommands.UNLINK, new LongSlotCallback(), map(keys));
    }

    @Override
    public RFuture<Long> deleteAsync(String... keys) {
        if (keys.length == 0) {
            return new CompletableFutureWrapper<>(0L);
        }

        return commandExecutor.writeBatchedAsync(null, RedisCommands.DEL, new LongSlotCallback(), map(keys));
    }

    private String map(String key) {
        return commandExecutor.getServiceManager().getConfig().getNameMapper().map(key);
    }

    private String unmap(String key) {
        return commandExecutor.getServiceManager().getConfig().getNameMapper().unmap(key);
    }

    private List<String> unmap(List<String> keys) {
        return keys.stream()
                .map(k -> commandExecutor.getServiceManager().getConfig().getNameMapper().unmap(k))
                .collect(Collectors.toList());
    }

    private String[] map(String[] keys) {
        return Arrays.stream(keys)
                .map(k -> commandExecutor.getServiceManager().getConfig().getNameMapper().map(k))
                .toArray(String[]::new);
    }

    @Override
    public long count() {
        return commandExecutor.get(countAsync());
    }

    @Override
    public RFuture<Long> countAsync() {
        List<CompletableFuture<Long>> futures = commandExecutor.readAllAsync(RedisCommands.DBSIZE);
        CompletableFuture<Void> f = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
        CompletableFuture<Long> s = f.thenApply(r -> futures.stream().mapToLong(v -> v.getNow(0L)).sum());
        return new CompletableFutureWrapper<>(s);
    }

    @Override
    public void flushdbParallel() {
        commandExecutor.get(flushdbParallelAsync());
    }

    @Override
    public RFuture<Void> flushdbParallelAsync() {
        return commandExecutor.writeAllVoidAsync(RedisCommands.FLUSHDB_ASYNC);
    }

    @Override
    public void flushallParallel() {
        commandExecutor.get(flushallParallelAsync());
    }

    @Override
    public RFuture<Void> flushallParallelAsync() {
        return commandExecutor.writeAllVoidAsync(RedisCommands.FLUSHALL_ASYNC);
    }

    @Override
    public void flushdb() {
        commandExecutor.get(flushdbAsync());
    }

    @Override
    public RFuture<Void> flushdbAsync() {
        return commandExecutor.writeAllVoidAsync(RedisCommands.FLUSHDB);
    }

    @Override
    public void flushall() {
        commandExecutor.get(flushallAsync());
    }

    @Override
    public RFuture<Void> flushallAsync() {
        return commandExecutor.writeAllVoidAsync(RedisCommands.FLUSHALL);
    }

    @Override
    public long remainTimeToLive(String name) {
        return commandExecutor.get(remainTimeToLiveAsync(name));
    }

    @Override
    public RFuture<Long> remainTimeToLiveAsync(String name) {
        return commandExecutor.readAsync(map(name), StringCodec.INSTANCE, RedisCommands.PTTL, map(name));
    }

    @Override
    public void rename(String currentName, String newName) {
        commandExecutor.get(renameAsync(currentName, newName));
    }

    @Override
    public RFuture<Void> renameAsync(String currentName, String newName) {
        return commandExecutor.writeAsync(map(currentName), RedisCommands.RENAME, map(currentName), map(newName));
    }

    @Override
    public boolean renamenx(String oldName, String newName) {
        return commandExecutor.get(renamenxAsync(oldName, newName));
    }

    @Override
    public RFuture<Boolean> renamenxAsync(String oldName, String newName) {
        return commandExecutor.writeAsync(map(oldName), RedisCommands.RENAMENX, map(oldName), map(newName));
    }

    @Override
    public boolean clearExpire(String name) {
        return commandExecutor.get(clearExpireAsync(name));
    }

    @Override
    public RFuture<Boolean> clearExpireAsync(String name) {
        return commandExecutor.writeAsync(map(name), StringCodec.INSTANCE, RedisCommands.PERSIST, map(name));
    }

    @Override
    public boolean expireAt(String name, long timestamp) {
        return commandExecutor.get(expireAtAsync(name, timestamp));
    }

    @Override
    public RFuture<Boolean> expireAtAsync(String name, long timestamp) {
        return commandExecutor.writeAsync(map(name), StringCodec.INSTANCE, RedisCommands.PEXPIREAT, map(name), timestamp);
    }

    @Override
    public boolean expire(String name, long timeToLive, TimeUnit timeUnit) {
        return commandExecutor.get(expireAsync(name, timeToLive, timeUnit));
    }

    @Override
    public RFuture<Boolean> expireAsync(String name, long timeToLive, TimeUnit timeUnit) {
        return commandExecutor.writeAsync(map(name), StringCodec.INSTANCE, RedisCommands.PEXPIRE, map(name),
                timeUnit.toMillis(timeToLive));
    }

    @Override
    public void migrate(String name, String host, int port, int database, long timeout) {
        commandExecutor.get(migrateAsync(name, host, port, database, timeout));
    }

    @Override
    public RFuture<Void> migrateAsync(String name, String host, int port, int database, long timeout) {
        return commandExecutor.writeAsync(map(name), RedisCommands.MIGRATE, host, port, map(name), database, timeout);
    }

    @Override
    public void copy(String name, String host, int port, int database, long timeout) {
        commandExecutor.get(copyAsync(name, host, port, database, timeout));
    }

    @Override
    public RFuture<Void> copyAsync(String name, String host, int port, int database, long timeout) {
        return commandExecutor.writeAsync(map(name), RedisCommands.MIGRATE, host, port, map(name), database, timeout, "COPY");
    }

    @Override
    public boolean move(String name, int database) {
        return commandExecutor.get(moveAsync(name, database));
    }

    @Override
    public RFuture<Boolean> moveAsync(String name, int database) {
        return commandExecutor.writeAsync(map(name), RedisCommands.MOVE, map(name), database);
    }

    @Override
    public Stream<String> getKeysStreamByPattern(String pattern) {
        return toStream(getKeysByPattern(pattern).iterator());
    }

    protected <T> Stream<T> toStream(Iterator<T> iterator) {
        Spliterator<T> spliterator = Spliterators.spliteratorUnknownSize(iterator, Spliterator.NONNULL);
        return StreamSupport.stream(spliterator, false);
    }

    @Override
    public Stream<String> getKeysStreamByPattern(String pattern, int count) {
        return toStream(getKeysByPattern(pattern, count).iterator());
    }

    @Override
    public Stream<String> getKeysStream() {
        return toStream(getKeys().iterator());
    }

    @Override
    public Stream<String> getKeysStream(int count) {
        return toStream(getKeys(count).iterator());
    }

    @Override
    public void swapdb(int db1, int db2) {
        commandExecutor.get(swapdbAsync(db1, db2));
    }

    @Override
    public RFuture<Void> swapdbAsync(int db1, int db2) {
        return commandExecutor.writeAsync(null, RedisCommands.SWAPDB, db1, db2);
    }
}
