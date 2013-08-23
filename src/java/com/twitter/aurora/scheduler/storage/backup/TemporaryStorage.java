package com.twitter.aurora.scheduler.storage.backup;

import java.util.Set;

import com.google.common.base.Function;

import com.twitter.aurora.gen.ScheduledTask;
import com.twitter.aurora.gen.storage.Snapshot;
import com.twitter.aurora.scheduler.base.Query;
import com.twitter.aurora.scheduler.storage.SnapshotStore;
import com.twitter.aurora.scheduler.storage.Storage;
import com.twitter.aurora.scheduler.storage.Storage.MutableStoreProvider;
import com.twitter.aurora.scheduler.storage.Storage.MutateWork;
import com.twitter.aurora.scheduler.storage.Storage.StoreProvider;
import com.twitter.aurora.scheduler.storage.Storage.Work;
import com.twitter.aurora.scheduler.storage.log.SnapshotStoreImpl;
import com.twitter.aurora.scheduler.storage.mem.MemStorage;
import com.twitter.common.util.testing.FakeClock;

/**
 * A short-lived in-memory storage system that can be converted to a {@link Snapshot}.
 */
interface TemporaryStorage {

  /**
   * Deletes all tasks matching a query.  Deleted tasks will not be reflected in the snapshot when
   * {@link #toSnapshot()} is executed.
   *
   * @param query Query builder for tasks to delete.
   */
  void deleteTasks(Query.Builder query);

  /**
   * Fetches tasks matching a query.
   *
   * @param query Query builder for tasks to fetch.
   * @return Matching tasks.
   */
  Set<ScheduledTask> fetchTasks(Query.Builder query);

  /**
   * Creates a snapshot of the contents of the temporary storage.
   *
   * @return Temporary storage snapshot.
   */
  Snapshot toSnapshot();

  /**
   * A factory that creates temporary storage instances, detached from the rest of the system.
   */
  class TemporaryStorageFactory implements Function<Snapshot, TemporaryStorage> {
    @Override public TemporaryStorage apply(Snapshot snapshot) {
      final Storage storage = MemStorage.newEmptyStorage();
      FakeClock clock = new FakeClock();
      clock.setNowMillis(snapshot.getTimestamp());
      final SnapshotStore<Snapshot> snapshotStore = new SnapshotStoreImpl(clock, storage);
      snapshotStore.applySnapshot(snapshot);

      return new TemporaryStorage() {
        @Override public void deleteTasks(final Query.Builder query) {
          storage.write(new MutateWork.NoResult.Quiet() {
            @Override protected void execute(MutableStoreProvider storeProvider) {
              storeProvider.getUnsafeTaskStore()
                  .deleteTasks(storeProvider.getTaskStore().fetchTaskIds(query));
            }
          });
        }

        @Override public Set<ScheduledTask> fetchTasks(final Query.Builder query) {
          return storage.consistentRead(new Work.Quiet<Set<ScheduledTask>>() {
            @Override public Set<ScheduledTask> apply(StoreProvider storeProvider) {
              return storeProvider.getTaskStore().fetchTasks(query);
            }
          });
        }

        @Override public Snapshot toSnapshot() {
          return snapshotStore.createSnapshot();
        }
      };
    }
  }
}
